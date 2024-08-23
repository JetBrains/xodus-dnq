/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.query.metadata.*
import kotlinx.dnq.XdEnumEntityType
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.XdWrappedProperty
import kotlinx.dnq.simple.custom.type.XdCustomTypeProperty
import kotlinx.dnq.singleton.XdSingletonEntityType
import kotlinx.dnq.store.EntityLifecycleImpl
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.reflect.jvm.javaType

fun initMetaData(hierarchy: Map<String, XdHierarchyNode>, entityStore: TransientEntityStoreImpl) {
    val naturalNodes = hierarchy.filter {
        it.value.entityType is XdNaturalEntityType<*>
    }
    val modelMetaData = entityStore.modelMetaData as ModelMetaDataImpl

    naturalNodes.forEach { (entityTypeName, node) ->
        entityStore.registerCustomTypes(node)
        modelMetaData.addEntityMetaData(entityTypeName, node)
    }

    val linkValidator = LinkValidator()
    naturalNodes.forEach { (entityTypeName, node) ->
        node.linkProperties.values.forEach { sourceEnd ->
            linkValidator.oneMore(entityTypeName, sourceEnd)
            modelMetaData.addLinkMetaData(hierarchy, entityTypeName, sourceEnd, node)
        }
    }
    val deprecatedNodes = hierarchy.filter {
        it.value.entityType !is XdNaturalEntityType<*>
    }
    XdModel.plugins.flatMap { it.typeExtensions }.forEach { extension ->
        deprecatedNodes.forEach { (entityTypeName, node) ->
            node.linkProperties.values.forEach { sourceEnd ->
                if (sourceEnd.property == extension) {
                    linkValidator.oneMore(entityTypeName, sourceEnd)
                    // will add all data to sub types automatically
                    modelMetaData.addLinkMetaData(hierarchy, node.entityType.entityType, sourceEnd, node)
                }
            }
        }
    }
    linkValidator.validate()
    entityStore.entityLifecycle = EntityLifecycleImpl()

    /**
     * This explicitly prepares all data structures within model metadata. If we don't invoke
     * preparation here, then it would be performed on demand and very likely simultaneously in two
     * threads: EventMultiplexer and the one executing App.init().
     */
    modelMetaData.prepare()

    // JT-57205: we should be able to start in read-only mode
    // TODO here should be readonly check
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


private fun TransientEntityStore.registerCustomTypes(node: XdHierarchyNode) {
    node.getAllProperties()
        .map { it.delegate }
        .filterIsInstance<XdCustomTypeProperty<*>>()
        .mapNotNull { it.binding }
        .forEach { it.register(this) }
}

private fun ModelMetaDataImpl.addEntityMetaData(entityTypeName: String, node: XdHierarchyNode) {
    addEntityMetaData(EntityMetaDataImpl().apply {
        type = entityTypeName
        superType = node.parentNode?.entityType?.entityType
        isAbstract = Modifier.isAbstract(node.entityType.javaClass.enclosingClass.modifiers)
        propertiesMetaData = node.getAllProperties().map {
            val (simpleTypeName, typeParameters) = it.property.returnType.javaType.let { propertyJavaType ->
                when (propertyJavaType) {
                    is Class<*> -> propertyJavaType.simpleName to null
                    is ParameterizedType ->
                        (propertyJavaType.rawType as Class<*>).simpleName to
                                (propertyJavaType.actualTypeArguments.map { argType ->
                                    argType.typeName.substringAfterLast(
                                        "."
                                    ).lowercase()
                                })

                    else -> throw IllegalArgumentException("Cannot identify simple property type name")
                }
            }

            SimplePropertyMetaDataImpl(
                it.dbPropertyName,
                simpleTypeName,
                typeParameters
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
                ownerEntityType = entityTypeName
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
                    it.ownerEntityType = entityType.entityType
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

fun XdHierarchyNode.getAllLinks(): Sequence<XdHierarchyNode.LinkProperty> {
    return (parentNode?.getAllLinks() ?: emptySequence()) + linkProperties.values
}

private fun ModelMetaDataImpl.addLinkMetaData(
    hierarchy: Map<String, XdHierarchyNode>,
    entityTypeName: String,
    sourceEnd: XdHierarchyNode.LinkProperty,
    sourceNode: XdHierarchyNode
) {
    val oppositeEntityType = sourceEnd.delegate.oppositeEntityType

    when (sourceEnd.delegate.endType) {
        AssociationEndType.DirectedAssociationEnd -> {
            addLink(
                sourceEntityName = entityTypeName,
                targetEntityName = oppositeEntityType.entityType,
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
                targetTargetClearOnDelete = false
            )
        }

        AssociationEndType.UndirectedAssociationEnd -> {
            val targetEnd = getTargetEnd(hierarchy, sourceEnd.delegate)
            val targetEntityType = oppositeEntityType.entityType
            val sourceEntityType = sourceNode.entityType
            if (targetEnd != null && (entityTypeName < targetEntityType ||
                        (entityTypeName == targetEntityType && sourceEnd.dbPropertyName <= targetEnd.dbPropertyName) ||
                        (oppositeEntityType !is XdNaturalEntityType && sourceEntityType is XdNaturalEntityType))
            ) {
                addLink(
                    sourceEntityName = entityTypeName,
                    targetEntityName = oppositeEntityType.entityType,
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
                    targetTargetClearOnDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR
                )
            }
        }

        AssociationEndType.ParentEnd -> {
            val targetEnd = getTargetEnd(hierarchy, sourceEnd.delegate)
            if (targetEnd != null) {
                addLink(
                    sourceEntityName = entityTypeName,
                    targetEntityName = oppositeEntityType.entityType,
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
                    targetTargetClearOnDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR
                )
            }

        }

        AssociationEndType.ChildEnd -> { // only add when natural child has a legacy parent
            val targetEnd = getTargetEnd(hierarchy, sourceEnd.delegate)
            if (targetEnd != null && sourceNode.entityType is XdNaturalEntityType) {
                val oppositeType = hierarchy[oppositeEntityType.entityType]?.entityType
                if (oppositeType != null && oppositeType !is XdNaturalEntityType) {
                    addLink(
                        sourceEntityName = oppositeEntityType.entityType,
                        targetEntityName = entityTypeName,
                        type = AssociationType.Aggregation,


                        sourceName = targetEnd.dbPropertyName,
                        sourceCardinality = targetEnd.delegate.cardinality,
                        sourceCascadeDelete = targetEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                        sourceClearOnDelete = targetEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                        sourceTargetCascadeDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                        sourceTargetClearOnDelete = targetEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR,

                        targetName = sourceEnd.dbPropertyName,
                        targetCardinality = sourceEnd.delegate.cardinality,
                        targetCascadeDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CASCADE,
                        targetClearOnDelete = sourceEnd.delegate.onDelete == OnDeletePolicy.CLEAR,
                        targetTargetCascadeDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CASCADE,
                        targetTargetClearOnDelete = sourceEnd.delegate.onTargetDelete == OnDeletePolicy.CLEAR
                    )
                }
            }
        }
    }
}

fun getPropertyConstraints(property: XdConstrainedProperty<*, *>) =
    ((property as? XdWrappedProperty<*, *, *>)?.wrapped ?: property).constraints

fun ModelMetaDataImpl.addLink(
    sourceEntityName: String, targetEntityName: String, type: AssociationType,

    sourceName: String, sourceCardinality: AssociationEndCardinality,
    sourceCascadeDelete: Boolean, sourceClearOnDelete: Boolean,
    sourceTargetCascadeDelete: Boolean, sourceTargetClearOnDelete: Boolean,

    targetName: String?, targetCardinality: AssociationEndCardinality?,
    targetCascadeDelete: Boolean, targetClearOnDelete: Boolean,
    targetTargetCascadeDelete: Boolean, targetTargetClearOnDelete: Boolean
): AssociationMetaData =
    addAssociation(
        sourceEntityName, targetEntityName, type,

        sourceName, sourceCardinality,
        sourceCascadeDelete, sourceClearOnDelete,
        sourceTargetCascadeDelete, sourceTargetClearOnDelete,

        targetName, targetCardinality,
        targetCascadeDelete, targetClearOnDelete,
        targetTargetCascadeDelete, targetTargetClearOnDelete
    )

private fun getTargetEnd(
    hierarchy: Map<String, XdHierarchyNode>,
    sourceProperty: XdLink<*, *>
): XdHierarchyNode.LinkProperty? {
    return hierarchy[sourceProperty.oppositeEntityType.entityType]?.linkProperties?.values?.firstOrNull {
        it.property.name == sourceProperty.oppositeField?.name
    }
}
