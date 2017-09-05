package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.query.metadata.*
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.enum.XdEnumEntityType
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.XdWrappedProperty
import kotlinx.dnq.singleton.XdSingletonEntityType
import kotlinx.dnq.transactional
import kotlin.reflect.jvm.javaType

fun initMetaData(hierarchy: Map<String, XdHierarchyNode>, entityStore: TransientEntityStoreImpl) {
    val naturalNodes = hierarchy.filter {
        it.value.entityType is XdNaturalEntityType<*>
    }
    val modelMetaData = entityStore.modelMetaData as ModelMetaDataImpl

    naturalNodes.forEach {
        val (entityTypeName, node) = it
        modelMetaData.addEntityMetaData(entityTypeName, node)
        entityStore.setCachedPersistentClassInstance(entityTypeName, node.naturalPersistentClassInstance)
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

    entityStore.transactional { txn ->
        naturalNodes.values.asSequence().map {
            it.entityType
        }.filterIsInstance<XdEnumEntityType<*>>().forEach {
            it.initEnumValues(txn)
        }

        naturalNodes.values.asSequence().map {
            it.entityType
        }.filterIsInstance<XdNaturalEntityType<*>>().forEach {
            it.initEntityType()
        }

        naturalNodes.values.asSequence().map {
            it.entityType
        }.filterIsInstance<XdSingletonEntityType<*>>().forEach {
            it.get()
        }
    }
}

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

        val uniqueProperties = node.getAllProperties().filter {
            it.delegate.requirement == XdPropertyRequirement.UNIQUE
        }.map {
            IndexImpl().apply {
                setOwnerEntityType(entityTypeName)
                fields = listOf(IndexFieldImpl().apply {
                    name = it.dbPropertyName
                    isProperty = true
                })
            }
        }
        val compositeIndices = node.getCompositeIndices()
        ownIndexes = (uniqueProperties + compositeIndices).toSet()
    })

}

private fun XdHierarchyNode.getCompositeIndices(): Sequence<IndexImpl> {
    val entityType = this.entityType
    return when (entityType) {
        is XdNaturalEntityType<*> -> entityType.compositeIndices
                .asSequence()
                .map { index ->
                    IndexImpl().also {
                        it.setOwnerEntityType(entityType.entityType)
                        it.fields = index.map {
                            val metaProperty = resolveMetaProperty(it)
                                    ?: throw IllegalArgumentException("Cannot build composite index by property ${entityType.entityType}::${it.name}")

                            IndexFieldImpl().also {
                                it.name = metaProperty.dbPropertyName
                                it.isProperty = metaProperty is XdHierarchyNode.SimpleProperty
                            }
                        }
                    }
                }
        else -> emptySequence()
    }
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
