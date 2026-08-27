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

import com.jetbrains.teamsys.dnq.database.TransientEntityIterable
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.events.Bar
import kotlinx.dnq.events.Foo
import kotlinx.dnq.events.Goo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * XD-1292 / audit #11-B — `TransientEntityIterable.indexOf`/`contains` compared objects
 * (`values.indexOf` / `values.contains` over a `Set<TransientEntity>`) instead of `EntityId`s.
 *
 * This is the most user-visible member of the #11 family: `XdQuery.contains`/`indexOf` route into it
 * (a `TransientEntityIterable` unwraps to itself, is not a `Collection` and not a
 * `YTDBEntityIterable`, so `XdQuery.contains` takes the `else` arm → `indexOf(entity) != -1`).
 *
 * Argument choice: the **underlying persistent `YTDBEntity`** of a member. That is the natural failing
 * direction here — `TransientEntityImpl.equals` rejects any non-`TransientEntity` outright, and its
 * `hashCode` (`id.hashCode() + persistentStore.hashCode()`) differs from `YTDBVertexEntity`'s
 * (`id.hashCode()`), so the set lookup cannot even land in the right bucket. No mock is needed (and
 * mockk is not on `dnq`'s test classpath).
 *
 * Three guards, all mandatory:
 *  1. the positive target sits at a **non-zero** index of the iterable's own `toList()`, asserted
 *     exactly (the index of a `Set`-backed iterable is iteration-order dependent, so it must be
 *     derived from the iterable, never from insertion order);
 *  2. a **cross-type collision** control — a `Bar` sharing the target `Foo`'s `localId` (per-class
 *     `localId` sequences make this the norm) must NOT be found;
 *  3. an **absent** same-type entity control.
 */
class TransientEntityIterableEqualityTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo, Goo, Bar)
    }

    @Test
    fun `TransientEntityIterable indexOf and contains compare entity ids`() {
        lateinit var goo: Goo
        lateinit var foos: List<Foo>
        lateinit var bars: List<Bar>
        lateinit var absentFoo: Foo
        transactional {
            goo = Goo.new()
            foos = (0..2).map { Foo.new { intField = it } }
            // Bars cover localIds 0..4, so whatever localId the target Foo has, a colliding Bar exists.
            bars = (0..4).map { Bar.new() }
            absentFoo = Foo.new()
        }

        transactional {
            foos.forEach { goo.content.add(it) }

            val added = (goo.entity as TransientEntity).getAddedLinks("content")
            // Precondition: we are really exercising TransientEntityIterable.
            assertTrue(added is TransientEntityIterable, "receiver must be a TransientEntityIterable")
            val members = added.toList()
            assertEquals(3, members.size)

            // GUARD 1 - positive row on a NON-FIRST element of the iterable's OWN order.
            val targetIndex = members.size - 1
            assertTrue(targetIndex > 0, "the positive row must not target index 0")
            val target = members[targetIndex] as TransientEntity
            val raw: Entity = target.entity // same EntityId, different Entity implementation
            assertEquals(target.id, raw.id)
            assertNotEquals<Class<*>>(target.javaClass, raw.javaClass)
            assertEquals(targetIndex, added.indexOf(raw))
            assertTrue(added.contains(raw))

            // GUARD 2 - cross-type collision: same localId, different typeId, must NOT be found.
            val colliderXd = bars.first { it.entityId.localId == target.id.localId }
            val collider = colliderXd.entity as TransientEntity
            assertNotEquals(collider.id.typeId, target.id.typeId, "collider must be of another type")
            assertEquals(collider.id.localId, target.id.localId, "collider must share the localId")
            assertFalse(added.contains(collider))
            assertEquals(-1, added.indexOf(collider))
            // ... and via its persistent form, the shape a foreign wrapper would arrive in
            assertFalse(added.contains(collider.entity))
            assertEquals(-1, added.indexOf(collider.entity))

            // GUARD 3 - an absent entity of the same type.
            val absent = absentFoo.entity as TransientEntity
            assertFalse(added.contains(absent))
            assertEquals(-1, added.indexOf(absent))
            assertFalse(added.contains(absent.entity))
            assertEquals(-1, added.indexOf(absent.entity))
        }
    }

    /**
     * BG33 — the rewritten `indexOf`/`contains` read `entity.id` eagerly, where the old
     * object-equality comparison never invoked the argument's `getId()`.
     * `TransientEntityImpl.getId()` throws `IllegalStateException("Cannot get wrapped persistent
     * entity")` when its private `id` field is `null`, so the question is whether the rewrite can turn
     * a `false` answer into an exception.
     *
     * This row pins the answer for the hardest case reachable through the public API: a brand-new,
     * not-yet-flushed entity, passed both as its `TransientEntity` and as its persistent form, to the
     * iterable that `XdQuery.contains`/`indexOf` actually routes into. It must be `false` / `-1`, not
     * a throw.
     *
     * Verdict: it does not throw. `TransientEntityImpl.id` is assigned by every constructor before the
     * object is published (`TransientSessionImpl.createEntity` sets `transientEntity.entity` before
     * returning; the persistent-entity constructor sets it directly) and is never reset to `null`, so
     * the `throwWrappedPersistentEntityUndefined()` branch of `getId()` is unreachable for a
     * normally-constructed entity. The only `getId()` implementations in the repo that always throw
     * are `UnsupportedOperationEntity` and `FakeTransientEntity`, both `internal` to `dnq` and confined
     * to the metadata-collecting `filter {}` / `search {}` closures — they are never elements of, nor
     * arguments to, an `EntityIterable`. Reading the argument's id eagerly is also exactly what the
     * contract authority does (Xodus `EntityIterableBase.indexOf:219` / `contains:230`).
     */
    @Test
    fun `contains answers false for a brand new unflushed argument`() {
        val goo = transactional { Goo.new() }
        transactional {
            goo.content.add(Foo.new { intField = 1 })
            goo.content.add(Foo.new { intField = 2 })
            val added = (goo.entity as TransientEntity).getAddedLinks("content")
            assertTrue(added is TransientEntityIterable)

            // created in THIS transaction, never flushed, and not linked to goo
            val fresh = Foo.new { intField = 99 }
            val freshTransient = fresh.entity as TransientEntity
            assertTrue(freshTransient.isNew, "precondition: the argument must be an unflushed entity")

            assertFalse(added.contains(freshTransient))
            assertEquals(-1, added.indexOf(freshTransient))
            assertFalse(added.contains(freshTransient.entity))
            assertEquals(-1, added.indexOf(freshTransient.entity))
        }
    }
}
