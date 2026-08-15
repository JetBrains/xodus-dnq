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
package kotlinx.dnq

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import kotlinx.dnq.link.OnDeletePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XD-1292 / audit BG18 — `SnapshotEntityIterable.concat` was a copy-paste of the `minus` override
 * immediately above it (`wrap(original.minus(getOriginal(right)))`), so `a.concat(b)` silently returned
 * `a \ b`: wrong in **both** directions, dropping every element of `a` that is also in `b` and never
 * returning any element of `b`.
 *
 * The class is `internal` and constructed only from `RemovedTransientEntity.getLinks`
 * (`RemovedTransientEntity.kt:253-258`), so no test can name it — the fixture must actually delete the
 * source entity and read the snapshot's links from a flush listener. `dnq-transient-store` has no test
 * source set, hence `dnq`.
 *
 * **`listener.check()` after the transaction is mandatory (TQ23):** `CallbackListener.onFlush` swallows
 * every `Throwable` the callback raises into `callbackErrors`, and only `check()` rethrows them (and
 * asserts the callback ran). A version of this test without it is green before *and* after the fix.
 *
 * **Both operands stay in the same iterable family**, in two shapes rather than one. No target is removed
 * in the transaction, so for the three snapshots that *have* targets
 * `YTDBVertexEntityRemoved.loadMultiple` takes its all-existing branch and yields a `ByIds`
 * `YTDBEntityIterableImpl`; the fourth, `noTargets`, has no `links` entry at all (the init guard drops
 * empty id sets, `YTDBVertexEntityRemoved.kt:80-82`) and `loadMultiple` short-circuits to
 * `YTDBEntityIterable.EMPTY`. Both shapes are YTDB, so every `original.concat(getOriginal(right))` here
 * is YTDB × YTDB. An `InMemoryEntityIterable` operand would throw before *and* after the fix on the three
 * `ByIds` rows (`operand()` → `asYTDBIterable()`, and `InMemoryEntityIterable.unwrap()` returns `this`) and
 * would silently succeed on the `EMPTY` row, since `EMPTY.concat(right)` returns `right` without touching
 * `operand()` — either way it would make this test look like a Track 04 dependency that does not exist.
 */
class SnapshotEntityIterableConcatTest : DBTest() {

    class ConcatTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ConcatTarget>()

        var name by xdRequiredStringProp()
    }

    class ConcatSource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ConcatSource>()

        var name by xdRequiredStringProp()
        val targets by xdLink0_N(ConcatTarget, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(ConcatSource, ConcatTarget)
    }

    private fun EntityIterable.names() = map { it.getProperty("name") as String }

    @Test
    fun `snapshot concat concatenates instead of subtracting`() {
        val listener = CallbackListener()
        store.addListener(listener)

        lateinit var overlapLeft: ConcatSource
        lateinit var overlapRight: ConcatSource
        lateinit var disjoint: ConcatSource
        lateinit var noTargets: ConcatSource

        store.transactional {
            val t1 = ConcatTarget.new { name = "t1" }
            val t2 = ConcatTarget.new { name = "t2" }
            val t3 = ConcatTarget.new { name = "t3" }
            val t4 = ConcatTarget.new { name = "t4" }

            overlapLeft = ConcatSource.new { name = "left"; targets.add(t1); targets.add(t2) }
            overlapRight = ConcatSource.new { name = "right"; targets.add(t2); targets.add(t3) }
            disjoint = ConcatSource.new { name = "disjoint"; targets.add(t4) }
            noTargets = ConcatSource.new { name = "noTargets" }
        }

        listener.onFlush { changes ->
            fun snapshotOf(source: ConcatSource): TransientEntity {
                val change = changes.find { it.transientEntity.id == source.entityId }!!
                return change.snapshotEntity.also { assertTrue(it.isRemoved, "source must be removed") }
            }

            val left = snapshotOf(overlapLeft).getLinks("targets")
            val right = snapshotOf(overlapRight).getLinks("targets")
            val other = snapshotOf(disjoint).getLinks("targets")
            val empty = snapshotOf(noTargets).getLinks("targets")

            // preconditions on the operands themselves
            assertEquals(setOf("t1", "t2"), left.names().toSet())
            assertEquals(setOf("t2", "t3"), right.names().toSet())
            assertEquals(setOf("t4"), other.names().toSet())
            assertTrue(empty.isEmpty, "the no-targets snapshot must yield the EMPTY iterable")

            // STRONGEST ROW - overlapping operands: fails in both directions before the fix, where
            // `left.minus(right)` returned just ["t1"].
            val overlapping = left.concat(right).names()
            assertTrue("t2" in overlapping, "an element of the left operand must survive being in the right too")
            assertTrue("t3" in overlapping, "elements of the right operand must appear at all")
            assertEquals(setOf("t1", "t2", "t3"), overlapping.toSet())
            assertEquals(4, overlapping.size, "concat preserves duplicates, unlike union")

            // disjoint operands: ["t1","t2"] before the fix, ["t1","t2","t4"] after
            assertEquals(setOf("t1", "t2", "t4"), left.concat(other).names().toSet())

            // engine-independent row: original is YTDBEntityIterable.EMPTY, so `EMPTY.minus(b)` was
            // EMPTY while `EMPTY.concat(b)` is b - no set arithmetic and no operand-family concerns.
            assertEquals(setOf("t2", "t3"), empty.concat(right).names().toSet())
        }

        store.transactional {
            overlapLeft.delete()
            overlapRight.delete()
            disjoint.delete()
            noTargets.delete()
        }
        listener.check()
    }
}
