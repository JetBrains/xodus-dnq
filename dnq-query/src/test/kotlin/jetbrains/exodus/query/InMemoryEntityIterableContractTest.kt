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

import io.mockk.every
import io.mockk.mockk
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * XD-1292 / audit #11-A, #21-A and B2 — the contract defects of `dnq-query`'s in-memory iterable;
 * #11-A and #21-A are byte-for-byte copies of #11 and #21 one module away, B2 is #10's in-memory
 * mirror.
 */
class InMemoryEntityIterableContractTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    /**
     * #11-A — `AbstractInMemoryEntityIterable.indexOf`/`contains` delegated to the Kotlin `Iterable`
     * extensions, i.e. to `Any.equals`, so a foreign `Entity` implementation carrying a member's id
     * missed. Must compare `EntityId`s.
     *
     * Three guards, all mandatory:
     *  1. the positive target sits at a **non-zero** index, asserted exactly;
     *  2. a **cross-type collision** control (`localId` sequences are per class, so `board3` really
     *     shares `issue3`'s `localId`);
     *  3. an **absent** same-type entity control.
     */
    @Test
    fun `in-memory indexOf and contains compare entity ids`() {
        val test = givenTestCase()
        val absentIssue = youTrackDb.createIssue("absent")

        withStoreTx { tx ->
            val engine = QueryEngine(null, youTrackDb.store)
            val members: List<Entity> = listOf(test.issue1, test.issue2, test.issue3)
            val iterable = InMemoryEntityIterable(members, tx, engine)

            // GUARD 1 - positive row on a NON-FIRST element, exact index asserted.
            val targetIndex = members.size - 1
            val target = members[targetIndex]
            assertTrue(targetIndex > 0, "the positive row must not target index 0")
            val foreignWrapper = mockk<Entity> { every { id } returns target.id }
            assertEquals(targetIndex, iterable.indexOf(foreignWrapper))
            assertTrue(iterable.contains(foreignWrapper))

            // GUARD 2 - cross-type collision: same localId, different typeId, must NOT be found.
            val collider = listOf(test.board1, test.board2, test.board3)
                .first { it.id.localId == target.id.localId }
            assertNotEquals(collider.id.typeId, target.id.typeId, "collider must be of another type")
            assertEquals(collider.id.localId, target.id.localId, "collider must share the localId")
            assertFalse(iterable.contains(collider))
            assertEquals(-1, iterable.indexOf(collider))

            // GUARD 3 - an absent entity of the same type.
            assertFalse(iterable.contains(absentIssue))
            assertEquals(-1, iterable.indexOf(absentIssue))
        }
    }

    /**
     * PF1 — the `contains` fast path must survive, and must not shadow the id-based answer.
     *
     * `QueryEngine.inMemoryUnion` builds the backing iterable with Kotlin's `union`, i.e. a
     * `LinkedHashSet`, and `Set.asIterable()` is the identity — so a real production receiver is
     * hash-backed and its membership test must stay O(1). This row pins both halves: the hash probe
     * still answers same-object hits, and a *foreign wrapper* (which the probe necessarily misses,
     * since object equality is narrower than id equality) still falls through to the id scan.
     */
    @Test
    fun `in-memory contains keeps the hash fast path without losing the id fallback`() {
        val test = givenTestCase()
        val absentIssue = youTrackDb.createIssue("absent")

        withStoreTx { tx ->
            val engine = QueryEngine(null, youTrackDb.store)
            // exactly the shape inMemoryUnion produces
            val backing: Set<Entity> = linkedSetOf(test.issue1, test.issue2, test.issue3)
            val iterable = InMemoryEntityIterable(backing, tx, engine)

            // the fast path itself
            assertTrue(iterable.contains(test.issue3))
            assertFalse(iterable.contains(absentIssue))

            // ... and the id fallback behind it, which the hash probe cannot answer
            val foreignWrapper = mockk<Entity> { every { id } returns test.issue3.id }
            assertTrue(iterable.contains(foreignWrapper))
            assertEquals(2, iterable.indexOf(foreignWrapper))

            // cross-type collision must still be rejected by both paths
            val collider = listOf(test.board1, test.board2, test.board3)
                .first { it.id.localId == test.issue3.id.localId }
            assertFalse(iterable.contains(collider))
        }
    }

    /**
     * BG33 — the rewritten `indexOf`/`contains` read `entity.id` eagerly, where the old
     * `equals`-based comparison never touched the argument's id. `TransientEntityImpl.getId()` throws
     * `IllegalStateException("Cannot get wrapped persistent entity")` when no persistent entity is
     * bound, so the question is whether a caller can now get an exception where it used to get
     * `false`. This row pins the contract answer for the closest reachable case in this module: a
     * brand-new, not-yet-flushed entity used as the argument.
     *
     * Verdict: it does not throw — `TransientEntityImpl.id` is assigned by every constructor before
     * the object is published and is never reset to `null`. And an eager `entity.getId()` is what the
     * contract authority itself does: Xodus's `EntityIterableBase.indexOf` (`:219`) and `contains`
     * (`:230`) both read it unguarded, so swallowing an id-access failure here would be a divergence
     * from the reference, not a repair.
     */
    @Test
    fun `in-memory contains answers false for a brand new unflushed argument`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            val engine = QueryEngine(null, youTrackDb.store)
            val iterable = InMemoryEntityIterable(listOf(test.issue1, test.issue2), tx, engine)
            val fresh = tx.createIssue("created-in-this-tx-and-not-flushed")

            assertFalse(iterable.contains(fresh))
            assertEquals(-1, iterable.indexOf(fresh))
        }
    }

    /**
     * B2 — #10's in-memory mirror. `AbstractInMemoryEntityIterable.take` delegates to Kotlin's
     * `Iterable.take`, whose `require(n >= 0)` threw `IllegalArgumentException` for a negative
     * argument, where Xodus's `EntityIterableBase.take` returns the empty iterable. Only the `take`
     * half needed the clamp: `skip` routes through `InMemoryEntityIterator.skip`, whose `0..<number`
     * range is empty for negatives, so it already preserved every element — the third row is that
     * preservation control and passes before and after the fix.
     *
     * **Contents/emptiness only, never identity** (TQ35/TQ38): the clamp returns a *new*
     * `InMemoryEntityIterable(emptyList(), …)`, not `YTDBEntityIterable.EMPTY`, and the class overrides
     * no `equals`; `skip` likewise always builds a new iterable, so `=== iterable` never held.
     */
    @Test
    fun `in-memory take clamps negative arguments and skip preserves contents`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            val engine = QueryEngine(null, youTrackDb.store)
            val members: List<Entity> = listOf(test.issue1, test.issue2, test.issue3)
            val iterable = InMemoryEntityIterable(members, tx, engine)

            // the defect: this call threw IllegalArgumentException before the clamp
            assertTrue(iterable.take(-1).isEmpty)
            assertEquals(emptyList(), iterable.take(-1).toList())

            // `0` boundary control - untouched path, `Iterable.take(0)` is already emptyList()
            assertTrue(iterable.take(0).isEmpty)
            assertEquals(emptyList(), iterable.take(0).toList())

            // preservation control - already contract-correct, must stay so
            assertEquals(members.map { it.id }, iterable.skip(-1).toList().map { it.id })
        }
    }

    /**
     * #21-A — `InMemoryEntityIterator.dispose()` claimed `true` while holding nothing and while
     * `shouldBeDisposed()` said `false`. Both halves are asserted so the pair cannot drift apart.
     *
     * Deliberately kept as `false`/`false` rather than the throwing form used by the two transient
     * iterators: `dispose()` is routinely called in `finally` blocks that ignore its answer.
     */
    @Test
    fun `in-memory iterator is honestly non-disposable`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            val engine = QueryEngine(null, youTrackDb.store)
            val iterable = InMemoryEntityIterable(listOf(test.issue1, test.issue2), tx, engine)

            assertFalse(iterable.iterator().shouldBeDisposed())
            assertFalse(iterable.iterator().dispose())
        }
    }
}
