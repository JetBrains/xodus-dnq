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
import kotlin.test.assertFailsWith
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
            // End-to-end: disjoint types produce empty intersection
            assertEquals(0, intersected.count())

            val unioned = nonPoly1.union(nonPoly2) as YTDBEntityIterable
            assertFalse(unioned.polymorphic)

            val minused = nonPoly1.minus(nonPoly2) as YTDBEntityIterable
            assertFalse(minused.polymorphic)
            // End-to-end: minus of disjoint non-polymorphic iterables preserves left operand results
            assertNamesExactly(minused, "base1")

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

    // --- Combination flag validation tests ---

    @Test
    fun `mixed-flag intersect throws in both directions with descriptive message`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.intersect(nonPoly) }
            assertTrue(ex1.message!!.startsWith("Cannot combine a polymorphic iterable with a non-polymorphic"))

            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.intersect(poly) }
            assertTrue(ex2.message!!.startsWith("Cannot combine a non-polymorphic iterable with a polymorphic"))
        }
    }

    @Test
    fun `mixed-flag union throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.union(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.union(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `mixed-flag minus throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.minus(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.minus(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `mixed-flag concat throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val ex1 = assertFailsWith<IllegalArgumentException> { poly.concat(nonPoly) }
            assertTrue(ex1.message!!.contains("polymorphic flag"))
            val ex2 = assertFailsWith<IllegalArgumentException> { nonPoly.concat(poly) }
            assertTrue(ex2.message!!.contains("polymorphic flag"))
        }
    }

    @Test
    fun `mixed-flag intersectSavingOrder throws`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)

            assertFailsWith<IllegalArgumentException> { poly.intersectSavingOrder(nonPoly) }
            assertFailsWith<IllegalArgumentException> { nonPoly.intersectSavingOrder(poly) }
        }
    }

    @Test
    fun `same-flag polymorphic true combination succeeds with correct results`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val poly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = true)
            val poly2 = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = true)

            val unioned = poly1.union(poly2) as YTDBEntityIterable
            assertTrue(unioned.polymorphic)
            assertNamesExactly(unioned, "base1", "user1", "guest1")

            val intersected = poly1.intersect(poly2) as YTDBEntityIterable
            assertTrue(intersected.polymorphic)

            val minused = poly1.minus(poly2) as YTDBEntityIterable
            assertTrue(minused.polymorphic)

            val concatenated = poly1.concat(poly2) as YTDBEntityIterable
            assertTrue(concatenated.polymorphic)
        }
    }

    @Test
    fun `EMPTY sentinel has polymorphic true by default`() {
        assertTrue(YTDBEntityIterable.EMPTY.polymorphic)
    }

    @Test
    fun `EMPTY as left operand preserves right operand flag`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val empty = YTDBEntityIterable.EMPTY

            // EMPTY.union returns right directly — flag preserved
            val unionResult = empty.union(nonPoly)
            assertFalse((unionResult as YTDBEntityIterable).polymorphic)
            assertNamesExactly(unionResult, "base1")

            // EMPTY.concat returns right directly — flag preserved
            assertFalse((empty.concat(nonPoly) as YTDBEntityIterable).polymorphic)

            // EMPTY.intersect returns EMPTY itself
            assertTrue(empty.intersect(nonPoly) === YTDBEntityIterable.EMPTY)

            // EMPTY.minus returns EMPTY itself
            assertTrue(empty.minus(nonPoly) === YTDBEntityIterable.EMPTY)
        }
    }

    @Test
    fun `chained combination preserves flag and validates correctly`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val nonPoly2 = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val nonPoly3 = YTDBEntityIterable.where(Guest.CLASS, tx, GremlinBlock.All, polymorphic = false)

            val combined = nonPoly1.union(nonPoly2).union(nonPoly3)
            assertFalse((combined as YTDBEntityIterable).polymorphic)
            assertNamesExactly(combined, "base1", "user1", "guest1")
        }
    }

    @Test
    fun `chained combination result rejects mismatched flag`() {
        givenUserHierarchy()

        withStoreTx { tx ->
            val nonPoly1 = YTDBEntityIterable.where(BaseUser.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val nonPoly2 = YTDBEntityIterable.where(User.CLASS, tx, GremlinBlock.All, polymorphic = false)
            val poly = YTDBEntityIterable.where(Guest.CLASS, tx, GremlinBlock.All, polymorphic = true)

            val combined = nonPoly1.union(nonPoly2) // polymorphic=false
            assertFailsWith<IllegalArgumentException> { combined.intersect(poly) }
        }
    }
}
