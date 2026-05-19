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
 * Regression test for JT-95690.
 *
 * `QueryEngine.query(instance, type, tree)` previously called `instance.intersect(...)`
 * unconditionally when `instance is EntityIterable`. If `instance` was a
 * `TransientEntityIterable` (in-memory `Set<TransientEntity>` wrapper), the call hit
 * `TransientEntityIterable.intersect` which throws `UnsupportedOperationException`.
 *
 * This is reachable from any post-flush listener that calls `.query(...)` on a
 * link collection containing entities created in the same transaction, since those
 * collections are materialised as `TransientEntityIterable` until the next flush.
 *
 * The fix mirrors the xodus-master `intersectNonTrees` routing: when the left side is
 * not DB-backed (not a `YTDBEntityIterable`), route through `inMemoryIntersect`, which
 * itself takes a DB-pushdown fast path (`GremlinQuery.ByIds(leftIds).intersect(right.query)`)
 * whenever the left side is smaller than 20 entries — typical for a transient changeset.
 */
class TransientIntersectInSameTxTest : DBTest() {

    class Box(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Box>()

        var label by xdStringProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Box)
    }

    private fun XdEntity.transient(): TransientEntity = entity as TransientEntity

    /**
     * Constructs an `XdQuery` whose underlying iterable is a `TransientEntityIterable`,
     * then runs `.query(predicate)` against it. Before the fix this throws
     * `UnsupportedOperationException` from `TransientEntityIterable.intersect`; after the
     * fix `QueryEngine.query` routes through `inMemoryIntersect`.
     */
    @Test
    fun `query through engine on TransientEntityIterable returns matching subset`() {
        transactional {
            val a = Box.new { label = "match" }
            val b = Box.new { label = "match" }
            val c = Box.new { label = "skip" }

            val transient: TransientEntityIterable =
                TransientEntityIterable(linkedSetOf(a.transient(), b.transient(), c.transient()))

            val result = transient.asQuery(Box)
                .query(Box::label eq "match")
                .toList()

            assertThat(result).containsExactly(a, b)
        }
    }

    @Test
    fun `query with non-matching predicate returns empty`() {
        transactional {
            val a = Box.new { label = "kept" }
            val b = Box.new { label = "filtered-out" }

            val transient = TransientEntityIterable(linkedSetOf(a.transient(), b.transient()))

            val result = transient.asQuery(Box)
                .query(Box::label eq "no-such-label")
                .toList()

            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `query on empty transient values returns empty`() {
        transactional {
            // a few persistent items so the engine has something to intersect against
            Box.new { label = "x" }
            Box.new { label = "y" }

            val transient = TransientEntityIterable(emptySet())

            val result = transient.asQuery(Box)
                .query(Box::label eq "x")
                .toList()

            assertThat(result).isEmpty()
        }
    }
}
