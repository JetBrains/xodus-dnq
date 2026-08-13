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

import jetbrains.exodus.entitystore.youtrackdb.ClassIdReservation
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_SEQUENCE_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import jetbrains.exodus.entitystore.youtrackdb.createSequencesIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.requireClassId
import jetbrains.exodus.entitystore.youtrackdb.setClassIdIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.withTx
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The batching that collapses the per-class / per-link transactions of a schema pass into a
 * constant number of them (XD-1283 performance).
 */
class SchemaInitBatchingTest {

    @Rule
    @JvmField
    val orientDb = InMemoryYouTrackDB(initializeIssueSchema = false)

    // ---- sequence batching -----------------------------------------------------------------

    @Test
    fun `createSequencesIfAbsent creates every missing sequence`() =
        orientDb.provider.withSession { session ->
            val names = (1..5).map { localEntityIdSequenceName("type$it") }
            session.createSequencesIfAbsent(names + CLASS_ID_SEQUENCE_NAME)

            for (name in names + CLASS_ID_SEQUENCE_NAME) {
                assertNotNull(
                    session.metadata.sequenceLibrary.getSequence(name),
                    "$name must have been created"
                )
            }
        }

    @Test
    fun `createSequencesIfAbsent is idempotent and tolerates a mixed present-absent batch`() {
        orientDb.provider.withSession { session ->
            session.createSequencesIfAbsent(listOf(localEntityIdSequenceName("type1")))
            // second call: one existing + one new name - the existing one must not be re-created
            // (createSequence throws "already exists"), the new one must appear
            session.createSequencesIfAbsent(
                listOf(localEntityIdSequenceName("type1"), localEntityIdSequenceName("type2"))
            )

            assertNotNull(session.metadata.sequenceLibrary.getSequence(localEntityIdSequenceName("type1")))
            assertNotNull(session.metadata.sequenceLibrary.getSequence(localEntityIdSequenceName("type2")))
        }
    }

    @Test
    fun `sequences created in the batch are committed and usable right away`() =
        orientDb.provider.withSession { session ->
            // The batch runs on an independent session, so its sequences must be visible to
            // DBSequence.next(), which self-hoists to yet another pooled session and can only see
            // committed records (XD-1283/AD1).
            session.createSequencesIfAbsent(listOf(CLASS_ID_SEQUENCE_NAME))
            val sequence = session.metadata.sequenceLibrary.getSequence(CLASS_ID_SEQUENCE_NAME)!!
            assertEquals(0L, sequence.next(session))
            assertEquals(1L, sequence.next(session))
        }

    // ---- classId reservation ---------------------------------------------------------------

    @Test
    fun `class ids come from a reserved block and stay unique`() {
        orientDb.provider.withSession { session ->
            session.createSequencesIfAbsent(listOf(CLASS_ID_SEQUENCE_NAME))
        }

        val ids = orientDb.provider.withSession { session ->
            session.withTx {
                val reservation = ClassIdReservation(3)
                (1..3).map { i ->
                    val oClass = it.schema.createVertexClass("reserved$i")
                    it.setClassIdIfAbsent(oClass, reservation)
                    oClass.requireClassId()
                }
            }
        }

        assertEquals(listOf(0, 1, 2), ids)
        orientDb.provider.withSession { session ->
            // the sequence has been jumped past the whole block, so the next consumer cannot
            // hand out an id from it
            val sequence = session.metadata.sequenceLibrary.getSequence(CLASS_ID_SEQUENCE_NAME)!!
            assertEquals(3L, sequence.next(session))
        }
    }

    @Test
    fun `a reservation that runs out falls back to the sequence`() {
        orientDb.provider.withSession { session ->
            session.createSequencesIfAbsent(listOf(CLASS_ID_SEQUENCE_NAME))
        }

        val ids = orientDb.provider.withSession { session ->
            session.withTx {
                // reserve one, ask for three
                val reservation = ClassIdReservation(1)
                (1..3).map { i ->
                    val oClass = it.schema.createVertexClass("overflow$i")
                    it.setClassIdIfAbsent(oClass, reservation)
                    oClass.requireClassId()
                }
            }
        }

        assertEquals(3, ids.distinct().size, "class ids must stay unique past the block: $ids")
    }

    @Test
    fun `an empty reservation hands out ids straight from the sequence`() {
        orientDb.provider.withSession { session ->
            session.createSequencesIfAbsent(listOf(CLASS_ID_SEQUENCE_NAME))
        }

        val id = orientDb.provider.withSession { session ->
            session.withTx {
                val oClass = it.schema.createVertexClass("noReservation")
                it.setClassIdIfAbsent(oClass, ClassIdReservation(0))
                oClass.requireClassId()
            }
        }
        assertEquals(0, id)
    }

    @Test
    fun `a full schema pass assigns a unique class id to every type`() {
        val model = model {
            for (i in 1..20) {
                entity("type$i", superType = if (i % 5 == 0) null else "type${i - i % 5 + 5}")
            }
        }
        // the hierarchy above must be well-formed for the topological sort
        orientDb.provider.withSession { session ->
            session.applySchemaInTx(model)
        }

        orientDb.provider.withSession { session ->
            val ids = (1..20).map { session.schema.getClass("type$it")!!.requireClassId() }
            assertEquals(20, ids.distinct().size, "class ids must be unique: $ids")
        }
    }

    @Test
    fun `a second schema pass reuses the class ids of the first`() {
        val model = model {
            entity("type1")
            entity("type2")
        }
        val first = orientDb.provider.withSession { session ->
            session.applySchemaInTx(model)
            listOf("type1", "type2").map { session.schema.getClass(it)!!.requireClassId() }
        }

        val extended = model {
            entity("type1")
            entity("type2")
            entity("type3")
        }
        val second = orientDb.provider.withSession { session ->
            session.applySchemaInTx(extended)
            listOf("type1", "type2", "type3").map { session.schema.getClass(it)!!.requireClassId() }
        }

        assertEquals(first, second.take(2))
        assertTrue(second[2] !in first, "the new type must get a fresh class id")
    }

    // ---- buildModel ------------------------------------------------------------------------

    @Test
    fun `buildModel applies the whole model in one pass`() {
        val model = YTDBModelMetaData(orientDb.provider)
        model.buildModel {
            model.entity("type1") { property("prop1", "int") }
            model.entity("type2")
            model.association("type2", "ass1", "type1", AssociationEndCardinality._0_n)
        }

        orientDb.provider.withSession { session ->
            session.assertVertexClassExists("type1")
            session.assertVertexClassExists("type2")
            session.assertAssociationExists("type2", "type1", "ass1", AssociationEndCardinality._0_n)
        }
    }

    @Test
    fun `buildModel suppresses the callbacks until it returns`() {
        val model = YTDBModelMetaData(orientDb.provider)
        model.buildModel {
            model.entity("type1")
            model.entity("type2")
            model.association("type2", "ass1", "type1", AssociationEndCardinality._0_n)

            // nothing has been applied to the database yet
            orientDb.provider.withSession { session ->
                assertNull(session.schema.getClass("type1"))
                assertNull(session.schema.getClass("type2"))
            }
        }

        orientDb.provider.withSession { session ->
            session.assertVertexClassExists("type1")
        }
    }

    @Test
    fun `a failing buildModel applies nothing`() {
        val model = YTDBModelMetaData(orientDb.provider)
        assertFailsWith<IllegalStateException> {
            model.buildModel {
                model.entity("type1")
                throw IllegalStateException("boom")
            }
        }

        orientDb.provider.withSession { session ->
            assertNull(session.schema.getClass("type1"))
        }

        // the model is usable afterwards: the suppression flag was cleared AND the memoized model
        // view was dropped, so a plain prepare() applies what the failed build left behind
        model.prepare()
        orientDb.provider.withSession { session ->
            session.assertVertexClassExists("type1")
        }

        model.entity("type2")
        model.prepare()
        orientDb.provider.withSession { session ->
            session.assertVertexClassExists("type2")
        }
    }

    @Test
    fun `nested buildModel applies once, at the outermost exit`() {
        val model = YTDBModelMetaData(orientDb.provider)
        model.buildModel {
            model.entity("type1")
            model.buildModel {
                model.entity("type2")
            }
            orientDb.provider.withSession { session ->
                assertNull(session.schema.getClass("type2"), "the nested block must not apply")
            }
        }

        orientDb.provider.withSession { session ->
            session.assertVertexClassExists("type1")
            session.assertVertexClassExists("type2")
        }
    }

    /**
     * A model whose SUPER type declares an index over a link: applying the whole model in one pass
     * makes every sub type see that inherited index (`EntityMetaData.indexes` includes the super
     * types' indexes), and the index must still be created once - on the declaring type - not once
     * per sub type. YTDB indexes are polymorphic, so a sub-type copy adds no coverage and only
     * costs an index engine.
     */
    @Test
    fun `an index inherited by sub types is created once, on the declaring type`() {
        val model = model {
            entity("base") {
                property("prop1", "string")
                index(IndexedField("prop1", isProperty = true), IndexedField("toOther", isProperty = false))
            }
            entity("sub1", "base")
            entity("sub2", "base")
            entity("other")
            association("base", "toOther", "other", AssociationEndCardinality._0_n)
        }

        val indices = orientDb.provider.withSession { session ->
            session.applySchemaInTx(model).indices
        }

        val compositeIndexOwners = indices.entries
            .filter { (_, deferred) ->
                deferred.any { it.properties.contains("prop1") && it.properties.size > 1 }
            }
            .map { it.key }
        assertEquals(listOf("base"), compositeIndexOwners)
    }
}
