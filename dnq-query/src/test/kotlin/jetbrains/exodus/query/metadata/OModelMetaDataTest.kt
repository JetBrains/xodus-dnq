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
import jetbrains.exodus.entitystore.PersistentEntityId

import jetbrains.exodus.entitystore.youtrackdb.*
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.linkTargetEntityIdPropertyName
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OModelMetaDataTest : OTestMixin {
    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = orientDbRule

    @Test
    fun `prepare() applies the schema to OrientDB`() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }

        youTrackDb.withSession { session ->
            Assert.assertNull(session.schema.getClass("type1"))
            Assert.assertNull(session.schema.getClass("type2"))
        }

        model.prepare()

        youTrackDb.withSession { session ->
            session.assertVertexClassExists("type1")
            session.assertVertexClassExists("type2")
        }
    }

    @Test
    fun `prepare() works end-to-end on a fresh empty database`() {
        // XD-1283 / AD1: the classId and per-class localEntityId sequences are created on
        // independent, immediately-committed side sessions and must be usable (sequence.next()
        // self-hoists to a pooled session) while the big schema transaction is still open
        val model = oModel(youTrackDb.provider) {
            entity("type1") {
                property("prop1", "int")
                property("prop2", "string")
                index("prop1")
            }
            entity("type2", "type1")
            association("type2", "ass1", "type1", AssociationEndCardinality._0_n)
        }

        model.prepare()

        youTrackDb.withSession { session ->
            session.assertVertexClassExists("type1")
            session.assertHasSuperClass("type2", "type1")
            session.assertAssociationExists("type2", "type1", "ass1", AssociationEndCardinality._0_n)
            session.checkIndex("type1", true, "prop1")
        }

        // data operations work on top of the prepared schema:
        // classIds are assigned, localEntityId sequences are committed and usable
        val store = YTDBPersistentEntityStore(youTrackDb.provider, youTrackDb.dbName, model)
        val id = store.computeInTransaction { tx ->
            val e = tx.newEntity("type2")
            e.setProperty("prop1", 42)
            e.id
        }
        assertTrue(id.typeId >= 0)
        store.executeInTransaction { tx ->
            assertEquals(42, tx.getEntity(id).getProperty("prop1"))
        }
    }

    @Test
    fun `addAssociation() implicitly call prepare() and applies the schema to OrientDB`() {
        oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
            association("type2", "ass1", "type1", AssociationEndCardinality._1)
        }

        youTrackDb.withSession { session ->
            session.assertVertexClassExists("type1")
            session.assertVertexClassExists("type2")
            session.assertAssociationExists("type2", "type1", "ass1", AssociationEndCardinality._1)
        }
    }

    @Test
    fun addAssociation() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }

        model.prepare()

        model.addAssociation(
            "type2", "type1", AssociationType.Directed, "ass1", AssociationEndCardinality._1,
            false, false, false, false, null,
            null, false, false, false, false
        )

        youTrackDb.withSession { session ->
            session.assertAssociationExists("type2", "type1", "ass1", AssociationEndCardinality._1)
        }
    }

    @Test
    fun `if there is an active session on the current thread, the model uses it`() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }

        youTrackDb.provider.withSession {
            model.prepare()
            model.addAssociation(
                "type2", "type1", AssociationType.Directed, "ass1", AssociationEndCardinality._1,
                false, false, false, false, null,
                null, false, false, false, false
            )
            model.removeAssociation("type2", "ass1")
        }
    }

    @Test
    fun `if there is an active transaction, model is created in separate session`() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }

        youTrackDb.withSession { session ->
            val tx = session.begin()
            model.prepare()
            model.addAssociation(
                "type2", "type1", AssociationType.Directed, "ass1", AssociationEndCardinality._1,
                false, false, false, false, null,
                null, false, false, false, false
            )
            model.removeAssociation("type2", "ass1")
            tx.commit()
        }
    }

    @Test
    fun removeAssociation() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
            association("type2", "ass1", "type1", AssociationEndCardinality._1)
        }

        model.removeAssociation("type2", "ass1")
        youTrackDb.withSession { session ->
            session.assertAssociationNotExist("type2", "type1", "ass1", requireEdgeClass = true)
        }
    }

    @Test
    fun `prepare() creates indices`() {
        val model = oModel(youTrackDb.provider) {
            entity("type1") {
                property("prop1", "int")
                property("prop2", "long")

                index("prop1", "prop2")
            }
        }

        model.prepare()

        youTrackDb.withSession { session ->
            session.checkIndex("type1", true, "prop1", "prop2")
        }
    }

    @Test
    fun `prepare() initializes the classId map`() {
        val model =
            oModel(youTrackDb.provider, YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)) {
                entity("type1")
            }

        // We have not yet called prepare() for the model, autoInitialize is disabled
        youTrackDb.provider.withSession {
            it.createVertexClassWithClassId("type1")
        }
        // Bootstrap the type1 schema (localEntityId property, sequences, index) with a
        // throwaway model - not the model under test, which must stay unprepared here.
        // This prepare() runs transactionally while the class is empty: it succeeds
        // because the schema, including the index, is applied here BEFORE the row below is
        // created, and the later prepare() of the model under test re-applies the same schema,
        // adding no new index over the now-populated class.
        oModel(youTrackDb.provider, YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)) {
            entity("type1")
        }.prepare()
        val entityId = youTrackDb.withStoreTx { tx ->
            tx.newEntity("type1").id
        }

        val oldSchoolEntityId = PersistentEntityId(entityId.typeId, entityId.localId)

        // model does not find the id because internal data structures are not initialized yet
        youTrackDb.withTxSession {
            assertNull(model.resolveEntityIdOrNull(it, oldSchoolEntityId.typeId, oldSchoolEntityId.localId))
        }

        // prepare() must initialize internal data structures in the end
        model.prepare()

        youTrackDb.withTxSession { session ->
            assertEquals(entityId, model.resolveEntityIdOrNull(session, oldSchoolEntityId.typeId, oldSchoolEntityId.localId))
        }
    }

    @Test
    fun `addAssociation() initializes complementary properties for indexed links`() {
        // This test's subject is incremental association-add over populated classes. The
        // index-mode preflight routes the populated owners through the supported fill path.
        oModel(youTrackDb.provider, YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)) {
            entity("type2")
            entity("type1")
            association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
            association("type2", "ass2", "type1", AssociationEndCardinality._0_n)
        }

        // the schema is already initialized because addAssociation implicitly calls prepare()

        val (id11, id12, id21) = youTrackDb.withStoreTx { tx ->
            val v11 = createVertexAndSetLocalEntityId(tx, "type1")
            val v12 = createVertexAndSetLocalEntityId(tx, "type1")
            val v21 = createVertexAndSetLocalEntityId(tx, "type2")

            v11.addSimpleEdge("ass1", v21)
            v21.addSimpleEdge("ass2", v11)
            v21.addSimpleEdge("ass2", v12)

            Triple(v11.id(), v12.id(), v21.id())
        }

        // links are not indexes, so there are no complementary properties
        youTrackDb.withTxSession { session ->
            val type1 = session.schema.getClass("type1")
            val type2 = session.schema.getClass("type2")
            assertFalse(type1.existsProperty(linkTargetEntityIdPropertyName("ass1")))
            assertFalse(type2.existsProperty(linkTargetEntityIdPropertyName("ass2")))
        }

        /*
         * XD-1283 index-mode preflight: a runtime association-add requiring backfill is DDL
         * tx -> batched backfill txs -> legacy non-tx index creation, which supports populated
         * classes, so the whole flow succeeds.
         */
        oModel(youTrackDb.provider, YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)) {
            entity("type2") {
                index(IndexedField("ass2", isProperty = false))
            }
            entity("type1") {
                index(IndexedField("ass1", isProperty = false))
            }
            association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
            association("type2", "ass2", "type1", AssociationEndCardinality._0_n)
        }

        // addAssociation() must have run the complementary-property backfill AND created
        // the indices over the populated classes
        youTrackDb.withTxSession { session ->
            val tx = session.activeTransaction
            val v11 = tx.loadVertex(id11)
            val v12 = tx.loadVertex(id12)
            val v21 = tx.loadVertex(id21)

            val bag11 = v11.getTargetLocalEntityIds("ass1")
            val bag21 = v21.getTargetLocalEntityIds("ass2")

            assertTrue(bag11.size() == 1)
            assertTrue(bag11.contains(v21.identity))
            assertTrue(bag21.size() == 2)
            assertTrue(bag21.contains(v11.identity))
            assertTrue(bag21.contains(v12.identity))

            session.checkIndex("type1", true, linkTargetEntityIdPropertyName("ass1"))
            session.checkIndex("type2", true, linkTargetEntityIdPropertyName("ass2"))
        }
    }

    @Test
    fun `addAssociation over populated classes succeeds with the index-mode preflight`() {
        // The preflight keeps empty owners transactional and routes populated owners through the
        // legacy non-transactional tail, avoiding YTDB-1064.
        oModel(youTrackDb.provider, YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)) {
            entity("type2")
            entity("type1")
            association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
        }

        youTrackDb.withStoreTx { tx ->
            val v1 = createVertexAndSetLocalEntityId(tx, "type1")
            val v2 = createVertexAndSetLocalEntityId(tx, "type2")
            v1.addSimpleEdge("ass1", v2)
        }

        oModel(youTrackDb.provider, YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)) {
            entity("type2")
            entity("type1") {
                index(IndexedField("ass1", isProperty = false))
            }
            association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
        }

        youTrackDb.provider.withSession { session ->
            session.checkIndex("type1", true, linkTargetEntityIdPropertyName("ass1"))
        }
    }

    @Test
    fun `getOrCreateEdgeClass joins the caller transaction if it already carries schema state`() {
        // XD-1283 AD3 guard, YTDBModelMetaData override: once the caller's transaction has
        // tx-local schema state (a prior schema write), a same-thread side-session DDL would
        // fail on the metadata write mutex - so the edge class AND its indices must be
        // created in the caller's transaction instead (one atomic unit, AD10).
        // Proof that they joined the caller's transaction: the rollback discards them; a
        // side-session creation would have been committed immediately and would survive.
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }
        model.prepare()

        val edgeClassName = YTDBVertexEntity.edgeClassName("ass1")
        val linkUniqueIndexName = "${edgeClassName}_in_out_unique"

        youTrackDb.withSession { session ->
            session.begin()
            // first schema write: the transaction now carries tx-local schema state
            session.schema.createVertexClass("guardDummy")
            assertNotNull(session.txSchemaState)

            val edgeClass = model.getOrCreateEdgeClass(session, "ass1", "type1", "type2")
            assertTrue(edgeClass.isEdgeType)
            // the DDL and the link unique index both joined the caller's transaction
            // (schema.indexExists consults only the committed index manager, so the tx-local
            // index creation is asserted via the transaction's index overlay)
            assertNotNull(session.schema.getClass(edgeClassName))
            assertTrue(session.txSchemaState!!.indexOverlay!!.isTxCreated(linkUniqueIndexName))
            assertFalse(session.schema.indexExists(linkUniqueIndexName))

            session.rollback()

            // the rollback discarded the edge class, its index and the seed class
            assertNull(session.schema.getClass(edgeClassName))
            assertFalse(session.schema.indexExists(linkUniqueIndexName))
        }

        youTrackDb.withSession { session ->
            assertNull(session.schema.getClass(edgeClassName))
            assertNull(session.schema.getClass("guardDummy"))
        }
    }

    @Test
    fun `oModel creates the schema for links if it is absent`() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }
        // initialize the entity types
        model.prepare()
        val store = YTDBPersistentEntityStore(youTrackDb.provider, youTrackDb.dbName, model)

        // check that the links do not exist
        withSession { session ->
            session.assertAssociationNotExist("type1", "type2", "link1")
            session.assertAssociationNotExist("type2", "type1", "link2")
        }

        // add the links, the necessary schema parts should be initialized on the way
        store.executeInTransaction { tx ->
            val e1 = tx.newEntity("type1")
            val e2 = tx.newEntity("type2")
            e1.addLink("link1", e2)
            e2.addLink("link2", e1)
        }

        withSession { session ->
            // check that the necessary schema parts have been initialized
            session.assertAssociationExists("type1", "type2", "link1", cardinality = null)
            session.assertAssociationExists("type2", "type1", "link2", cardinality = null)

            /**
             * It is a tricky one.
             * We have type2 -link2-> type1 link.
             * But we do not have type2 -link1-> type1 link yet.
             * So, the necessary schema parts must not be initialized for type2 -link1-> type1,
             * except for the link1 edge class itself. It must be initialized already. We
             * share edge classes between all the links with the same name.
             */
            session.assertAssociationNotExist("type2", "type1", "link1", requireEdgeClass = true)
        }

        // add a type2 -link1-> type1 link
        store.executeInTransaction { tx ->
            val e1 = tx.newEntity("type1")
            val e2 = tx.newEntity("type2")
            e2.addLink("link1", e1)
        }

        // check that the necessary schema parts have been initialized
        withSession { session ->
            session.assertAssociationExists("type2", "type1", "link1", cardinality = null)
        }
    }

    @Test
    fun `adding new link type does not cause OConcurrentModificationException`() {
        val model = oModel(youTrackDb.provider) {
            entity("type1")
            entity("type2")
        }
        // initialize the entity types
        model.prepare()
        val store = YTDBPersistentEntityStore(youTrackDb.provider, youTrackDb.dbName, model)

        // entity1 has already existed for a while
        val id1 = store.computeInTransaction { tx ->
            val e1 = tx.newEntity("type1")
            e1.setProperty("trista", "opca")
            e1.id
        }

        /**
         * Initializing new links must not affect vertices.
         * 1. All the business logic happens in session1.
         * 1. Initializing new links happens in a separate session, session2.
         * 2. Everything that happens in session2 is concurrent changes from the session1 point of view.
         * 3. So, if the initialization of new links affects vertices, session1 will fail with OConcurrentModificationException.
         */
        store.executeInTransaction { tx ->
            val e1 = tx.getEntity(id1)
            val e2 = tx.newEntity("type2")
            e1.addLink("link1", e2)
            e2.addLink("link2", e1)
            e1.setProperty("trista", "drista")
        }
    }

    @Test
    fun `adding an indexed property to a populated class succeeds with the index-mode preflight`() {
        // The initial schema and its indices are created transactionally while the class is empty,
        // then the populated owner is routed through the supported non-transactional fill path.
        oModel(youTrackDb.provider) {
            entity("type1")
        }.prepare()

        youTrackDb.withStoreTx { tx ->
            createVertexAndSetLocalEntityId(tx, "type1").property("newProperty", "indexed")
        }

        oModel(youTrackDb.provider) {
            entity("type1") {
                property("newProperty", "string")
            }
        }.prepare()

        youTrackDb.withTxSession { session ->
            session.checkIndex("type1", unique = false, "newProperty")
            val index = requireNotNull(
                session.sharedContext.indexManager.getIndex(session, "type1_newProperty")
            )
            val indexedRids = index.getRids(session, "indexed").toList()
            assertEquals(1, indexedRids.size)
        }
    }

    @Test
    fun `an indexed property on an empty supertype with a populated subtype is filled`() {
        oModel(youTrackDb.provider) {
            entity("base")
            entity("sub", "base")
        }.prepare()
        youTrackDb.withStoreTx { tx ->
            createVertexAndSetLocalEntityId(tx, "sub").property("indexedProperty", "indexed")
        }

        oModel(youTrackDb.provider) {
            entity("base") {
                property("indexedProperty", "string")
            }
            entity("sub", "base")
        }.prepare()

        youTrackDb.withTxSession { session ->
            session.checkIndex("base", unique = false, "indexedProperty")
            val index = requireNotNull(
                session.sharedContext.indexManager.getIndex(session, "base_indexedProperty")
            )
            assertEquals(1, index.getRids(session, "indexed").toList().size)
        }
    }

    @Test
    fun `a new supertype over a populated existing subtype is not treated as empty`() {
        oModel(youTrackDb.provider) {
            entity("sub")
        }.prepare()
        youTrackDb.withStoreTx { tx ->
            createVertexAndSetLocalEntityId(tx, "sub").property("indexedProperty", "indexed")
        }

        oModel(youTrackDb.provider) {
            entity("base") {
                property("indexedProperty", "string")
            }
            entity("sub", "base")
        }.prepare()

        youTrackDb.withTxSession { session ->
            session.assertHasSuperClass("sub", "base")
            session.checkIndex("base", unique = false, "indexedProperty")
            val index = requireNotNull(
                session.sharedContext.indexManager.getIndex(session, "base_indexedProperty")
            )
            assertEquals(1, index.getRids(session, "indexed").toList().size)
        }
    }

    @Test
    fun `prepare() over a populated class succeeds with the index-mode preflight`() {
        // The populated owner is routed through the supported non-transactional fill path.
        oModel(youTrackDb.provider) {
            entity("type1") {
                property("prop1", "int")
            }
        }.prepare()
        youTrackDb.withStoreTx { tx ->
            createVertexAndSetLocalEntityId(tx, "type1").property("prop1", 1)
        }

        val upgraded = oModel(youTrackDb.provider) {
            entity("type1") {
                property("prop1", "int")
                property("prop2", "string")
            }
        }

        upgraded.prepare()

        youTrackDb.withSession { session ->
            session.checkIndex("type1", unique = false, "prop2")
        }
    }

}
