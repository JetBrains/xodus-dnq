/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
package jetbrains.exodus.query.metadata

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty
import jetbrains.exodus.entitystore.youtrackdb.*
import org.junit.Assert.*

// assertions

internal fun DatabaseSessionEmbedded.assertAssociationNotExist(
    outClassName: String,
    inClassName: String,
    edgeName: String,
    requireEdgeClass: Boolean = false
) {
    val edgeClassName = edgeName.asEdgeClass
    if (requireEdgeClass) {
        val edgeClass = requireEdgeClass(edgeClassName)
        assertTrue(
            (edgeClass as SchemaClassInternal).areIndexed(
                this as DatabaseSessionEmbedded,
                Edge.DIRECTION_IN,
                Edge.DIRECTION_OUT
            )
        )
    }

    val inClass = schema.getClass(inClassName)!!
    val outClass = schema.getClass(outClassName)!!

    val outPropName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClassName)
    assertNull(outClass.getProperty(outPropName))

    val inPropName = Vertex.getEdgeLinkFieldName(Direction.IN, edgeClassName)
    assertNull(inClass.getProperty(inPropName))
}

internal fun DatabaseSessionEmbedded.assertAssociationExists(
    outClassName: String,
    inClassName: String,
    edgeName: String,
    cardinality: AssociationEndCardinality?,
) {
    val edgeClassName = edgeName.asEdgeClass
    val edgeClass = schema.getClass(edgeClassName)
    val inClass = schema.getClass(inClassName)!!
    val outClass = schema.getClass(outClassName)!!

    assertTrue(
        (edgeClass as SchemaClassInternal).areIndexed(
            (this as DatabaseSessionEmbedded),
            Edge.DIRECTION_IN,
            Edge.DIRECTION_OUT
        )
    )

    if (cardinality != null) {
        val outPropName = Vertex.getEdgeLinkFieldName(Direction.OUT, edgeClassName)
        val directOutProp = outClass.getProperty(outPropName)!!
        assertEquals(com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.LINKBAG, directOutProp.type)
        directOutProp.assertCardinality(cardinality)

        val inPropName = Vertex.getEdgeLinkFieldName(Direction.IN, edgeClassName)
        val directInProp = inClass.getProperty(inPropName)!!
        assertEquals(com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.LINKBAG, directInProp.type)
    }
}

private fun SchemaProperty.assertCardinality(cardinality: AssociationEndCardinality) {
    when (cardinality) {
        AssociationEndCardinality._0_1 -> {
            assertTrue(!this.isMandatory)
            assertTrue(this.min == "0")
            assertTrue(this.max == "1")
        }

        AssociationEndCardinality._1 -> {
            assertTrue(this.isMandatory)
            assertTrue(this.min == "1")
            assertTrue(this.max == "1")
        }

        AssociationEndCardinality._0_n -> {
            assertTrue(!this.isMandatory)
            assertTrue(this.min == "0")
            assertTrue(this.max == null)
        }

        AssociationEndCardinality._1_n -> {
            assertTrue(this.isMandatory)
            assertTrue(this.min == "1")
            assertTrue(this.max == null)
        }
    }
}

internal fun DatabaseSessionEmbedded.assertVertexClassExists(name: String) {
    assertHasSuperClass(name, "V")
}

internal fun DatabaseSessionEmbedded.requireEdgeClass(name: String): SchemaClass {
    val edge = schema.getClass(name)!!
    assertTrue(edge.superClassesNames.contains("E"))
    return edge
}

internal fun DatabaseSessionEmbedded.assertHasSuperClass(className: String, superClassName: String) {
    assertTrue(schema.getClass(className)!!.superClassesNames.contains(superClassName))
}

internal fun DatabaseSessionEmbedded.checkIndex(
    className: String,
    unique: Boolean,
    vararg fieldNames: String
) {
    val entity = schema.getClass(className)!!
    val indexName = indexName(className, unique, *fieldNames)
    val index = (entity as SchemaClassInternal).indexesInternal.first { it.name == indexName }
    assertEquals(unique, index.isUnique)

    assertEquals(fieldNames.size, index.definition.properties.size)
    for (fieldName in fieldNames) {
        assertTrue(index.definition.properties.contains(fieldName))
    }
}

internal fun Map<String, Set<DeferredIndex>>.checkIndex(
    entityName: String,
    unique: Boolean,
    vararg fieldNames: String
) {
    val indexName = indexName(entityName, unique, *fieldNames)
    val indices = getValue(entityName)
    val index = indices.first { it.indexName == indexName }

    assertEquals(unique, index.unique)
    assertEquals(entityName, index.ownerVertexName)
    assertEquals(fieldNames.size, index.properties.size)

    for (fieldName in fieldNames) {
        assertTrue(index.properties.any { it == fieldName })
    }
}

internal fun indexName(entityName: String, unique: Boolean, vararg fieldNames: String): String =
    "${entityName}_${fieldNames.joinToString("_")}${if (unique) "_unique" else ""}"


// Model

internal fun model(initialize: ModelMetaDataImpl.() -> Unit): ModelMetaDataImpl {
    val model = ModelMetaDataImpl()
    model.initialize()
    return model
}

internal fun oModel(
    databaseProvider: YTDBDatabaseProvider,
    schemaBuddy: YTDBSchemaBuddy = YTDBSchemaBuddyImpl(databaseProvider, autoInitialize = false),
    buildModel: ModelMetaDataImpl.() -> Unit
): YTDBModelMetaData {
    val model = YTDBModelMetaData(databaseProvider, schemaBuddy)
    model.buildModel()
    return model
}

fun ModelMetaDataImpl.entity(
    type: String,
    superType: String? = null,
    init: EntityMetaDataImpl.() -> Unit = {}
) {
    val entity = EntityMetaDataImpl()
    entity.type = type
    entity.superType = superType
    addEntityMetaData(entity)
    entity.init()
}

fun EntityMetaDataImpl.index(vararg fieldNames: String) {
    index(*fieldNames.map { IndexedField(it, true) }.toTypedArray())
}

data class IndexedField(val name: String, val isProperty: Boolean)

fun EntityMetaDataImpl.index(vararg fields: IndexedField) {
    val index = IndexImpl()
    index.fields = fields.map { (fieldName, isProperty) ->
        val field = IndexFieldImpl()
        field.isProperty = isProperty
        field.name = fieldName
        field
    }
    index.ownerEntityType = this.type
    this.ownIndexes = this.ownIndexes + setOf(index)
}

fun EntityMetaDataImpl.property(
    name: String,
    typeName: String,
    required: Boolean = false
) {
    // regardless of the name, this setter actually ADDS new properties to its internal collection
    this.propertiesMetaData = listOf(SimplePropertyMetaDataImpl(name, typeName))
    if (required) {
        requiredProperties = requiredProperties + setOf(name)
    }
}

internal fun EntityMetaDataImpl.blobProperty(name: String) {
    this.propertiesMetaData = listOf(PropertyMetaDataImpl(name, PropertyType.BLOB))
}

internal fun EntityMetaDataImpl.stringBlobProperty(name: String) {
    this.propertiesMetaData = listOf(PropertyMetaDataImpl(name, PropertyType.TEXT))
}

internal fun EntityMetaDataImpl.setProperty(name: String, dataType: String) {
    this.propertiesMetaData = listOf(SimplePropertyMetaDataImpl(name, "Set", listOf(dataType)))
}

fun ModelMetaData.association(
    sourceEntity: String,
    associationName: String,
    targetEntity: String,
    cardinality: AssociationEndCardinality
) {
    addAssociation(
        sourceEntity,
        targetEntity,
        AssociationType.Directed, // ingored
        associationName,
        cardinality,
        false, false, false, false, // ignored
        null, null, false, false, false, false
    )
}

internal fun ModelMetaData.twoDirectionalAssociation(
    sourceEntity: String,
    sourceName: String,
    sourceCardinality: AssociationEndCardinality,
    targetEntity: String,
    targetName: String,
    targetCardinality: AssociationEndCardinality,
) {
    addAssociation(
        sourceEntity,
        targetEntity,
        AssociationType.Undirected, // two-directional
        sourceName,
        sourceCardinality,
        false, false, false, false, // ignored
        targetName,
        targetCardinality,
        false, false, false, false // ignored
    )
}

internal fun createVertexAndSetLocalEntityId(tx: YTDBStoreTransaction, className: String): YTDBVertex =
    tx
        .newVertex(className)
        .apply { tx.generateEntityId(className, this) }

internal fun YTDBVertex.addSimpleEdge(linkName: String, target: YTDBVertex) {
    val edgeClassName = YTDBVertexEntity.edgeClassName(linkName)
    addEdge( edgeClassName, target)
}

internal fun YTDBVertex.addIndexedEdge(linkName: String, target: YTDBVertex) {
    val bag = raw().getTargetLocalEntityIds(linkName)
    addEdge(YTDBVertexEntity.edgeClassName(linkName), target)
    bag.add(target.id())
    raw().setTargetLocalEntityIds(linkName, bag)
}

internal fun YTDBVertex.deleteIndexedEdge(linkName: String, target: YTDBVertex) {
    val bag = raw().getTargetLocalEntityIds(linkName)

    for (e in edges(org.apache.tinkerpop.gremlin.structure.Direction.OUT, YTDBVertexEntity.edgeClassName(linkName))) {
        val edge = e as YTDBEdge
        if (e.outVertex().id() == target.id()) {
            e.remove()
        }
    }
    bag.remove(target.id())
    raw().setTargetLocalEntityIds(linkName, bag)
}
