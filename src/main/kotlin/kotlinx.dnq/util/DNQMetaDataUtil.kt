package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import javassist.util.proxy.ProxyObject
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.*
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.XdWrappedProperty
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import kotlin.reflect.companionObjectInstance
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaType

open class CommonBasePersistentClass : BasePersistentClassImpl() {
    override fun run() = Unit
}

private class PersistentClassMethodHandler(self: Any, xdEntityType: XdNaturalEntityType<*>) : MethodHandler {
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

    private fun isNaturalEntity(entityClass: Class<*>) = entityClass.kotlin.companionObjectInstance is XdNaturalEntityType<*>

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
            throw e.cause!!
        }
    }

}

fun initMetaData(hierarchy: Map<String, XdHierarchyNode>, entityStore: TransientEntityStoreImpl) {
    val naturalNodes = hierarchy.filter {
        it.value.entityType is XdNaturalEntityType<*>
    }
    val modelMetaData = entityStore.modelMetaData as ModelMetaDataImpl

    naturalNodes.forEach {
        val (entityTypeName, node) = it
        modelMetaData.addEntityMetaData(entityTypeName, node)
        entityStore.setCachedPersistentClassInstance(entityTypeName, getPersistenceClassInstance(node))
    }

    naturalNodes.forEach {
        val (entityTypeName, node) = it
        node.linkProperties.values.forEach { sourceEnd ->
            modelMetaData.addLinkMetaData(hierarchy, entityTypeName, sourceEnd)
        }
    }

    entityStore.transactional {
        naturalNodes.values.asSequence().map {
            it.entityType
        }.filterIsInstance<XdNaturalEntityType<*>>().forEach {
            it.initEntityType()
        }
    }
}

private val persistenceClassInstanceCache = HashMap<XdEntityType<*>, BasePersistentClassImpl>()

private fun getPersistenceClassInstance(node: XdHierarchyNode) = persistenceClassInstanceCache.getOrPut(node.entityType) {
    val persistentClass = node.findClosestLegacyEntitySupertype()?.legacyClass ?: CommonBasePersistentClass::class.java
    return ProxyFactory().apply {
        superclass = persistentClass
        setFilter(::isNotFinalize)
        isUseCache = false
    }.create(emptyArray(), emptyArray()).apply {
        this as ProxyObject
        handler = PersistentClassMethodHandler(this, node.entityType as XdNaturalEntityType<*>)
    } as BasePersistentClassImpl
}

private fun isNotFinalize(method: Method) = !method.parameterTypes.isEmpty() || method.name != "finalize"

private fun ModelMetaDataImpl.addEntityMetaData(entityTypeName: String, node: XdHierarchyNode) {
    addEntityMetaData(EntityMetaDataImpl().apply {
        type = entityTypeName
        superType = node.parentNode?.entityType?.entityType

        propertiesMetaData = node.getAllProperties().map {
            SimplePropertyMetaDataImpl(
                    it.dbPropertyName,
                    (it.property.returnType.javaType as Class<*>).simpleName
            ).apply {
                type = it.delegate.propertyType
            }
        }.toList()

        requiredProperties = node.getAllProperties().filter {
            it.delegate.requirement == XdPropertyRequirement.REQUIRED
        }.map {
            it.dbPropertyName
        }.toSet()

        setRequiredIfProperties(node.getAllProperties().filter {
            getPropertyConstraints(it.delegate).any { it is RequireIfConstraint<*, *> }
        }.map {
            it.dbPropertyName
        }.toSet())

        // TODO: Support composite indexes
        ownIndexes = node.getAllProperties().filter {
            it.delegate.requirement == XdPropertyRequirement.UNIQUE
        }.map {
            IndexImpl().apply {
                setOwnerEnityType(entityTypeName)
                fields = listOf(IndexFieldImpl().apply {
                    name = it.dbPropertyName
                    isProperty = true
                })
            }
        }.toSet()
    })

}

private fun XdHierarchyNode.getAllProperties(): Sequence<XdHierarchyNode.SimpleProperty> {
    return (parentNode?.getAllProperties() ?: emptySequence()) + simpleProperties.values
}

private fun ModelMetaDataImpl.addLinkMetaData(hierarchy: Map<String, XdHierarchyNode>, entityTypeName: String, sourceEnd: XdHierarchyNode.LinkProperty) {
    when (sourceEnd.delegate.endType) {
        AssociationEndType.DirectedAssociationEnd -> {
            addLink(
                    sourceEntityName = entityTypeName,
                    targetEntityName = sourceEnd.delegate.oppositeEntityType.entityType,
                    type = AssociationType.Directed,

                    sourceName = sourceEnd.dbPropertyName,
                    sourceCardinality = sourceEnd.delegate.cardinality,
                    sourceCascadeDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                    sourceClearOnDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                    sourceTargetCascadeDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                    sourceTargetClearOnDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR,

                    targetName = null,
                    targetCardinality = null,
                    targetCascadeDelete = false,
                    targetClearOnDelete = false,
                    targetTargetCascadeDelete = false,
                    targetTargetClearOnDelete = false)
        }
        AssociationEndType.UndirectedAssociationEnd -> {
            val targetEnd = getTargetEnd(hierarchy, sourceEnd.delegate)
            val targetEntityType = sourceEnd.delegate.oppositeEntityType.entityType
            if (targetEnd != null && (entityTypeName < targetEntityType || (entityTypeName == targetEntityType && sourceEnd.dbPropertyName <= targetEnd.dbPropertyName))) {
                addLink(
                        sourceEntityName = entityTypeName,
                        targetEntityName = sourceEnd.delegate.oppositeEntityType.entityType,
                        type = AssociationType.Undirected,

                        sourceName = sourceEnd.dbPropertyName,
                        sourceCardinality = sourceEnd.delegate.cardinality,
                        sourceCascadeDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                        sourceClearOnDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                        sourceTargetCascadeDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                        sourceTargetClearOnDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR,

                        targetName = targetEnd.dbPropertyName,
                        targetCardinality = targetEnd.delegate.cardinality,
                        targetCascadeDelete = targetEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                        targetClearOnDelete = targetEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                        targetTargetCascadeDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                        targetTargetClearOnDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR)
            }
        }
        AssociationEndType.ParentEnd -> {
            val targetEnd = getTargetEnd(hierarchy, sourceEnd.delegate)
            if (targetEnd != null) {
                addLink(
                        sourceEntityName = entityTypeName,
                        targetEntityName = sourceEnd.delegate.oppositeEntityType.entityType,
                        type = AssociationType.Aggregation,

                        sourceName = sourceEnd.dbPropertyName,
                        sourceCardinality = sourceEnd.delegate.cardinality,
                        sourceCascadeDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                        sourceClearOnDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                        sourceTargetCascadeDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                        sourceTargetClearOnDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR,

                        targetName = targetEnd.dbPropertyName,
                        targetCardinality = targetEnd.delegate.cardinality,
                        targetCascadeDelete = targetEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                        targetClearOnDelete = targetEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                        targetTargetCascadeDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                        targetTargetClearOnDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR)
            }

        }
        AssociationEndType.ChildEnd -> {
            // Ignore
        }
    }
}

private fun getPropertyConstraints(property: XdConstrainedProperty<*, *>) =
        ((property as? XdWrappedProperty<*, *, *>)?.wrapped ?: property).constraints

private fun ModelMetaDataImpl.addLink(
        sourceEntityName: String, targetEntityName: String, type: AssociationType,

        sourceName: String, sourceCardinality: AssociationEndCardinality,
        sourceCascadeDelete: Boolean, sourceClearOnDelete: Boolean,
        sourceTargetCascadeDelete: Boolean, sourceTargetClearOnDelete: Boolean,

        targetName: String?, targetCardinality: AssociationEndCardinality?,
        targetCascadeDelete: Boolean, targetClearOnDelete: Boolean,
        targetTargetCascadeDelete: Boolean, targetTargetClearOnDelete: Boolean): AssociationMetaData =
        addAssociation(sourceEntityName, targetEntityName, type,

                sourceName, sourceCardinality,
                sourceCascadeDelete, sourceClearOnDelete,
                sourceTargetCascadeDelete, sourceTargetClearOnDelete,

                targetName, targetCardinality,
                targetCascadeDelete, targetClearOnDelete,
                targetTargetCascadeDelete, targetTargetClearOnDelete)

private fun getTargetEnd(hierarchy: Map<String, XdHierarchyNode>, sourceProperty: XdLink<*, *>): XdHierarchyNode.LinkProperty? {
    return hierarchy[sourceProperty.oppositeEntityType.entityType]?.linkProperties?.values?.firstOrNull {
        it.property.name == sourceProperty.oppositeField?.name
    }
}
