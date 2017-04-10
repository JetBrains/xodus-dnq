package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import javassist.util.proxy.MethodHandler
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.wrapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import kotlin.reflect.jvm.javaGetter

class PersistentClassMethodHandler(self: Any, xdEntityType: XdNaturalEntityType<*>) : MethodHandler {
    val xdEntityClass = xdEntityType.enclosingEntityClass
    val requireIfConstraints = LinkedHashMap<String, MutableCollection<RequireIfConstraint<*, *>>>()

    init {
        val propertyConstraintRegistry = getPropertyConstraintRegistry(self)

        val naturalProperties = getEntityProperties(xdEntityType).filter {
            isNaturalEntity(getPropertyDeclaringClass(it))
        }
        for (property in naturalProperties) {
            ArrayList<PropertyConstraint<*>>().run {
                for (constraint in getPropertyConstraints(property.delegate)) {
                    if (constraint is RequireIfConstraint<*, *>) {
                        requireIfConstraints.getOrPut(property.dbPropertyName) {
                            ArrayList<RequireIfConstraint<*, *>>()
                        } += constraint
                    } else {
                        this += constraint
                    }
                }

                if (isNotEmpty()) {
                    propertyConstraintRegistry[property.dbPropertyName] = this
                }
            }
        }
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
        return invokeMethod(self, proceed, args)
    }

    private fun getEntityProperties(xdEntityType: XdNaturalEntityType<*>) = XdModel[xdEntityType]!!.getAllProperties()

    private fun getPropertyDeclaringClass(property: XdHierarchyNode.SimpleProperty) = property.property.javaGetter!!.declaringClass

    @Suppress("UNCHECKED_CAST")
    private fun isNaturalEntity(clazz: Class<*>) =
            XdEntity::class.java.isAssignableFrom(clazz) && clazz != XdEntity::class.java
                    && (clazz as Class<out XdEntity>).entityType is XdNaturalEntityType<*>

    private fun getPropertyConstraintRegistry(self: Any): MutableMap<String, Iterable<PropertyConstraint<*>>> {
        val propertyConstraintsField = BasePersistentClassImpl::class.java.getDeclaredField("propertyConstraints")
        propertyConstraintsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        var propertyConstraints = propertyConstraintsField.get(self) as MutableMap<String, Iterable<PropertyConstraint<*>>>?
        if (propertyConstraints == null) {
            propertyConstraints = LinkedHashMap<String, Iterable<PropertyConstraint<*>>>()
            propertyConstraintsField.set(self, propertyConstraints)
        }
        return propertyConstraints
    }

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
        return requireIfConstraints.getOrElse(propertyName) { emptyList<RequireIfConstraint<*, *>>() }.any {
            @Suppress("UNCHECKED_CAST")
            with(xdEntity, it.predicate as XdEntity.() -> Boolean)
        }
    }

    private fun findNaturalMethod(method: Method): Method? {
        val xdArgTypes = method.parameterTypes.let { Arrays.copyOf(it, it.size - 1) }
        return try {
            xdEntityClass.getMethod(method.name, *xdArgTypes)
        } catch (e: NoSuchMethodException) {
            null
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