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
import jetbrains.exodus.entitystore.youtrackdb.createVertexClassWithClassId
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.testutil.BaseUser
import jetbrains.exodus.entitystore.youtrackdb.testutil.Guest
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import jetbrains.exodus.entitystore.youtrackdb.testutil.User
import jetbrains.exodus.entitystore.youtrackdb.testutil.createUser
import org.junit.Rule
import org.junit.Test

/**
 * Reproduction for the YTDB-820 GetAll-intersect regression (XD-1278 `intersectWithGetAll`):
 * `GetAll(BaseType) ∩ <by-ids operand>` must keep entities whose concrete type is a *subtype* of
 * BaseType. It currently drops them, because membership is resolved with a `V(ids).hasLabel(Base)`
 * query whose label filter is applied non-polymorphically on the by-ids GraphStep path.
 */
class OGetAllIntersectSubtypeTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = orientDbRule

    private fun engine() = QueryEngine(null, youTrackDb.store).apply { sortEngine = SortEngine(this) }

    private fun byIds(tx: YTDBStoreTransaction, vararg entities: Entity): YTDBEntityIterable =
        YTDBEntityIterable.query(
            tx.getStore(),
            GremlinQuery.ByIds(entities.map { (it.id as YTDBEntityId).asOId() })
        )

    private fun givenUserHierarchy() {
        youTrackDb.withSession { session ->
            val baseClass = session.createVertexClassWithClassId(BaseUser.CLASS)
            listOf(
                session.createVertexClassWithClassId(User.CLASS),
                session.createVertexClassWithClassId(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
        }
    }

    /** Sanity: a plain polymorphic GetAll(Base) does see the subtype instances. */
    @Test
    fun `polymorphic GetAll sees subtype instances`() {
        givenUserHierarchy()
        val engine = engine()
        withStoreTx { tx ->
            tx.createUser(BaseUser.CLASS, "base1")
            tx.createUser(User.CLASS, "user1")
            tx.createUser(Guest.CLASS, "guest1")
        }
        withStoreTx {
            assertNamesExactly(engine.queryGetAll(BaseUser.CLASS), "base1", "user1", "guest1")
        }
    }

    /** Control: a by-ids operand of the *exact* base type survives the intersect. */
    @Test
    fun `GetAll intersect by-ids keeps exact-type instance`() {
        givenUserHierarchy()
        val engine = engine()
        val base1Id = withStoreTx { tx -> tx.createUser(BaseUser.CLASS, "base1").id }
        withStoreTx { tx ->
            val ranked = byIds(tx, tx.getEntity(base1Id))
            val all = engine.queryGetAll(BaseUser.CLASS)
            assertNamesExactly(engine.intersect(all, ranked), "base1")
            assertNamesExactly(engine.intersect(ranked, all), "base1")
        }
    }

    /** The bug: a by-ids operand of a *subtype* is wrongly dropped from GetAll(Base) ∩ x. */
    @Test
    fun `GetAll intersect by-ids keeps subtype instance`() {
        givenUserHierarchy()
        val engine = engine()
        val user1Id = withStoreTx { tx -> tx.createUser(User.CLASS, "user1").id }
        withStoreTx { tx ->
            val ranked = byIds(tx, tx.getEntity(user1Id))
            val all = engine.queryGetAll(BaseUser.CLASS)
            // EXPECTED: "user1" (User is a subtype of BaseUser). ACTUAL (bug): empty.
            assertNamesExactly(engine.intersect(all, ranked), "user1")
            assertNamesExactly(engine.intersect(ranked, all), "user1")
        }
    }
}
