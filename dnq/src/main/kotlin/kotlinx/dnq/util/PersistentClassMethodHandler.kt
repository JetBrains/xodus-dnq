/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import com.jetbrains.teamsys.dnq.database.IncomingLinkViolation
import com.jetbrains.teamsys.dnq.database.PerEntityIncomingLinkViolation
import com.jetbrains.teamsys.dnq.database.PerTypeIncomingLinkViolation
import javassist.util.proxy.MethodHandler
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.wrapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.LinkedHashMap
import kotlin.reflect.jvm.javaGetter

private val xdMethodCache = ConcurrentHashMap<Method, Method?>()

class PersistentClassMethodHandler(self: BasePersistentClassImpl, xdEntityType: XdNaturalEntityType<*>, xdHierarchyNode: XdHierarchyNode) : MethodHandler {
    val xdEntityClass = xdEntityType.enclosingEntityClass
    val requireIfConstraints: Map<String, Collection<RequireIfConstraint<*, *>>>
    val customTargetDeletePolicies: Map<String, OnDeletePolicy>

    init {
        val requireIfConstraints = LinkedHashMap<String, MutableList<RequireIfConstraint<*, *>>>()
        val naturalProperties = xdHierarchyNode.getAllProperties()
                .filter { isNaturalEntity(getPropertyDeclaringClass(it)) }
        for (property in naturalProperties) {
            for (constraint in getPropertyConstraints(property.delegate)) {
                if (constraint is RequireIfConstraint<*, *>) {
                    requireIfConstraints.getOrPut(property.dbPropertyName) { ArrayList(1) }.add(constraint)
                } else {
                    self.addPropertyConstraint(property.dbPropertyName, constraint)
                }
            }
        }
        this.requireIfConstraints = requireIfConstraints

        customTargetDeletePolicies = xdHierarchyNode.getAllLinks()
                .filter { isNaturalEntity(getPropertyDeclaringClass(it)) }
                .filter { link ->
                    val onTargetDelete = link.delegate.onTargetDelete
                    onTargetDelete is OnDeletePolicy.FAIL_PER_TYPE || onTargetDelete is OnDeletePolicy.FAIL_PER_ENTITY
                }
                .map { it.dbPropertyName to it.delegate.onTargetDelete }
                .toMap()
    }

    override fun invoke(self: Any, thisMethod: Method, proceed: Method, args: Array<out Any?>): Any? {
        if (thisMethod.parameterTypes.isNotEmpty() && thisMethod.parameterTypes.last() == Entity::class.java) {
            if (thisMethod.isBeforeFlushCall()) {
                return invokeBeforeFlush(self, proceed, args)
            }
            if (thisMethod.isDestructorCall()) {
                return invokeDestructor(self, proceed, args)
            }
            if (thisMethod.isPropertyRequiredCall(args)) {
                return isPropertyRequired(self, proceed, args)
            }
            findNaturalMethod(thisMethod)?.let {
                if (isNaturalEntity(it.declaringClass)) {
                    return invokeNaturalMethod(it, args)
                }
            }
        }
        thisMethod.ifCreateIncomingLinkViolationCall(args) { linkName ->
            return createIncomingLinkViolation(linkName)
        }
        return invokeMethod(self, proceed, args)
    }

    private fun getPropertyDeclaringClass(property: XdHierarchyNode.MetaProperty) = property.property.javaGetter!!.declaringClass

    @Suppress("UNCHECKED_CAST")
    private fun isNaturalEntity(clazz: Class<*>) =
            XdEntity::class.java.isAssignableFrom(clazz) && clazz != XdEntity::class.java
                    && (clazz as Class<out XdEntity>).entityType is XdNaturalEntityType<*>

    private fun Method.isBeforeFlushCall() = name == BasePersistentClassImpl::executeBeforeFlushTrigger.name && parameterTypes.size == 1

    private fun invokeBeforeFlush(self: Any, method: Method, args: Array<out Any?>) {
        invokeMethod(self, method, args)
        (args.last() as Entity).wrapper.beforeFlush()
    }

    private fun Method.isDestructorCall() = name == BasePersistentClassImpl::destructor.name && parameterTypes.size == 1

    private fun invokeDestructor(self: Any, method: Method, args: Array<out Any?>) {
        (args.last() as Entity).wrapper.destructor()
        invokeMethod(self, method, args)
    }

    private fun Method.isPropertyRequiredCall(args: Array<out Any?>) = name == BasePersistentClassImpl::isPropertyRequired.name && parameterTypes.size == 2 && args[0] is String

    private fun isPropertyRequired(self: Any, method: Method, args: Array<out Any?>): Boolean {
        if (invokeMethod(self, method, args) as Boolean) {
            return true
        }
        val xdEntity = (args.last() as Entity).wrapper
        val propertyName = args[0] as String
        return requireIfConstraints.getOrElse(propertyName) { emptyList() }.any {
            @Suppress("UNCHECKED_CAST")
            with(xdEntity, it.predicate as XdEntity.() -> Boolean)
        }
    }

    private inline fun Method.ifCreateIncomingLinkViolationCall(args: Array<out Any?>, body: (linkName: String) -> Unit) {
        if (name == BasePersistentClassImpl::createIncomingLinkViolation.name && parameterTypes.size == 1) {
            val linkName = args.first()
            if (linkName is String) {
                body(linkName)
            }
        }
    }

    private fun createIncomingLinkViolation(linkName: String): IncomingLinkViolation {
        val onTargetDeletePolicy = customTargetDeletePolicies[linkName]
        return when (onTargetDeletePolicy) {
            is OnDeletePolicy.FAIL_PER_TYPE -> PerTypeIncomingLinkViolation(linkName, onTargetDeletePolicy.message)
            is OnDeletePolicy.FAIL_PER_ENTITY -> PerEntityIncomingLinkViolation(linkName, onTargetDeletePolicy.message)
            else -> IncomingLinkViolation(linkName)
        }
    }

    private fun findNaturalMethod(method: Method): Method? {
        return xdMethodCache.getOrPut(method) {
            val xdArgTypes = method.parameterTypes.let { it.copyOf(it.size - 1) }
            return try {
                xdEntityClass.getMethod(method.name, *xdArgTypes)
            } catch (e: NoSuchMethodException) {
                null
            }
        }
    }

    private fun invokeNaturalMethod(xdMethod: Method, args: Array<out Any?>): Any? {
        val xdEntity = (args.last() as Entity).wrapper
        val xdArgs = Arrays.copyOf(args, args.size - 1)
        return invokeMethod(xdEntity, xdMethod, xdArgs)
    }

    private fun invokeMethod(self: Any, method: Method, args: Array<out Any?>): Any? {
        try {
            return method.invoke(self, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

}