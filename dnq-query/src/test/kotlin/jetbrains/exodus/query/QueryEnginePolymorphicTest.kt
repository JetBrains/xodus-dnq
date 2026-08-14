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

import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import jetbrains.exodus.query.metadata.entity
import jetbrains.exodus.query.metadata.oModel
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryEnginePolymorphicTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    private fun givenEngine(): QueryEngine {
        // XD-1283: the schema is applied BEFORE the data is populated, because index creation
        // is transactional by default and YTDB-1064 rejects an in-tx index over a populated
        // class. This test's subject is query semantics, so the fixture order is incidental:
        // OUsersWithInheritanceTestCase early-returns on the classes prepare() already created
        // and its rows carry localEntityId in either order.
        val model = oModel(youTrackDb.provider) {
            entity(BaseUser.CLASS)
            entity(User.CLASS, BaseUser.CLASS)
            entity(Guest.CLASS, BaseUser.CLASS)
            entity(Admin.CLASS, BaseUser.CLASS)
            entity(Agent.CLASS, BaseUser.CLASS)
        }.apply { prepare() }

        // OUsersWithInheritanceTestCase sets up: BaseUser -> {User, Guest, Admin, Agent}
        // with entities: u1, u2, ag1, ag2, ad1, ad2, g1
        OUsersWithInheritanceTestCase(youTrackDb)

        return QueryEngine(model, youTrackDb.store).also {
            it.sortEngine = SortEngine()
        }
    }

    @Test
    fun `non-polymorphic queryGetAll on base type excludes subtypes`() {
        val engine = givenEngine()

        withStoreTx {
            // BaseUser has no direct instances — only subtypes exist
            val result = engine.queryGetAll(BaseUser.CLASS, polymorphic = false)
            assertEquals(0L, result.count())
        }
    }

    @Test
    fun `non-polymorphic queryGetAll on leaf type returns its instances`() {
        val engine = givenEngine()

        withStoreTx {
            val result = engine.queryGetAll(User.CLASS, polymorphic = false)
            assertNamesExactly(result, "u1", "u2")
        }
    }

    @Test
    fun `default queryGetAll returns all subtypes`() {
        val engine = givenEngine()

        withStoreTx {
            val result = engine.queryGetAll(BaseUser.CLASS)
            assertNamesExactly(result, "u1", "u2", "ag1", "ag2", "ad1", "ad2", "g1")
        }
    }

    @Test
    fun `queryGetAll for unknown entity type returns EMPTY`() {
        val engine = givenEngine()

        withStoreTx {
            val result = engine.queryGetAll("NoSuchType", polymorphic = false)
            assertTrue(result === YTDBEntityIterable.EMPTY)

            val polyResult = engine.queryGetAll("NoSuchType")
            assertTrue(polyResult === YTDBEntityIterable.EMPTY)
        }
    }

    @Test
    fun `inMemoryIntersect runs purely in memory and returns matching entities`() {
        val engine = givenEngine()

        withStoreTx { tx ->
            // XD-1275: inMemoryIntersect no longer issues a GremlinQuery.ByIds DB query for
            // small operands — it always intersects in memory and yields an InMemoryEntityIterable.
            val nonPoly = YTDBEntityIterable.where(
                User.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false
            )
            val inMemory = nonPoly.toList()

            val result = engine.inMemoryIntersect(nonPoly, inMemory)
            assertFalse(result is YTDBEntityIterable)
            assertNamesExactly(result, "u1", "u2")
        }
    }
}
