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
package jetbrains.exodus.entitystore.youtrackdb.iterate

import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YTDBPolymorphicQueryTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = orientDbRule

    private fun givenUserHierarchy() {
        youTrackDb.withSession { session ->
            val baseClass = session.getOrCreateVertexClass(BaseUser.CLASS)
            listOf(
                session.getOrCreateVertexClass(User.CLASS),
                session.getOrCreateVertexClass(Guest.CLASS)
            ).forEach { it.addSuperClass(baseClass) }
        }
        withStoreTx { tx ->
            tx.createUser(BaseUser.CLASS, "base1")
            tx.createUser(User.CLASS, "user1")
            tx.createUser(Guest.CLASS, "guest1")
        }
    }

    @Test
    fun `non-polymorphic getAll returns exact type only`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            assertNamesExactly(result, "base1")
        }
    }

    @Test
    fun `polymorphic getAll returns subclasses (default)`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val result = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All)

            assertNamesExactly(result, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `flag survives single-operand chaining`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val afterSkip = nonPoly.skip(1) as YTDBEntityIterable
            assertFalse(afterSkip.polymorphic)

            val afterTake = nonPoly.take(10) as YTDBEntityIterable
            assertFalse(afterTake.polymorphic)

            val afterDistinct = nonPoly.distinct() as YTDBEntityIterable
            assertFalse(afterDistinct.polymorphic)

            val afterReverse = nonPoly.reverse() as YTDBEntityIterable
            assertFalse(afterReverse.polymorphic)

            // End-to-end: chained non-polymorphic query still returns exact type only
            assertNamesExactly(nonPoly.take(10), "base1")
        }
    }

    @Test
    fun `flag survives combination methods`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val nonPoly2 = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val intersected = nonPoly1.intersect(nonPoly2) as YTDBEntityIterable
            assertFalse(intersected.polymorphic)

            val unioned = nonPoly1.union(nonPoly2) as YTDBEntityIterable
            assertFalse(unioned.polymorphic)

            val minused = nonPoly1.minus(nonPoly2) as YTDBEntityIterable
            assertFalse(minused.polymorphic)

            val concatenated = nonPoly1.concat(nonPoly2) as YTDBEntityIterable
            assertFalse(concatenated.polymorphic)
        }
    }

    @Test
    fun `flag propagates through selectMany`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val selected = nonPoly.selectMany("someLink") as YTDBEntityIterable
            assertFalse(selected.polymorphic)
        }
    }

    @Test
    fun `default polymorphic flag is true`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All)

            assertTrue(poly.polymorphic)
        }
    }

    @Test
    fun `non-polymorphic query on leaf type returns same results as polymorphic`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = false)

            assertNamesExactly(poly, "user1")
            assertNamesExactly(nonPoly, "user1")
        }
    }

    @Test
    fun `findLinks propagates polymorphic flag from entities parameter`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val polyReceiver = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPolyEntities = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val result = polyReceiver.findLinks(nonPolyEntities, "someLink") as YTDBEntityIterable
            assertFalse(result.polymorphic, "findLinks should propagate entities-parameter flag, not receiver flag")
        }
    }

    @Test
    fun `flag preserved when combining with EMPTY`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val empty = YTDBEntityIterable.EMPTY

            assertFalse((nonPoly.union(empty) as YTDBEntityIterable).polymorphic)
            assertFalse((nonPoly.minus(empty) as YTDBEntityIterable).polymorphic)
            assertFalse((nonPoly.concat(empty) as YTDBEntityIterable).polymorphic)
            // intersect with EMPTY returns EMPTY itself (acceptable: empty result)
            assertTrue(nonPoly.intersect(empty) === YTDBEntityIterable.EMPTY)
        }
    }

    @Test
    fun `non-polymorphic distinct returns exact-type results end-to-end`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            // distinct() goes through modify(), producing a new iterable;
            // verify the flag propagates AND the traversal respects it
            assertNamesExactly(nonPoly.distinct(), "base1")
        }
    }
}
