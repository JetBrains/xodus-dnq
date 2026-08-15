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
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.events.Foo
import kotlinx.dnq.events.Goo
import kotlinx.dnq.query.drop
import kotlinx.dnq.query.take
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.getAddedLinks
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * XD-1292 / audit E1 — `TransientEntityIterable.skip`/`take` delegate to `Sequence.drop`/`Sequence.take`,
 * both of which open with `require(n >= 0)`, so a negative argument threw `IllegalArgumentException`
 * where Xodus's `EntityIterableBase` returns the receiver (`skip`) / the empty iterable (`take`).
 *
 * E1 is the third provenance of the same B2 defect (the other two are `YTDBEntityIterableImpl` — #10 —
 * and `dnq-query`'s `AbstractInMemoryEntityIterable` — B2 proper). Without it, DNQ-level `take(-1)` /
 * `drop(-1)` behaviour would still depend on where the query came from, which is exactly what B2 exists
 * to prevent.
 *
 * `dnq-transient-store` has no test source set, so this lives in `dnq`.
 *
 * **Assert contents/emptiness, never identity.** Not because identity could not hold — the clamped
 * raw-level rows below return the receiver (`skip`) and the shared `YTDBEntityIterable.EMPTY` singleton
 * (`take`), exactly as the raw YTDB layer does — but because what E1 fixes is the *contract*, i.e. which
 * elements come back instead of an `IllegalArgumentException`. Contents assertions pin that, and they
 * keep pinning it if the guards are ever reimplemented to allocate. At the DNQ level identity is not
 * even available: `XdQuery.take`/`drop` return a freshly wrapped query.
 */
class TransientEntityIterableClampTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(Foo, Goo)
    }

    /**
     * The raw-iterable rows: `getAddedLinks(linkName)` on the underlying [TransientEntity] hands back a
     * `TransientEntityIterable` directly (`TransientEntityImpl.getAddedLinks`), so `skip`/`take` here are
     * the edited methods themselves, with no wrapper in between.
     *
     * The `0` rows are the boundary controls: widening the existing `number == 0` guards to `number <= 0`
     * must be bit-identical at `0`, because those guards already returned `this` and
     * `YTDBEntityIterable.EMPTY`.
     */
    @Test
    fun `TransientEntityIterable clamps negative skip and take`() {
        val goo = transactional { Goo.new() }

        transactional {
            (0..2).forEach { i -> goo.content.add(Foo.new { intField = i }) }

            val added = (goo.entity as TransientEntity).getAddedLinks("content")
            assertTrue(added is TransientEntityIterable, "receiver must be a TransientEntityIterable")
            val allIds: List<EntityId> = added.map { it.id }
            assertEquals(3, allIds.size)

            // the defect: both of these threw IllegalArgumentException from `require(n >= 0)`
            assertTrue(added.take(-1).isEmpty)
            assertEquals(emptyList(), added.take(-1).map { it.id })
            assertEquals(allIds, added.skip(-1).map { it.id })

            // `0` boundary controls - unchanged by the widening
            assertTrue(added.take(0).isEmpty)
            assertEquals(allIds, added.skip(0).map { it.id })
        }
    }

    /**
     * The public-API row: `XdEntity.getAddedLinks(Goo::content)` inside a flush callback returns an
     * `XdQuery` over that same anonymous `TransientEntityIterable`, and `XdQuery.take`/`drop` route into
     * it through `XdQuery.operation` (`toEntityIterable` wraps it in a `PersistentEntityIterableWrapper`,
     * which *is* a `YTDBEntityIterable`, so the first arm matches and `unwrap()` yields the transient
     * iterable again). This is the shape a caller actually hits.
     *
     * The callback captures instead of asserting, and the captured throwable is asserted `null`
     * afterwards: a listener that threw would otherwise be indistinguishable from one that never ran,
     * and the run counter pins that it did run.
     */
    @Test
    fun `negative take and drop on an added-links query do not throw`() {
        val (f1, f2) = transactional { Pair(Foo.new { intField = 1 }, Foo.new { intField = 2 }) }
        val goo = transactional { Goo.new() }

        val runs = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val all = AtomicReference<List<Int>>(emptyList())
        val takeNegative = AtomicReference<List<Int>?>(null)
        val takeZero = AtomicReference<List<Int>?>(null)
        val dropNegative = AtomicReference<List<Int>?>(null)
        val dropZero = AtomicReference<List<Int>?>(null)

        Goo.onUpdate { old, _ ->
            runs.incrementAndGet()
            try {
                val query = old.getAddedLinks(Goo::content)
                all.set(query.toList().map { it.intField })
                takeNegative.set(query.take(-1).toList().map { it.intField })
                takeZero.set(query.take(0).toList().map { it.intField })
                dropNegative.set(query.drop(-1).toList().map { it.intField })
                dropZero.set(query.drop(0).toList().map { it.intField })
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        transactional {
            goo.content.add(f1)
            goo.content.add(f2)
        }

        assertEquals(1, runs.get(), "the update listener must have run")
        assertNull(failure.get(), "negative take/drop must not throw: ${failure.get()}")
        assertEquals(listOf(1, 2), all.get().sorted())
        assertEquals(emptyList(), takeNegative.get())
        assertEquals(emptyList(), takeZero.get())
        assertEquals(listOf(1, 2), dropNegative.get()?.sorted())
        assertEquals(listOf(1, 2), dropZero.get()?.sorted())
    }
}
