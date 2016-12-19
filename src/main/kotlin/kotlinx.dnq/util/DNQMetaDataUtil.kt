package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.entitystore.metadata.*
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlin.reflect.jvm.javaType

object CommonBasePersistentClass : BasePersistentClassImpl() {
    override fun run() = Unit
}

fun initMetaData(hierarchy: Map<String, XdHierarchyNode>, entityStore: TransientEntityStoreImpl) {
    val naturalNodes = hierarchy.filter {
        it.value.entityType is XdNaturalEntityType<*>
    }
    val modelMetaData = entityStore.modelMetaData as ModelMetaDataImpl

    naturalNodes.forEach {
        val (entityTypeName, node) = it
        modelMetaData.addEntityMetaData(entityTypeName, node)
        entityStore.setCachedPersistentClassInstance(
                entityTypeName,
                (node.entityType as XdNaturalEntityType).persistentClassInstance
        )
    }

    naturalNodes.forEach {
        val (entityTypeName, node) = it
        node.linkProperties.values.forEach { sourceEnd ->
            modelMetaData.addLinkMetaData(hierarchy, entityTypeName, sourceEnd)
        }
    }
}

private fun ModelMetaDataImpl.addEntityMetaData(entityTypeName: String, node: XdHierarchyNode) {
    addEntityMetaData(EntityMetaDataImpl().apply {
        this.type = entityTypeName
        this.superType = node.parentNode?.entityType?.entityType

        this.propertiesMetaData = node.simpleProperties.values.map {
            SimplePropertyMetaDataImpl(
                    it.dbPropertyName,
                    (it.property.returnType.javaType as Class<*>).simpleName).apply {
                this.type = it.delegate.propertyType
            }
        }

        requiredProperties = node.simpleProperties.values.asSequence().filter {
            it.delegate.requirement == XdPropertyRequirement.REQUIRED
        }.map {
            it.dbPropertyName
        }.toSet()

        setRequiredIfProperties(node.simpleProperties.values.asSequence().filter {
            getPropertyConstraints(it.delegate).any { it is RequireIfConstraint<*, *> }
        }.map {
            it.dbPropertyName
        }.toSet())

        // TODO: Support composite indexes
        ownIndexes = node.simpleProperties.values.asSequence().filter {
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
