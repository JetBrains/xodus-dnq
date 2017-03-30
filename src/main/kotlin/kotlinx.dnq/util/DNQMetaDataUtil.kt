package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import javassist.util.proxy.ProxyFactory
import javassist.util.proxy.ProxyObject
import jetbrains.exodus.query.metadata.*
import kotlinx.dnq.XdLegacyEntityType
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.XdWrappedProperty
import kotlinx.dnq.transactional
import java.lang.reflect.Method
import java.util.*
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaType

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

    /**
     * This explicitly prepares all data structures within model metadata. If we don't invoke
     * preparation here, then it would be performed on demand and very likely simultaneously in two
     * threads: EventMultiplexer and the one executing App.init().
     */
    modelMetaData.prepare()

    entityStore.transactional {
        naturalNodes.values.asSequence().map {
            it.entityType
        }.filterIsInstance<XdNaturalEntityType<*>>().forEach {
            it.initEntityType()
        }
    }
}

private val persistenceClassInstanceCache = ConcurrentHashMap<XdNaturalEntityType<*>, BasePersistentClassImpl>()

private fun getPersistenceClassInstance(node: XdHierarchyNode) =
        persistenceClassInstanceCache.computeIfAbsent(node.entityType as XdNaturalEntityType<*>) {

    val persistentClass = findLegacyEntitySuperclass(node)?.legacyClass ?: CommonBasePersistentClass::class.java
    ProxyFactory().apply {
        superclass = persistentClass
        setFilter(::isNotFinalize)
        isUseCache = false
    }.create(emptyArray(), emptyArray()).apply {
        this as ProxyObject
        handler = PersistentClassMethodHandler(this, node.entityType)
    } as BasePersistentClassImpl
}

private fun findLegacyEntitySuperclass(node: XdHierarchyNode): XdLegacyEntityType<*, *>? =
        node.entityType.let { it as? XdLegacyEntityType<*, *> ?: node.parentNode?.let(::findLegacyEntitySuperclass) }

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

fun XdHierarchyNode.getAllProperties(): Sequence<XdHierarchyNode.SimpleProperty> {
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

fun getPropertyConstraints(property: XdConstrainedProperty<*, *>) =
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
