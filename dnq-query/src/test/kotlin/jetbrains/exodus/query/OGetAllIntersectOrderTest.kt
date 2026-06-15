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
package jetbrains.exodus.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.junit.Rule
import org.junit.Test

/**
 * XD-1278: intersecting `GetAll(type)` with another iterable must preserve that other iterable's
 * iteration order (and exclude dead/stale entities). The order-sensitive operand stands in for
 * full-text search results, whose relevance ranking is intentionally different from entity-id order.
 */
class OGetAllIntersectOrderTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    private fun engine() = QueryEngine(null, youTrackDb.store).apply { sortEngine = SortEngine(this) }

    private fun byIds(tx: YTDBStoreTransaction, vararg entities: Entity): YTDBEntityIterable =
        YTDBEntityIterable.query(
            tx.getStore(),
            GremlinQuery.ByIds(entities.map { (it.id as YTDBEntityId).asOId() })
        )

    @Test
    fun `x intersect GetAll preserves the relevance order of x`() {
        val test = givenTestCase()
        val engine = engine()
        withStoreTx { tx ->
            // relevance order intentionally differs from entity-id (creation) order
            val ranked = byIds(tx, test.issue3, test.issue1, test.issue2)
            val all = engine.queryGetAll(Issues.CLASS)

            assertNamesExactlyInOrder(engine.intersect(ranked, all), "issue3", "issue1", "issue2")
        }
    }

    @Test
    fun `GetAll intersect x preserves the relevance order of x`() {
        val test = givenTestCase()
        val engine = engine()
        withStoreTx { tx ->
            val ranked = byIds(tx, test.issue3, test.issue1, test.issue2)
            val all = engine.queryGetAll(Issues.CLASS)

            assertNamesExactlyInOrder(engine.intersect(all, ranked), "issue3", "issue1", "issue2")
        }
    }

    @Test
    fun `intersecting GetAll with an in-memory operand excludes stale entities and keeps order`() {
        val test = givenTestCase()
        val engine = engine()

        // Hold references in a deliberate order, then delete one of them in a later transaction.
        val ordered: List<Entity> = listOf(test.issue3, test.issue1, test.issue2)
        withStoreTx { it.getEntity(test.issue2.id).delete() }

        withStoreTx {
            val inMemory = InMemoryEntityIterable(ordered, txn = it, engine)
            val all = engine.queryGetAll(Issues.CLASS)

            // issue2 is stale: excluded. issue3, issue1 kept, in the in-memory order (not id order).
            assertNamesExactlyInOrder(engine.intersect(all, inMemory), "issue3", "issue1")
        }
    }

    @Test
    fun `GetAll intersect a typed query is unchanged`() {
        givenTestCase()
        val engine = engine()
        withStoreTx {
            val all = engine.queryGetAll(Issues.CLASS)
            val issue2 = engine.query(Issues.CLASS, NodeFactory.propEqual("name", "issue2"))

            // GetAll(Issue) ∩ issues(name = issue2) == issues(name = issue2)
            assertNamesExactly(engine.intersect(all, issue2), "issue2")
            assertNamesExactly(engine.intersect(issue2, all), "issue2")
        }
    }

    @Test
    fun `GetAll intersect filters out entities of a different type`() {
        val test = givenTestCase()
        val engine = engine()
        withStoreTx { tx ->
            // a project mixed in among the issue ids — must be dropped by GetAll(Issue)
            val mixed = byIds(tx, test.issue3, test.project1, test.issue1)
            val allIssues = engine.queryGetAll(Issues.CLASS)

            assertNamesExactlyInOrder(engine.intersect(mixed, allIssues), "issue3", "issue1")
        }
    }
}
