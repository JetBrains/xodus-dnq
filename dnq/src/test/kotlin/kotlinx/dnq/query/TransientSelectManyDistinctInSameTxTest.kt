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
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.TransientEntityIterable
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Test

/**
 * Regression test for `QueryEngine.selectDistinct`/`selectManyDistinct` on a non-DB-backed
 * `EntityIterable`; sibling of [TransientIntersectInSameTxTest], which covers the same defect in
 * the binary operations.
 *
 * Both functions used to call `it.selectDistinct(...)` / `it.selectManyDistinct(...)`
 * unconditionally when `it is EntityIterable`. A `TransientEntityIterable` (in-memory
 * `Set<TransientEntity>` wrapper) *is* an `EntityIterable` - it implements `EntityIterableWrapper`
 * - but both of its select* implementations throw `UnsupportedOperationException`.
 *
 * This is reachable from any before/after-flush listener that maps over the links of a snapshot
 * entity: `ReadonlyTransientEntity.getLinks` materialises the pre-change link state into a
 * `TransientEntityIterable`, so `snapshotEntity.someLinks.mapDistinct { ... }` /
 * `.flatMapDistinct { ... }` hits the throwing path.
 *
 * The fix gates on `isPersistent` (unwraps to a `YTDBEntityIterable`) exactly like `canAggregate`
 * does for the binary operations, routing everything else through `inMemorySelectDistinct` /
 * `inMemorySelectManyDistinct`.
 */
class TransientSelectManyDistinctInSameTxTest : DBTest() {

    class Item(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Item>()

        var name by xdRequiredStringProp()
    }

    class Box(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Box>()

        var label by xdStringProp()
        var main by xdLink0_1(Item)
        val items by xdLink0_N(Item)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Box, Item)
    }

    private fun XdEntity.transient(): TransientEntity = entity as TransientEntity

    private fun transientOf(vararg boxes: Box) =
        TransientEntityIterable(boxes.mapTo(LinkedHashSet()) { it.transient() })

    /**
     * `flatMapDistinct` over a `TransientEntityIterable`. Before the fix this throws
     * `UnsupportedOperationException` from `TransientEntityIterable.selectManyDistinct`.
     */
    @Test
    fun `flatMapDistinct through engine on TransientEntityIterable returns union of links`() {
        transactional {
            val i1 = Item.new { name = "i1" }
            val i2 = Item.new { name = "i2" }
            val i3 = Item.new { name = "i3" }

            val b1 = Box.new { label = "b1"; items.add(i1); items.add(i2) }
            val b2 = Box.new { label = "b2"; items.add(i2); items.add(i3) }
            // not part of the transient iterable - must not leak into the result
            Box.new { label = "b3"; items.add(Item.new { name = "i4" }) }

            val result = transientOf(b1, b2).asQuery(Box)
                .flatMapDistinct(Box::items)
                .toList()

            assertThat(result).containsExactly(i1, i2, i3)
        }
    }

    /**
     * `mapDistinct` (i.e. `QueryEngine.selectDistinct`) has the identical latent bug.
     */
    @Test
    fun `mapDistinct through engine on TransientEntityIterable returns distinct single links`() {
        transactional {
            val shared = Item.new { name = "shared" }
            val other = Item.new { name = "other" }

            val b1 = Box.new { label = "b1"; main = shared }
            val b2 = Box.new { label = "b2"; main = shared }
            val b3 = Box.new { label = "b3"; main = other }
            val b4 = Box.new { label = "b4" } // no link - must be skipped, not NPE

            val result = transientOf(b1, b2, b3, b4).asQuery(Box)
                .mapDistinct(Box::main)
                .toList()

            assertThat(result).containsExactly(shared, other)
        }
    }

    @Test
    fun `select on empty transient values returns empty`() {
        transactional {
            Box.new { label = "ignored"; items.add(Item.new { name = "i" }) }

            val empty = TransientEntityIterable(emptySet())

            assertThat(empty.asQuery(Box).flatMapDistinct(Box::items).toList()).isEmpty()
            assertThat(empty.asQuery(Box).mapDistinct(Box::main).toList()).isEmpty()
        }
    }

    /**
     * Guards the branch the fix narrows: a DB-backed query must still be pushed down to the store
     * and keep working across a flush.
     */
    @Test
    fun `persistent query still resolves select through the database`() {
        val (i1, i2) = transactional {
            val i1 = Item.new { name = "i1" }
            val i2 = Item.new { name = "i2" }
            Box.new { label = "b1"; main = i1; items.add(i1); items.add(i2) }
            Box.new { label = "b2"; main = i2; items.add(i2) }
            i1 to i2
        }

        transactional {
            assertThat(Box.all().flatMapDistinct(Box::items).toList()).containsExactly(i1, i2)
            assertThat(Box.all().mapDistinct(Box::main).toList()).containsExactly(i1, i2)
        }
    }
}
