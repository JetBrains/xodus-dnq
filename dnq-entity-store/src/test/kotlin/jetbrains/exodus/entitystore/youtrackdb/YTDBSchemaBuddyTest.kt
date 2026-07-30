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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YTDBSchemaBuddyTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = orientDbRule

    @Test
    fun `if autoInitialize is false, explicit initialization is required`() {
        withSession { session ->
            session.getOrCreateVertexClass(Issues.CLASS)
        }
        val issueId = withStoreTx { tx ->
            tx.createIssue("trista").id
        }
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = false)

        withSession {
            assertNull(buddy.resolveEntityIdOrNull(it, issueId.typeId, issueId.localId))
        }

        withSession {
            buddy.initialize(it)
        }

        withTxSession {
            assertEquals(issueId, buddy.resolveEntityIdOrNull(it, issueId.typeId, issueId.localId))
        }
    }

    @Test
    fun `requireTypeExists() fails if the class is absent`() {
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val className = "trista"
        withSession { session ->
            assertNull(session.schema.getClass(className))
            assertFailsWith<IllegalStateException> { buddy.requireTypeExists(session, className) }
        }
    }

    @Test
    fun `resolveEntityIdOrNull() works with both existing and not existing EntityId`() {
        withSession { session ->
            session.getOrCreateVertexClass(Issues.CLASS)
        }
        val issueId = withStoreTx { tx ->
            tx.createIssue("trista").id
        }
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider, autoInitialize = true)

        withTxSession {
            assertNull(buddy.resolveEntityIdOrNull(it, 300, 301))
            assertNull(buddy.resolveEntityIdOrNull(it, issueId.typeId, 301))
            assertNull(buddy.resolveEntityIdOrNull(it, 300, issueId.localId))
            assertEquals(issueId, buddy.resolveEntityIdOrNull(it, issueId.typeId, issueId.localId))
        }
    }

    /*
    * SchemaBuddy heavily depends on this invariant for the classId map consistency
    * */
    @Test
    fun `sequence does not roll back already generated values if the transaction is rolled back`() {
        withSession { session ->
            val params = DBSequence.CreateParams()
            params.start = 0
            (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.createSequence(
                "seq",
                DBSequence.SEQUENCE_TYPE.ORDERED,
                params
            )
        }

        youTrackDb.withStoreTx(failOnRollback = false) { tx ->
            val res = tx.getSequenceNextValue("seq")
            assertEquals(1, res)
            tx.abort()
        }

        youTrackDb.withStoreTx { tx ->
            val res = tx.getSequenceNextValue("seq")
            assertEquals(2, res)
        }
    }

    @Test
    fun `can create an edge class in a transaction`() {
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val edgeClassName = YTDBVertexEntity.edgeClassName("trista")

        // the edge class is not there
        withSession { session ->
            session.schema.createVertexClass("issue")
            assertNull(session.schema.getClass(edgeClassName))
        }

        // create the edge class in a transaction
        val issId = withSession { session ->
            val tx = session.begin()
            val iss = tx.newVertex("issue")

            val edgeClass = buddy.getOrCreateEdgeClass(session, "trista", "issue", "issue")
            assertNotNull(edgeClass)
            assertTrue(edgeClass.isEdgeType)

            tx.commit()
            iss.identity
        }

        // the changes made in the transaction are still there
        withStoreTx { tx ->
            assertNotNull(tx.getVertex(issId))
        }
    }

    @Test
    fun `concurrent getOrCreateEdgeClass calls for the same link both succeed`() {
        // XD-1283 site-4 concurrent-creation race: two sessions may both find the edge class
        // absent and both attempt to create it; the single-permit metadata write mutex
        // serializes the side transactions, so the loser's createEdgeClass throws
        // "already exists" - it must be caught, re-checked and both callers must succeed
        // with the same class. Repeated to give the race window a decent chance to be hit;
        // the contract holds for every interleaving.
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)

        repeat(10) { i ->
            val linkName = "racyLink$i"
            val barrier = CyclicBarrier(2)
            val errors = ConcurrentLinkedQueue<Throwable>()

            val threads = (1..2).map {
                thread {
                    try {
                        youTrackDb.provider.withSession { session ->
                            barrier.await()
                            val edgeClass =
                                buddy.getOrCreateEdgeClass(session, linkName, "issue", "issue")
                            assertTrue(edgeClass.isEdgeType)
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
            threads.forEach { it.join() }

            assertTrue(errors.isEmpty(), "concurrent getOrCreateEdgeClass failed: $errors")
            withSession { session ->
                val edgeClass = session.schema.getClass(YTDBVertexEntity.edgeClassName(linkName))
                assertNotNull(edgeClass)
                assertTrue(edgeClass.isEdgeType)
            }
        }
    }

    @Test
    fun `getOrCreateEdgeClass joins the caller transaction if it already carries schema state`() {
        // XD-1283 AD3 guard: once the caller's transaction has tx-local schema state (a prior
        // schema write), a same-thread side-session DDL would fail on the metadata write
        // mutex - so the edge class must be created in the caller's transaction instead.
        // Proof that it joined the caller's transaction: the rollback discards it; a
        // side-session creation would have been committed immediately and would survive.
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val edgeClassName = YTDBVertexEntity.edgeClassName("guardedLink")

        withSession { session ->
            session.begin()
            // first schema write: the transaction now carries tx-local schema state
            session.schema.createVertexClass("guardedType")
            assertNotNull(session.txSchemaState)

            val edgeClass = buddy.getOrCreateEdgeClass(session, "guardedLink", "guardedType", "guardedType")
            assertTrue(edgeClass.isEdgeType)

            session.rollback()

            // the rollback discarded the edge class along with the rest of the tx-local schema
            assertNull(session.schema.getClass(edgeClassName))
        }

        withSession { session ->
            assertNull(session.schema.getClass(edgeClassName))
            assertNull(session.schema.getClass("guardedType"))
        }
    }

    @Test
    fun `renameOClass and deleteOClass require an active transaction`() {
        // XD-1283 site 6: both operations now run on the CALLER's session and must never fall
        // back to a non-transactional schema write.
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        withSession { session ->
            session.schema.createVertexClass("typeToRefactor")
        }

        withSession { session ->
            assertFailsWith<IllegalStateException> {
                buddy.renameOClass(session, "typeToRefactor", "renamedType")
            }
            assertFailsWith<IllegalStateException> {
                buddy.deleteOClass(session, "typeToRefactor")
            }
        }

        withSession { session ->
            assertNotNull(session.schema.getClass("typeToRefactor"))
            assertNull(session.schema.getClass("renamedType"))
        }
    }

    @Test
    fun `a rolled back rename does not poison the classId to name cache`() {
        // XD-1283 site 6: the rename rides the caller's transaction, so schema reads on that
        // session resolve the uncommitted tx-local name. getType() must not memoize it - a
        // cached tx-local name would survive the rollback and mis-resolve the type forever.
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val typeId = withTxSession { session ->
            session.createVertexClassWithClassId("typeToRename").requireClassId()
        }

        withSession { session ->
            session.begin()
            buddy.renameOClass(session, "typeToRename", "renamedType")
            // resolving the type inside the renaming transaction sees the tx-local name
            assertEquals("renamedType", buddy.getType(session, typeId))
            session.rollback()
        }

        withSession { session ->
            assertEquals("typeToRename", buddy.getType(session, typeId))
        }
    }

    @Test
    fun `resolveEntityIdOrNull still resolves entities of a renamed type`() {
        // The classId -> (collectionId, name) cache is read by resolveEntityIdOrNull as a
        // COLLECTION id (getClassByCollectionId). getType() must therefore memoize the
        // collection id, not the classId - otherwise every entity of a type whose cache entry
        // was (re)populated by getType resolves to null (XD-1283).
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        withSession { session ->
            session.getOrCreateVertexClass(Issues.CLASS)
        }
        val issueId = withStoreTx { tx -> tx.createIssue("trista").id }

        withSession { session ->
            val tx = session.begin()
            buddy.renameOClass(session, Issues.CLASS, "RenamedIssue")
            tx.commit()
        }
        // the cache entry for this type is now (re)populated by getType, not by scanClasses
        withSession { session ->
            assertEquals("RenamedIssue", buddy.getType(session, issueId.typeId))
        }

        withTxSession { session ->
            val resolved = buddy.resolveEntityIdOrNull(session, issueId.typeId, issueId.localId)
            assertNotNull(resolved)
            assertEquals(issueId.localId, resolved.localId)
        }
    }

    @Test
    fun `a committed drop invalidates the cached class name`() {
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val typeId = withTxSession { session ->
            session.createVertexClassWithClassId("typeToDrop").requireClassId()
        }
        withSession { session ->
            assertEquals("typeToDrop", buddy.getType(session, typeId))
        }

        withSession { session ->
            val tx = session.begin()
            buddy.deleteOClass(session, "typeToDrop")
            tx.commit()
        }

        withSession { session ->
            assertFailsWith<EntityRemovedInDatabaseException> { buddy.getType(session, typeId) }
        }
    }

    @Test
    fun `a cached class name renamed by another session is not served from the cache`() {
        // The eviction at the DDL site cannot cover an entry re-cached between the DDL and its
        // commit, so a cache hit must be validated against the schema (XD-1283).
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val otherBuddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val typeId = withTxSession { session ->
            session.createVertexClassWithClassId("typeToRename").requireClassId()
        }
        withSession { session ->
            assertEquals("typeToRename", buddy.getType(session, typeId))
        }

        // another schema buddy (i.e. another cache) commits the rename: nothing evicts the
        // first buddy's entry
        withSession { session ->
            val tx = session.begin()
            otherBuddy.renameOClass(session, "typeToRename", "renamedType")
            tx.commit()
        }

        withSession { session ->
            assertEquals("renamedType", buddy.getType(session, typeId))
        }
    }

    @Test
    fun `a committed rename invalidates the cached class name`() {
        // The cache is primed with the old name before the rename, so the rename must evict it -
        // otherwise getType() keeps reporting a name that no longer exists (XD-1283 site 6).
        val buddy = YTDBSchemaBuddyImpl(youTrackDb.provider)
        val typeId = withTxSession { session ->
            session.createVertexClassWithClassId("typeToRename").requireClassId()
        }
        withSession { session ->
            assertEquals("typeToRename", buddy.getType(session, typeId))
        }

        withSession { session ->
            val tx = session.begin()
            buddy.renameOClass(session, "typeToRename", "renamedType")
            tx.commit()
        }

        withSession { session ->
            assertEquals("renamedType", buddy.getType(session, typeId))
        }
    }

    @Test
    fun `require both classId and localEntityId to create an instance`() {
        val typeID = youTrackDb.provider.withSession { oSession ->
            val oClass = oSession.createVertexClassWithClassId("type1")
            oClass.requireClassId()
        }
        youTrackDb.provider.withSession { oSession ->
            assertEquals("type1", youTrackDb.schemaBuddy.getType(oSession, typeID))
        }

    }

}
