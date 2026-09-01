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

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import jetbrains.exodus.entitystore.youtrackdb.requireClassId
import jetbrains.exodus.entitystore.youtrackdb.requireLocalEntityId
import jetbrains.exodus.entitystore.youtrackdb.setLocalEntityId
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class YouTrackDbSchemaInitializerTest {
    @Rule
    @JvmField
    val orientDb = InMemoryYouTrackDB(initializeIssueSchema = false)

    @Test
    fun `create vertex-class for every entity`() =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1")
                entity("type2")
            }

            oSession.applySchemaInTx(model)

            oSession.assertVertexClassExists("type1")
            oSession.assertVertexClassExists("type2")
        }

    @Test
    fun `schema result records classes created by this pass`() =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1")
                entity("type2", "type1")
            }

            val first = oSession.applySchemaInTx(model)
            assertEquals(setOf("type1", "type2"), first.createdClasses)

            val second = oSession.applySchemaInTx(model)
            assertTrue(second.createdClasses.isEmpty())
        }

    @Test
    fun `set super-classes`() = orientDb.provider.withSession { oSession ->
        val model = model {
            entity("type1")
            entity("type2", "type1")
            entity("type3", "type2")
        }

        oSession.applySchemaInTx(model)

        oSession.assertHasSuperClass("type2", "type1")
        oSession.assertHasSuperClass("type3", "type2")
    }

    @Test
    fun `simple properties of known types are created`() =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    for (type in supportedSimplePropertyTypes) {
                        property("prop$type", type)
                        property("requiredProp$type", type, required = true)
                    }
                }
            }

            oSession.applySchemaInTx(model)

            val oClass = oSession.schema.getClass("type1")!!
            for (type in supportedSimplePropertyTypes) {
                val requiredProp = oClass.getProperty("requiredProp$type")!!
                val prop = oClass.getProperty("prop$type")!!

                assertEquals(getOType(type), requiredProp.type)
                assertEquals(getOType(type), prop.type)

                requiredProp.check(required = true, notNull = true)
                prop.check(required = false, notNull = false)
            }
        }

    @Test
    fun `simple properties of not-known types cause exception`(): Unit =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    property("prop1", "notSupportedType")
                }
            }

            assertFailsWith<IllegalArgumentException>() {
                oSession.applySchemaInTx(model)
            }
        }

    @Test
    fun `SchemaBuddy impl can correctly initialize StringBlob and Blob`() {
        val model = model {
            entity("type1") {
                blobProperty("blob1")
                stringBlobProperty("strBlob1")
            }
        }
        orientDb.withSession {
            it.applySchemaInTx(model)
        }
        orientDb.withSession {
            orientDb.schemaBuddy.initialize(it)
        }

        orientDb.withSession { session ->
            val type = session.schema.getClass("type1")!!
            val prop1 = type.getProperty("blob1")
            val prop2 = type.getProperty("strBlob1")
            assertEquals(PropertyType.LINK, prop1.type)
            assertEquals(PropertyType.LINK, prop2.type)
        }
    }

    @Test
    fun `embedded set properties with supported types`() {
        val indices = orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    for (type in supportedSimplePropertyTypes) {
                        setProperty("setProp$type", type)
                    }
                }
            }

            val (indices, _) = oSession.applySchemaInTx(model)

            val oClass = oSession.schema.getClass("type1")!!
            for (type in supportedSimplePropertyTypes) {
                val prop = oClass.getProperty("setProp$type")!!
                assertEquals(PropertyType.EMBEDDEDSET, prop.type)
                assertEquals(getOType(type), prop.linkedType)

                indices.checkIndex("type1", unique = false, "setProp$type")
            }
            indices
        }

        orientDb.provider.withSession { oSession ->
            oSession.applyIndicesInTx(indices)

            for (type in supportedSimplePropertyTypes) {
                oSession.checkIndex("type1", unique = false, "setProp$type")
            }
        }
    }

    @Test
    fun `embedded set properties with not-supported types cause exception`(): Unit =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    setProperty("setProp$type", "cavaBanga")
                }
            }

            assertFailsWith<IllegalArgumentException> {
                oSession.applySchemaInTx(model)
            }
        }

    @Test
    fun `one-directional associations`(): Unit =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1")
                entity("type2")
                for (cardinality in AssociationEndCardinality.entries) {
                    association("type2", "prop1$cardinality", "type1", cardinality)
                }
            }

            val result = oSession.applySchemaInTx(model)
            oSession.initializeIndicesInTx(result)

            for (cardinality in AssociationEndCardinality.entries) {
                oSession.assertAssociationExists("type2", "type1", "prop1$cardinality", cardinality)
            }
        }

    @Test
    fun `two association with the same name to a single type`(): Unit =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1")
                entity("type2")
                entity("type3")
                association("type2", "link1", "type1", AssociationEndCardinality._0_n)
                association("type3", "link1", "type1", AssociationEndCardinality._0_n)
            }

            val result = oSession.applySchemaInTx(model)
            oSession.initializeIndicesInTx(result)

            oSession.assertAssociationExists(
                "type2",
                "type1",
                "link1",
                AssociationEndCardinality._0_n
            )
            oSession.assertAssociationExists(
                "type3",
                "type1",
                "link1",
                AssociationEndCardinality._0_n
            )
        }

    @Test
    fun `one-directional associations ignore cardinality`(): Unit =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1")
                entity("type2")
                for (cardinality in AssociationEndCardinality.entries) {
                    association("type2", "prop1$cardinality", "type1", cardinality)
                }
            }

            val result = oSession.applySchemaInTx(model, applyLinkCardinality = false)
            oSession.initializeIndicesInTx(result)

            for (cardinality in AssociationEndCardinality.entries) {
                oSession.assertAssociationExists("type2", "type1", "prop1$cardinality", null)
            }
        }

    @Test
    fun `two-directional associations`(): Unit =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1")
                entity("type2")

                for (cardinality1 in AssociationEndCardinality.entries) {
                    for (cardinality2 in AssociationEndCardinality.entries) {
                        twoDirectionalAssociation(
                            sourceEntity = "type1",
                            sourceName = "prop1${cardinality1}_${cardinality2}",
                            sourceCardinality = cardinality1,
                            targetEntity = "type2",
                            targetName = "prop2${cardinality2}_${cardinality1}",
                            targetCardinality = cardinality2
                        )
                    }
                }
            }

            val result = oSession.applySchemaInTx(model)
            oSession.initializeIndicesInTx(result)

            for (cardinality1 in AssociationEndCardinality.entries) {
                for (cardinality2 in AssociationEndCardinality.entries) {
                    oSession.assertAssociationExists(
                        "type1",
                        "type2",
                        "prop1${cardinality1}_${cardinality2}",
                        cardinality1
                    )
                    oSession.assertAssociationExists(
                        "type2",
                        "type1",
                        "prop2${cardinality2}_${cardinality1}",
                        cardinality2
                    )
                }
            }
        }


    @Test
    fun `own indices`() {
        val indices = orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    property("prop1", "int")
                    property("prop2", "long")
                    property("prop3", "string")
                    property("prop4", "string")

                    index("prop1", "prop2")
                    index("prop3")
                }
            }

            val (indices, _) = oSession.applySchemaInTx(model)

            indices.checkIndex("type1", unique = true, "prop1", "prop2")
            indices.checkIndex("type1", unique = true, "prop3")

            val entity = oSession.schema.getClass("type1")!!
            // indices are not created right away, they are created after data migration
            assertTrue((entity as SchemaClassInternal).indexes.isEmpty())

            // indexed properties in Xodus are required and not-nullable
            entity.getProperty("prop1").check(required = true, notNull = true)
            entity.getProperty("prop2").check(required = true, notNull = true)
            entity.getProperty("prop3").check(required = true, notNull = true)
            entity.getProperty("prop4").check(required = false, notNull = false)

            indices
        }

        orientDb.provider.withSession { oSession ->
            oSession.applyIndicesInTx(indices)

            oSession.checkIndex("type1", true, "prop1", "prop2")
            oSession.checkIndex("type1", true, "prop3")
        }
    }

    @Test
    fun `unique index forbids to create vertices with the same property value`() {
        val model = model {
            entity("type1") {
                property("prop1", "int")
                property("prop2", "long")
                index("prop1")
            }
        }

        orientDb.withSession { oSession ->
            val (indices, _) = oSession.applySchemaInTx(model, indexForEverySimpleProperty = false)
            oSession.applyIndicesInTx(indices)
        }

        assertFailsWith<RecordDuplicatedException> {
            orientDb.withStoreTx { tx ->
                val oClass = tx.activeYtdbSession().schema.getClass("type1")!!
                val v1 = tx.newVertex(oClass.name)
                tx.generateEntityId("type1", v1)
                v1.requireLocalEntityId()
                v1.property("prop1", 3)
                v1.property("prop2", 4)

                val v2 = tx.newVertex(oClass.name)
                tx.generateEntityId("type1", v2)
                v2.property("prop1", 3L)
                v2.property("prop2", 4L)
            }
        }
    }

    @Test
    fun `in-tx index creation over a populated class fails at commit until YTDB-1064 is lifted`() {
        // XD-1283 transactional index contract: direct in-tx creation retains the raw engine path
        val model = model {
            entity("type1") {
                property("prop1", "int")
                index("prop1")
            }
        }

        // apply the schema (pure DDL, no indices yet), then populate the class
        val indices = orientDb.withSession { oSession ->
            oSession.applySchemaInTx(model).indices
        }
        orientDb.withStoreTx { tx ->
            val v = createVertexAndSetLocalEntityId(tx, "type1")
            v.property("prop1", 1)
        }

        // In-transaction index creation over pre-existing committed rows is rejected
        // at commit time (accepted behavior of the in-tx mode until YTDB-1064 is lifted).
        // withTx must surface the original commit exception instead of masking it with a
        // secondary rollback failure.
        val e = assertFailsWith<RuntimeException> {
            orientDb.withSession { oSession ->
                oSession.applyIndicesInTx(indices)
            }
        }
        assertTrue(e.message!!.contains("YTDB-1064"))
    }

    @Test
    fun `non-tx index creation over a populated class succeeds`() {
        // XD-1283 legacy non-transactional contract, used by the preflight for populated owners:
        // the path registers the index and fills it from committed rows, so populated classes are
        // supported.
        val model = model {
            entity("type1") {
                property("prop1", "int")
                index("prop1")
            }
        }

        val indices = orientDb.withSession { oSession ->
            oSession.applySchemaInTx(model).indices
        }
        orientDb.withStoreTx { tx ->
            val v = createVertexAndSetLocalEntityId(tx, "type1")
            v.property("prop1", 1)
        }

        orientDb.withSession { oSession ->
            oSession.applyIndicesNonTx(indices)
        }

        orientDb.withSession { oSession ->
            oSession.checkIndex("type1", true, "prop1")
        }
    }

    @Test
    fun `non-tx index creation over duplicate data drops the poisoned index and fails loudly`() {
        // XD-1283 / AD-E1+AD-E2: on the legacy non-tx path the index is REGISTERED before it
        // is filled, so a fillIndex failure (a genuine duplicate under a unique index) would
        // leave a registered-but-empty index behind; the indexExists pre-check would then
        // silently skip the broken index on the next run. The non-tx path must drop the
        // poisoned index and rethrow - and a re-run must fail loudly again.
        val model = model {
            entity("type1") {
                property("prop1", "int")
                index("prop1")
            }
        }

        val indices = orientDb.withSession { oSession ->
            oSession.applySchemaInTx(model).indices
        }
        // two vertices with the same value in the unique-indexed property
        orientDb.withStoreTx { tx ->
            repeat(2) {
                val v = createVertexAndSetLocalEntityId(tx, "type1")
                v.property("prop1", 42)
            }
        }

        val uniqueIndexName = "type1_prop1_unique"

        assertFailsWith<RuntimeException> {
            orientDb.withSession { oSession ->
                oSession.applyIndicesNonTx(indices)
            }
        }
        // the poisoned (registered-but-empty) index was dropped, not left behind
        orientDb.withSession { oSession ->
            assertFalse(oSession.schema.indexExists(uniqueIndexName))
        }

        // a re-run fails loudly again instead of silently skipping a broken index
        assertFailsWith<RuntimeException> {
            orientDb.withSession { oSession ->
                oSession.applyIndicesNonTx(indices)
            }
        }
        orientDb.withSession { oSession ->
            assertFalse(oSession.schema.indexExists(uniqueIndexName))
        }
    }

    @Test
    fun `both index-creation entry points pin their transaction preconditions`() {
        // XD-1283 dual-mode: applyIndices must run inside an active transaction (outside one
        // it would silently degrade to the unguarded legacy path without drop protection);
        // applyIndicesNonTx must run with NO active transaction (with one, dropIndex would
        // only stage a tx-overlay drop and the legacy fill semantics would not apply).
        val model = model {
            entity("type1") {
                property("prop1", "int")
                index("prop1")
            }
        }
        val indices = orientDb.withSession { oSession ->
            oSession.applySchemaInTx(model).indices
        }

        orientDb.withSession { oSession ->
            // in-tx entry point without an active transaction
            assertFailsWith<IllegalStateException> {
                oSession.applyIndices(indices)
            }

            // non-tx entry point with an active transaction
            oSession.begin()
            try {
                assertFailsWith<IllegalStateException> {
                    oSession.applyIndicesNonTx(indices)
                }
            } finally {
                oSession.rollback()
            }
        }
    }

    @Test
    fun `in-tx dropProperty is forbidden by YTDB - canary for the onRemoveAssociation exception`() {
        /*
         * XD-1283 canary for the onRemoveAssociation exception: association removal
         * (onRemoveAssociation/removeAssociation) stays on the legacy non-tx path SOLELY
         * because YTDB forbids dropProperty under an active transaction
         * (SchemaClassEmbedded.dropProperty; see the YTDBModelMetaData.onRemoveAssociation
         * comment). When a YTDB upgrade makes THIS test fail, in-tx dropProperty has
         * arrived: lift the exception (transactionalize onRemoveAssociation) and remove
         * this test.
         */
        val model = model {
            entity("type1") {
                property("prop1", "int")
            }
        }
        orientDb.withSession { oSession ->
            oSession.applySchemaInTx(model)
        }

        orientDb.withSession { oSession ->
            oSession.begin()
            try {
                val oClass = oSession.schema.getClass("type1")!!
                val e = assertFailsWith<IllegalStateException> {
                    oClass.dropProperty("prop1")
                }
                assertTrue(e.message!!.contains("Cannot drop a property inside a transaction"))
            } finally {
                oSession.rollback()
            }
        }

        // the schema is intact afterwards - the rejected drop left no trace
        orientDb.withSession { oSession ->
            assertTrue(oSession.schema.getClass("type1")!!.existsProperty("prop1"))
        }
    }

    @Test
    fun `rolled-back transaction discards its schema changes`() {
        // XD-1283: schema manipulation is transactional - schema reads become tx-aware after
        // the first schema write in the transaction, and a rollback discards the tx-local
        // schema copy without leaving any trace
        val model = model {
            entity("type1") {
                property("prop1", "int")
            }
        }

        orientDb.withSession { oSession ->
            oSession.begin()
            oSession.applySchema(model)

            // the uncommitted class is visible inside the transaction
            assertNotNull(oSession.schema.getClass("type1"))

            oSession.rollback()

            // the rollback discarded the tx-local schema copy
            assertNull(oSession.schema.getClass("type1"))
        }

        // no other session ever sees the discarded class
        orientDb.withSession { oSession ->
            assertNull(oSession.schema.getClass("type1"))
        }
    }

    @Test
    fun `index for every simple property if required`() =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    property("prop1", "int")
                    property("prop2", "long")
                    property("prop3", "string")
                    property("prop4", "string")
                }
            }

            val (indices, _) = oSession.applySchemaInTx(model, indexForEverySimpleProperty = true)

            indices.checkIndex("type1", unique = false, "prop1")
            indices.checkIndex("type1", unique = false, "prop2")
            indices.checkIndex("type1", unique = false, "prop3")
            indices.checkIndex("type1", unique = false, "prop4")

            val entity = oSession.schema.getClass("type1")!!
            // indices are not created right away, they are created after data migration
            assertTrue((entity as SchemaClassInternal).indexes.isEmpty())
        }

    @Test
    fun `no indices for simple properties by default`() =
        orientDb.provider.withSession { oSession ->
            val model = model {
                entity("type1") {
                    property("prop1", "int")
                    property("prop2", "long")
                    property("prop3", "string")
                    property("prop4", "string")
                }
            }

            val (indices, _) = oSession.applySchemaInTx(model)
            assertTrue(indices.none { (indexName, _) -> indexName.contains("prop") })
        }

    @Test
    fun `addAssociation, removeAssociation`(): Unit =
        orientDb.provider.withSession { session ->
            val model = model {
                entity("type1")
                entity("type2")
            }

            val result = session.applySchemaInTx(model)
            session.initializeIndicesInTx(result)

            for (cardinality in AssociationEndCardinality.entries) {
                val assResult = session.withTx {
                    it.addAssociation(
                        LinkMetadata(
                            name = "ass1${cardinality.name}",
                            outClassName = "type1",
                            inClassName = "type2",
                            cardinality = cardinality
                        ),
                        listOf()
                    )
                }
                session.initializeIndicesInTx(assResult)
            }

            for (cardinality in AssociationEndCardinality.entries) {
                session.assertAssociationExists(
                    "type1",
                    "type2",
                    "ass1${cardinality.name}",
                    cardinality
                )
            }

            for (cardinality in AssociationEndCardinality.entries) {
                // removeAssociation cannot run inside a transaction: it drops properties,
                // and YTDB currently forbids dropProperty inside a transaction
                // ("Cannot drop a property inside a transaction")
                session.removeAssociation(
                    sourceClassName = "type1",
                    targetClassName = "type2",
                    associationName = "ass1${cardinality.name}"
                )
            }

            for (cardinality in AssociationEndCardinality.entries) {
                /*
                * We do not delete the edge class when deleting an association because
                * it (the edge class) may be used by other associations.
                *
                * Maybe it is possible to check an edge class if it is not used anywhere, but
                * we do not do it at the moment. Maybe some day in the future.
                * */
                session.assertAssociationNotExist(
                    "type1",
                    "type2",
                    "ass1${cardinality.name}",
                    requireEdgeClass = true
                )
            }
        }


    // Backward compatible EntityId

    @Test
    fun `classId is a monotonically increasing long`(): Unit =
        orientDb.provider.withSession { oSession ->
            val types = mutableListOf("type0", "type1", "type2")
            val model = model {
                for (type in types) {
                    entity(type)
                }
            }

            oSession.applySchemaInTx(model)

            val classIds = mutableSetOf<Int>()
            val classIdToClassName = mutableMapOf<Int, String>()
            for (type in types) {
                val classId = oSession.schema.getClass(type).requireClassId()
                classIdToClassName[classId] = type
                classIds.add(classId)
            }
            assertEquals(setOf(0, 1, 2), classIds)


            // emulate the next run of the application with new classes in the codebase
            types.add("type4")
            types.add("type5")
            val anotherModel = model {
                for (type in types) {
                    entity(type)
                }
            }

            oSession.applySchemaInTx(anotherModel)

            classIds.clear()
            for (type in types) {
                val classId = oSession.schema.getClass(type).requireClassId()
                // classId is not changed if it has been already assigned
                if (classId in classIdToClassName) {
                    assertEquals(classIdToClassName.getValue(classId), type)
                }
                classIds.add(classId)
            }
            assertEquals(setOf(0, 1, 2, 3, 4), classIds)
        }


    @Test
    fun `search for boolean == false works by default`() {
        val model = oModel(orientDb.provider) {
            entity("type1") {
                property("prop1", "boolean")
            }
        }
        model.prepare()
        orientDb.withStoreTx { tx ->
            tx.newVertex("type1").apply {
                property("prop1", true)
                tx.generateEntityId("type1", this)
            }
            tx.newVertex("type1").apply {
                tx.generateEntityId("type1", this)
            }

        }
        orientDb.withTxSession { oSession ->
            val tx = oSession.activeTransaction
            val updated =
                tx.query("SELECT from type1 where prop1 = true").vertexStream().toList()
            val default =
                tx.query("SELECT from type1 where prop1 = false").vertexStream().toList()
            val all = tx.query("SELECT from type1").vertexStream().toList()
            assertEquals(1, updated.size)
            assertEquals(1, default.size)
            assertEquals(2, all.size)
        }
    }

    @Test
    fun `every class gets localEntityId property`(): Unit =
        orientDb.provider.withSession { oSession ->
            val types = mutableListOf("type0", "type1", "type2")
            val model = model {
                for (type in types) {
                    entity(type)
                }
            }

            val (indices, _) = oSession.applySchemaInTx(model)

            val sequences = oSession.metadata.sequenceLibrary
            for (type in types) {
                assertNotNull(oSession.getClass(type).getProperty(LOCAL_ENTITY_ID_PROPERTY_NAME))
                // index for the localEntityId must be created regardless the indexForEverySimpleProperty param
                indices.checkIndex(type, false, LOCAL_ENTITY_ID_PROPERTY_NAME)
                // the index for localEntityId must not be unique, otherwise it will not let the same localEntityId
                // for subtypes of a supertype
                assertTrue(indices.getValue(type).none { it.unique })

                val sequence = sequences.getSequence(localEntityIdSequenceName(type))
                assertNotNull(sequence)
                assertEquals(0, sequence.next(oSession))
            }

            // emulate the next run of the application
            oSession.applySchemaInTx(model)

            for (type in types) {
                val sequence = sequences.getSequence(localEntityIdSequenceName(type))
                // sequences are the same
                assertEquals(1, sequence.next(oSession))
            }
        }

    private fun SchemaProperty.check(required: Boolean, notNull: Boolean) {
        assertEquals(required, isMandatory)
        assertEquals(notNull, isNotNull)
    }

    private val supportedSimplePropertyTypes: List<String> = listOf(
        "boolean",
        "string",
        "byte", "short", "int", "integer", "long",
        "float", "double",
        "datetime",
    )

    private fun getOType(jvmTypeName: String): PropertyType {
        return when (jvmTypeName.lowercase()) {
            "boolean" -> PropertyType.BOOLEAN
            "string" -> PropertyType.STRING

            "byte" -> PropertyType.BYTE
            "short" -> PropertyType.SHORT
            "int",
            "integer" -> PropertyType.INTEGER

            "long" -> PropertyType.LONG

            "float" -> PropertyType.FLOAT
            "double" -> PropertyType.DOUBLE

            "datetime" -> PropertyType.LONG

            else -> throw IllegalArgumentException("$jvmTypeName is not supported. Feel free to support it.")
        }
    }
}
