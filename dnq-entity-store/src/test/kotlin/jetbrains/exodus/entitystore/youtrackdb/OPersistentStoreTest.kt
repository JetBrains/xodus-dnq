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

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.PersistentEntityId
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues.CLASS
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import jetbrains.exodus.entitystore.youtrackdb.testutil.createIssue
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OPersistentStoreTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    @Test
    fun `renameEntityType() works only inside a transaction`() {
        // make sure the schema class is created
        youTrackDb.createIssue("trista")

        assertFailsWith<IllegalStateException> {
            youTrackDb.store.renameEntityType(
                CLASS,
                "NewName"
            )
        }

        youTrackDb.store.executeInTransaction {
            youTrackDb.store.renameEntityType(CLASS, "NewName")
        }
    }

    @Test
    fun renameClassTest() {
        val summary = "Hello, your product does not work"
        youTrackDb.createIssue(summary)
        val store = youTrackDb.store

        val newClassName = "Other${CLASS}"
        store.executeInTransaction {
            store.renameEntityType(CLASS, newClassName)
        }
        val issueByNewName = store.computeInExclusiveTransaction { tx ->
            tx.getAll(newClassName).first()
        }
        Assert.assertNotNull(issueByNewName)
        store.executeInTransaction {
            assertEquals(summary, issueByNewName.getProperty("name"))
        }
    }

    @Test
    fun transactionPropertiesTest() {
        val issue = youTrackDb.createIssue("Hello, nothing works")
        val store = youTrackDb.store
        store.computeInTransaction {
            Assert.assertTrue(it.isIdempotent)
            issue.setProperty("version", "22")
            Assert.assertFalse(it.isIdempotent)
        }
    }

    @Test
    fun `a transaction with only schema changes is not idempotent`() {
        // XD-1283 site 6: in-tx DDL leaves no record operations, so a schema-only transaction
        // used to look idempotent - and the transient flush path silently aborted it.
        youTrackDb.createIssue("trista")
        val store = youTrackDb.store
        store.executeInTransaction { tx ->
            Assert.assertTrue(tx.isIdempotent)
            store.renameEntityType(CLASS, "NewName")
            Assert.assertFalse(tx.isIdempotent)
        }
    }

    @Test
    fun `renameEntityType is discarded when the transaction is rolled back`() {
        // XD-1283 site 6: the rename joins the caller's transaction, so it must NOT leak out of
        // a rolled-back transaction (the previous separate-session implementation committed it
        // immediately and did leak).
        youTrackDb.createIssue("trista")
        val store = youTrackDb.store

        youTrackDb.withStoreTx(failOnRollback = false) { tx ->
            store.renameEntityType(CLASS, "NewName")
            tx.abort()
        }

        youTrackDb.withSession { session ->
            assertNull(session.schema.getClass("NewName"))
            Assert.assertNotNull(session.schema.getClass(CLASS))
        }
    }

    @Test
    fun `deleteEntityType is discarded when the transaction is rolled back`() {
        // XD-1283 site 6: same contract as the rename - the class drop rides the caller's
        // transaction and disappears with it.
        youTrackDb.createIssue("trista")
        val store = youTrackDb.store

        youTrackDb.withStoreTx(failOnRollback = false) { tx ->
            store.deleteEntityType(CLASS)
            tx.abort()
        }

        youTrackDb.withSession { session ->
            Assert.assertNotNull(session.schema.getClass(CLASS))
        }
        youTrackDb.withStoreTx { tx ->
            assertEquals(1, tx.getAll(CLASS).size())
        }
    }

    @Test
    fun `create and increment sequence`() {
        val store = youTrackDb.store
        val sequence = store.computeInTransaction {
            it.getSequence("first")
        }
        store.executeInTransaction {
            assertEquals(0, it.getSequence("first").increment())
        }
        store.executeInTransaction {
            assertEquals(0, it.getSequence("first").get())
        }
        store.executeInTransaction {
            assertEquals(1, it.getSequence("first").increment())
        }
    }

    @Test
    fun `create sequence with starting from`() {
        val store = youTrackDb.store
        val sequence = store.computeInTransaction {
            it.getSequence("first", 99)
        }
        store.executeInTransaction {
            assertEquals(100, it.getSequence("first").increment())
        }
    }

    @Test
    fun `can set actual value to sequence`() {
        val store = youTrackDb.store
        val sequence = store.computeInTransaction {
            it.getSequence("first", 99)
        }
        store.executeInTransaction {
            sequence.set(400)
        }
        store.executeInTransaction {
            assertEquals(401, sequence.increment())
        }
    }

    @Test
    fun `getEntity() resolves unresolved RIDEntityId via DB lookup`() {
        val aId = youTrackDb.createIssue("A").id
        val bId = youTrackDb.createIssue("B").id
        val store = youTrackDb.store

        // use default ids
        youTrackDb.store.executeInTransaction {
            val a = store.getEntity(aId)
            val b = store.getEntity(bId)

            assertEquals(aId, a.id)
            assertEquals(bId, b.id)
        }

        // use legacy ids
        youTrackDb.store.executeInTransaction {
            val unresolvedA = PersistentEntityId(aId.typeId, aId.localId)
            val unresolvedB = PersistentEntityId(bId.typeId, bId.localId)
            val a = store.getEntity(unresolvedA)
            val b = store.getEntity(unresolvedB)

            assertEquals(aId, a.id)
            assertEquals(bId, b.id)
        }

    }

    @Test
    fun `getEntity() throw exception the entity is not found`() {
        val aId = youTrackDb.createIssue("A").id

        // delete the issue
        youTrackDb.withStoreTx { tx ->
            tx.deleteVertex(aId.asOId())
        }

        // entity not found
        youTrackDb.store.executeInTransaction { tx ->
            assertFailsWith<EntityRemovedInDatabaseException> {
                youTrackDb.store.getEntity(aId)
            }
            assertFailsWith<EntityRemovedInDatabaseException> {
                youTrackDb.store.getEntity(PersistentEntityId(300, 300))
            }
        }
    }

    @Test
    fun `resolveEntityIdOrNull returns null for a not existing EntityId`() {
        val issueId = youTrackDb.createIssue("trista").id
        youTrackDb.store.executeInTransaction {
            assertNull(youTrackDb.store.resolveEntityIdOrNull(300, 301))
            assertNull(youTrackDb.store.resolveEntityIdOrNull(issueId.typeId, 301))
            assertNull(youTrackDb.store.resolveEntityIdOrNull(300, issueId.localId))
            assertEquals(issueId, youTrackDb.store.resolveEntityIdOrNull(issueId.typeId, issueId.localId))
        }
    }

    @Test
    fun `toEntityId(representation) parses into a logical PersistentEntityId without resolving`() {
        val issueId = youTrackDb.createIssue("trista").id
        val notExistingEntityId = PersistentEntityId(300, 301)
        val partiallyExistingEntityId1 = PersistentEntityId(issueId.typeId, 301)
        val partiallyExistingEntityId2 = PersistentEntityId(300, issueId.localId)
        val totallyExistingEntityId = PersistentEntityId(issueId.typeId, issueId.localId)
        youTrackDb.store.executeInTransaction { txn ->
            // Parse-only: every representation — whether the entity exists or not — yields a logical
            // PersistentEntityId carrying the parsed (typeId, localId), never a resolved YTDBEntityId.
            for (source in listOf(
                notExistingEntityId,
                partiallyExistingEntityId1,
                partiallyExistingEntityId2,
                totallyExistingEntityId,
            )) {
                val parsed = txn.toEntityId(source.toString())
                assertTrue(parsed is PersistentEntityId, "expected PersistentEntityId, got ${parsed.javaClass.name}")
                assertEquals(source.localId, parsed.localId)
                assertEquals(source.typeId, parsed.typeId)
            }
        }
    }

    @Test
    fun `propertyNames does not count internal properties`() {
        val issue = youTrackDb.store.computeInTransaction { txn ->
            txn as YTDBStoreTransaction
            val issue = txn.createIssue("Hello", "Critical")
            val project = txn.createProject("World")
            txn.addIssueToProject(issue, project)
            issue.setBlobString("bober", "bober")
            issue.setBlob("biba", "hello".toByteArray().inputStream())
            issue.setProperty("hello", 1995)
            issue
        }
        youTrackDb.store.executeInTransaction {
            assertEquals(
                listOf(Issues.Props.PRIORITY, "name", "hello").sorted(),
                issue.propertyNames.sorted()
            )
        }
    }

    @Test
    fun `can delete entityType`() {
        youTrackDb.withSession { session ->
            Assert.assertNotNull(session.schema.getClass(Issues.CLASS))
        }
        youTrackDb.createIssue("trista")
        youTrackDb.withStoreTx {
            youTrackDb.store.deleteEntityType(Issues.CLASS)
        }
        youTrackDb.withSession { session ->
            Assert.assertNull(session.schema.getClass(Issues.CLASS))
        }
    }

    @Test
    fun `resolveEntityId works correctly with different types of EntityId`() {
        val issueId = youTrackDb.createIssue("trista").id

        youTrackDb.store.executeInTransaction {
            assertEquals(issueId, youTrackDb.store.resolveEntityId(issueId))
            assertEquals(
                issueId,
                youTrackDb.store.resolveEntityId(PersistentEntityId(issueId.typeId, issueId.localId))
            )
            // a non-existent id cannot be resolved and must throw
            assertFailsWith<EntityRemovedInDatabaseException> {
                youTrackDb.store.resolveEntityId(PersistentEntityId(300, 301))
            }
        }
    }

    @Test
    fun `computeInTransaction and Co handle exceptions properly`() {
        withSession { session ->
            val t1 = session.getOrCreateVertexClass("type1")
            t1.createProperty("name", PropertyType.STRING)
            t1.createIndex("opca_index", SchemaClass.INDEX_TYPE.UNIQUE, "name")
        }
        fun StoreTransaction.violateIndexRestriction() {
            val e1 = this.newEntity("type1")
            val e2 = this.newEntity("type1")
            e1.setProperty("name", "trista")
            e2.setProperty("name", "trista")
        }

        /**
         * Here we check that nothing happens with the exception on the way up.
         * Our code that finishes the transaction must work correctly if there is no active session.
         */
        val store = youTrackDb.store
        assertFailsWith<RecordDuplicatedException> {
            store.computeInTransaction { tx ->
                tx.violateIndexRestriction()
            }
        }
        assertFailsWith<RecordDuplicatedException> {
            store.executeInTransaction { tx ->
                tx.violateIndexRestriction()
            }
        }
        assertFailsWith<RecordDuplicatedException> {
            withStoreTx { tx ->
                tx.violateIndexRestriction()
            }
        }
    }

    @Test
    fun `read-only mode flag is propagated to the store`() {
        youTrackDb.provider.readOnly = true
        assertEquals(true, youTrackDb.store.isReadOnly)
        youTrackDb.provider.readOnly = false
    }
}
