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
package jetbrains.exodus.entitystore.youtrackdb.query

import com.google.common.truth.Truth.assertWithMessage
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.PropEqual
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.Skip
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.Sort
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.SortDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.*
import org.junit.Test

/**
 * Exhaustive matrix of combineEfficient outcomes for every relevant left-type × right-type × operation
 * combination. Each cell records whether the result is a fused traversal, a sort-wrapped query,
 * or an Aggregate/UnionAll fallback.
 *
 * Outcome codes:
 *   Labeled(Where)        – condition fused into a single where-filtered traversal
 *   Labeled(AndThen)      – O7: condition appended directly to a FollowLink traversal
 *   Order(Labeled(FL))    – O4: two FollowLink sources merged, wrapped with dedup
 *   SortBy(...)           – O3: left sort preserved after combining inner queries
 *   Order(UnionAll)       – union fallback: combineEfficient returned null
 *   Aggregate             – intersect/difference fallback: combineEfficient returned null
 *   ByIds                 – both sides were ByIds, combined at the id-set level
 */
class GremlinQueryCombineMatrixTest {

    // --- Fixtures ---

    private val rid1 = RID.of(30, 1)
    private val rid2 = RID.of(30, 2)

    // Labeled(Where) — standard extractable condition
    private val cond  = Labeled(GremlinQuery.Where.of(PropEqual("status", "open")),     "Issue")
    private val cond2 = Labeled(GremlinQuery.Where.of(PropEqual("priority", "high")),   "Issue")
    private val condSameProp = Labeled(GremlinQuery.Where.of(PropEqual("status", "resolved")), "Issue")
    private val condDiffLabel = Labeled(GremlinQuery.Where.of(PropEqual("name", "X")), "Project")

    // Labeled(FollowLink) — link traversals
    private val srcA = Labeled(GremlinQuery.Where.of(PropEqual("key", "A")), "Project")
    private val srcB = Labeled(GremlinQuery.Where.of(PropEqual("key", "B")), "Project")
    private val link      = Labeled(FollowLink(srcA, LinkDirection.IN, "project"), "Issue") // same link as link2
    private val link2     = Labeled(FollowLink(srcB, LinkDirection.IN, "project"), "Issue") // same link name, different source
    private val linkDiff  = Labeled(FollowLink(srcA, LinkDirection.IN, "sprint"),  "Issue") // different link name

    // ByIds
    private val byids  = ByIds(listOf(rid1, rid2))
    private val byids2 = ByIds(listOf(rid2))

    // SortBy
    private val sorted = SortBy(cond, Sort(Sort.ByProp("name"), SortDirection.ASC))

    // Slice
    private val sliced = cond.then(Skip(1))   // becomes Slice(cond, Skip(1))

    // --- Outcome descriptor ---

    private fun outcome(q: GremlinQuery): String = when (q) {
        is Aggregate            -> "Aggregate"
        is ByIds                -> "ByIds"
        is GremlinQuery.Where   -> "Where"
        is Labeled              -> "Labeled(${innerName(q.inner)})"
        is SortBy               -> "Sort(${outcome(q.inner)})"
        is Order                -> when (q.orderBlock) {
            GremlinBlock.Dedup   -> "Dedup(${innerName(q.inner)})"
            GremlinBlock.Reverse -> "Reverse(${innerName(q.inner)})"
            else                 -> "Order(${innerName(q.inner)})"
        }
        is Slice                -> "Slice"
        is UnionAll             -> "UnionAll"
        is GremlinQuery.AndThen -> "AndThen"
        is FollowLink           -> "FollowLink"
        is AggregateNoOrder     -> "AggregateNoOrder"
        is ReversedOrder        -> "Reverse"
        is NestedCondition      -> "NestedCondition"
    }

    private fun innerName(q: GremlinQuery): String = when (q) {
        is GremlinQuery.Where   -> "Where"
        is GremlinQuery.AndThen -> "AndThen"
        is FollowLink           -> "FL"
        is UnionAll             -> "UnionAll"
        is Labeled              -> "Labeled(${innerName(q.inner)})"
        else                    -> q.shortName()
    }

    private fun check(left: GremlinQuery, op: String, right: GremlinQuery, expected: String) {
        val result = when (op) {
            "u" -> left.union(right)
            "i" -> left.intersect(right)
            "d" -> left.difference(right)
            else -> error("unknown op")
        }
        assertWithMessage("(${outcome(left)}) $op (${outcome(right)})")
            .that(outcome(result))
            .isEqualTo(expected)
    }

    // =========================================================================
    // Labeled(Where) × Labeled(Where)
    // =========================================================================

    @Test
    fun `Labeled(Where) x Labeled(Where) — same label`() {
        // Different properties: Or/And/And(Not) combined into a single Where
        check(cond, "u", cond2,         "Labeled(Where)")
        check(cond, "i", cond2,         "Labeled(Where)")
        check(cond, "d", cond2,         "Labeled(Where)")

        // Same property on both sides: O9 coalesces union into PropWithin
        check(cond, "u", condSameProp,  "Labeled(Where)")
    }

    @Test
    fun `Labeled(Where) x Labeled(Where) — different labels fall back to UnionAll`() {
        // combineEfficient detects label mismatch → returns null → union fallback
        check(cond, "u", condDiffLabel, "Dedup(UnionAll)")
        check(cond, "i", condDiffLabel, "Aggregate")
        check(cond, "d", condDiffLabel, "Aggregate")
    }

    // =========================================================================
    // Labeled(Where) × Labeled(FollowLink)
    // =========================================================================

    @Test
    fun `Labeled(Where) x Labeled(FL)`() {
        // union: O11 fires — builds Or(cond, inverseLinkPredicate) → single Labeled(Where)
        check(cond, "u", link, "Labeled(Where)")

        // intersect: O7 fires — other is FL, condBlock extracted from this
        check(cond, "i", link, "Labeled(AndThen)")

        // difference: O11 fires — builds And(cond, Not(inverseLinkPredicate)) → single Labeled(Where)
        check(cond, "d", link, "Labeled(Where)")
    }

    // =========================================================================
    // Labeled(FollowLink) × Labeled(Where)
    // =========================================================================

    @Test
    fun `Labeled(FL) x Labeled(Where)`() {
        // union: extractCondition(Labeled(FL)) = null → fallback
        check(link, "u", cond, "Dedup(UnionAll)")

        // intersect: O7 fires — this is FL, condBlock extracted from other
        check(link, "i", cond, "Labeled(AndThen)")

        // difference: O7 fires — this is FL, Not(condBlock) appended
        check(link, "d", cond, "Labeled(AndThen)")
    }

    // =========================================================================
    // Labeled(FollowLink) × Labeled(FollowLink)
    // =========================================================================

    @Test
    fun `Labeled(FL) x Labeled(FL)`() {
        // union, same link name + direction: O4 merges sources → Order(Labeled(FL))
        check(link, "u", link2,    "Dedup(Labeled(FL))")

        // union, different link name: O4 misses → fallback
        check(link, "u", linkDiff, "Dedup(UnionAll)")

        // intersect/difference: both sides are FL, neither is an extractable condition
        check(link, "i", link,     "Aggregate")
        check(link, "d", link,     "Aggregate")
        check(link, "i", link2,    "Aggregate")
        check(link, "d", link2,    "Aggregate")
    }

    // =========================================================================
    // ByIds × ByIds
    // =========================================================================

    @Test
    fun `ByIds x ByIds — combined at id-set level`() {
        check(byids, "u", byids2, "ByIds")
        check(byids, "i", byids2, "ByIds")
        check(byids, "d", byids2, "ByIds")
    }

    // =========================================================================
    // ByIds × Labeled(Where)
    // =========================================================================

    @Test
    fun `ByIds x Labeled(Where)`() {
        // ByIds.asBlock() = IdWithin → extractable; combined with the condition
        check(byids, "u", cond, "Labeled(Where)")
        check(byids, "i", cond, "Labeled(Where)")
        check(byids, "d", cond, "Labeled(Where)")

        // Symmetric
        check(cond, "u", byids, "Labeled(Where)")
        check(cond, "i", byids, "Labeled(Where)")
        check(cond, "d", byids, "Labeled(Where)")
    }

    // =========================================================================
    // ByIds × Labeled(FollowLink)
    // =========================================================================

    @Test
    fun `ByIds x Labeled(FL)`() {
        // union: O11 fires — IdWithin is extractable; builds Or(IdWithin, inverseLinkPredicate)
        check(byids, "u", link, "Labeled(Where)")

        // intersect: O7 fires — other is FL, condBlock = IdWithin
        check(byids, "i", link, "Labeled(AndThen)")

        // difference: O11 fires — builds And(IdWithin, Not(inverseLinkPredicate))
        check(byids, "d", link, "Labeled(Where)")

        // Symmetric for FL × ByIds
        check(link, "u", byids, "Dedup(UnionAll)")
        check(link, "i", byids, "Labeled(AndThen)")
        check(link, "d", byids, "Labeled(AndThen)")  // O7: this=FL, Not(IdWithin) appended
    }

    // =========================================================================
    // SortBy × others — O3 passthrough
    // =========================================================================

    @Test
    fun `SortBy x Labeled(Where) — O3 sort handling`() {
        // union: O3 strips left sort (sorting one side doesn't define union order)
        check(sorted, "u", cond, "Labeled(Where)")

        // intersect/difference: O3 preserves left sort
        check(sorted, "i", cond, "Sort(Labeled(Where))")
        check(sorted, "d", cond, "Sort(Labeled(Where))")

        // Symmetric: right sort is always stripped and not re-wrapped
        check(cond, "u", sorted, "Labeled(Where)")
        check(cond, "i", sorted, "Labeled(Where)")
        check(cond, "d", sorted, "Labeled(Where)")
    }

    @Test
    fun `SortBy x SortBy — both sorts handled by O3`() {
        // union: both sorts stripped
        check(sorted, "u", sorted, "Labeled(Where)")

        // intersect/difference: left sort preserved, right stripped
        check(sorted, "i", sorted, "Sort(Labeled(Where))")
        check(sorted, "d", sorted, "Sort(Labeled(Where))")
    }

    @Test
    fun `SortBy x Labeled(FL) — O3 delegates to inner, which then uses O7 or O11`() {
        // union: O3 recurses, inner.union(FL) → O11 fires → Labeled(Where); O3 strips sort for union
        check(sorted, "u", link, "Labeled(Where)")

        // intersect: O3 recurses, inner.intersect(FL) → O7 fires → Labeled(AndThen); O3 re-wraps with sort
        check(sorted, "i", link, "Sort(Labeled(AndThen))")

        // difference: O3 recurses, inner.difference(FL) → O11 fires → Labeled(Where); O3 re-wraps with sort
        check(sorted, "d", link, "Sort(Labeled(Where))")
    }

    // =========================================================================
    // Slice × others — never combines efficiently
    // =========================================================================

    @Test
    fun `Slice never combines efficiently`() {
        // union fallback for all
        check(sliced, "u", cond,  "Dedup(UnionAll)")
        check(cond,   "u", sliced,"Dedup(UnionAll)")

        // intersect/difference: Aggregate
        check(sliced, "i", cond,  "Aggregate")
        check(sliced, "d", cond,  "Aggregate")
        check(cond,   "i", sliced,"Aggregate")
        check(cond,   "d", sliced,"Aggregate")
    }
}
