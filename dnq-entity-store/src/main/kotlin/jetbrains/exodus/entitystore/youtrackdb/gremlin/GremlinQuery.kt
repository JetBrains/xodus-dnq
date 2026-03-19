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
package jetbrains.exodus.entitystore.youtrackdb.gremlin

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.SortDirection
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__

sealed class GremlinQuery {

    companion object {
        @JvmStatic
        val all = Where(GremlinBlock.All)

        @JvmStatic
        val none = Where(GremlinBlock.None)
    }

    fun then(block: GremlinBlock): GremlinQuery = when {
        block is GremlinBlock.All -> this
        block.type == BlockType.SLICE -> Slice.of(this, block)
        block is GremlinBlock.Sort -> SortBy.of(this, block)
        block.type == BlockType.ORDER -> Order.of(this, block)
        block is GremlinBlock.HasLabel -> Labeled.of(this, block.entityType)
        block is GremlinBlock.InLink -> FollowLink(this, LinkDirection.IN, block.linkName)
        block is GremlinBlock.OutLink -> FollowLink(this, LinkDirection.OUT, block.linkName)

        block is GremlinBlock.AndThen -> throw IllegalArgumentException("Nested andThen is not allowed")
        block is GremlinBlock.Reverse -> when (this) {
            is SortBy -> this.reverseOrder()
            is ReversedOrder -> this.inner
            else -> ReversedOrder(this)
        }

        else -> when (this) {
            is Where -> Where(this.block.andThen(block))
            is ByIds -> Where(this.asBlock().andThen(block))
            is Labeled -> Labeled(this.inner.then(block), this.label)
            is AndThen -> AndThen(this.inner, this.block.andThen(block))
            else -> AndThen(this, block)
        }
    }

    fun start(gs: GraphTraversalSource): YT {
        if (GremlinQueryCollector.enabled) GremlinQueryCollector.record(GremlinQueryShape.of(this))
        return startTraversal(gs).traversal
    }

    abstract fun shortName(): String

    protected data class YTBuilder(val traversal: YT, val counter: Int) {
        companion object {
            fun of(t: GraphTraversal<*, *>, block: GremlinBlock? = null, counter: Int = 0) = YTBuilder(
                block?.traverse(t.asYT()) ?: t.asYT(),
                counter
            )
        }

        fun combine(block: GremlinBlock) = YTBuilder(block.traverse(traversal), counter)
        fun combine(block: (YT) -> YT) = YTBuilder(block(traversal), counter)
    }

    protected abstract fun startTraversal(gs: GraphTraversalSource): YTBuilder
    protected abstract fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder

    sealed class ConditionCombiner(
        val combineBlocks: (GremlinBlock, GremlinBlock) -> GremlinBlock,
        val combineIds: (List<RID>, List<RID>) -> List<RID>
    ) {
        data object Intersect : ConditionCombiner(
            combineBlocks = { a, b ->
                when {
                    a is GremlinBlock.None || b is GremlinBlock.None -> GremlinBlock.None
                    a is GremlinBlock.All -> b
                    b is GremlinBlock.All -> a
                    a == b -> a
                    else -> GremlinBlock.And(a, b)
                }
            },
            combineIds = { a, b -> a.filter(b::contains) }
        )

        data object Union : ConditionCombiner(
            combineBlocks = { a, b ->
                when {
                    a is GremlinBlock.None -> b
                    b is GremlinBlock.None -> a
                    a is GremlinBlock.All || b is GremlinBlock.All -> GremlinBlock.All
                    a == b -> a
                    else -> GremlinBlock.Or(a, b)
                }
            },
            combineIds = { a, b -> a + b.filter { !a.contains(it) } }
        )

        data object Difference : ConditionCombiner(
            combineBlocks = { a, b ->
                when {
                    a is GremlinBlock.None -> GremlinBlock.None
                    a is GremlinBlock.All -> GremlinBlock.Not(b)
                    b is GremlinBlock.All -> GremlinBlock.None
                    b is GremlinBlock.None -> a
                    a == b -> GremlinBlock.None
                    else -> GremlinBlock.And(a, GremlinBlock.Not(b))
                }
            },
            combineIds = { a, b -> a.filter { !b.contains(it) } }
        )

    }

    private fun combineEfficient(
        other: GremlinQuery,
        condCombiner: ConditionCombiner,
    ): GremlinQuery? {
        // O3: SortBy passthrough — strip sort wrappers, combine inner queries, re-wrap.
        // The right-side sort is always irrelevant for set operations.
        // For intersect/difference: the result is a filtered subset of `this`, so `this`'s sort is
        // preserved by re-wrapping the combined query.
        // For union: sorting one operand does NOT define the sort of their union — B's results should
        // not be sorted by A's key. Strip the sort and return the combined query unsorted.
        if (this is SortBy) {
            val otherInner = if (other is SortBy) other.inner else other
            val combined = this.inner.combineEfficient(otherInner, condCombiner) ?: return null
            return if (condCombiner is ConditionCombiner.Union) combined
                   else SortBy(combined, this.sortBlock)
        }

        // Strip right-side sort and retry
        if (other is SortBy) {
            return this.combineEfficient(other.inner, condCombiner)
        }

        // O17: Order(Dedup) transparency — strip Dedup wrapper, combine inner queries, re-wrap.
        // flatMapDistinct wraps its FollowLink result in Order(Dedup) for correct deduplication.
        // This wrapper prevents O4/O7/O16 from firing on the DSL path. Strip it, attempt the
        // combination on the inner query, and re-wrap the result with Dedup.
        // Also strips the right-side Order(Dedup) for symmetric union/intersect where both sides
        // are flatMapDistinct results.
        // When the inner combination itself returns Order(Dedup) (e.g. the Labeled O4 path adds
        // Dedup directly), return that result as-is to avoid double-wrapping.
        if (this is Order && this.orderBlock === GremlinBlock.Dedup) {
            val otherInner = if (other is Order && other.orderBlock === GremlinBlock.Dedup) other.inner else other
            val innerResult = this.inner.combineEfficient(otherInner, condCombiner)
            if (innerResult != null) {
                return if (innerResult is Order && innerResult.orderBlock === GremlinBlock.Dedup)
                    innerResult  // inner path (e.g. O4 Labeled branch) already added Dedup
                else
                    Order(innerResult, GremlinBlock.Dedup)
            }
        }
        // O17 symmetric: for commutative intersect, also try when only the right side is Order(Dedup).
        if (condCombiner is ConditionCombiner.Intersect &&
            other is Order && other.orderBlock === GremlinBlock.Dedup) {
            val innerResult = other.inner.combineEfficient(this, condCombiner)
            if (innerResult != null) {
                return if (innerResult is Order && innerResult.orderBlock === GremlinBlock.Dedup)
                    innerResult
                else
                    Order(innerResult, GremlinBlock.Dedup)
            }
        }
        // O17 right-difference: strip Dedup from the right side of a difference.
        // condition \ Dedup(FL)  ≡  condition \ FL — dedup changes cardinality but not the
        // distinct vertex set, and difference is a set operation. Safe to delegate without Dedup.
        if (condCombiner is ConditionCombiner.Difference &&
            other is Order && other.orderBlock === GremlinBlock.Dedup) {
            return this.combineEfficient(other.inner, condCombiner)
        }

        // O4: FollowLink union shortcut — merge source queries, re-wrap with the shared link traversal
        if (this is Labeled && other is Labeled && this.label == other.label &&
            condCombiner is ConditionCombiner.Union) {
            val thisLink = this.inner as? FollowLink
            val otherLink = other.inner as? FollowLink
            if (thisLink != null && otherLink != null &&
                thisLink.direction == otherLink.direction &&
                thisLink.linkName == otherLink.linkName) {
                // Dedup is needed: multiple source vertices can reach the same target via separate edges.
                return Labeled(
                    FollowLink(thisLink.inner.union(otherLink.inner), thisLink.direction, thisLink.linkName),
                    this.label
                ).then(GremlinBlock.Dedup)
            }
        }

        // O4 extension: bare FollowLink × bare FollowLink (reached via O17 delegation).
        // When O17 strips Order(Dedup) from both sides, the inners are bare FollowLinks.
        // Merge their source queries; O17 adds Dedup when re-wrapping.
        if (this is FollowLink && other is FollowLink &&
            condCombiner is ConditionCombiner.Union &&
            this.direction == other.direction &&
            this.linkName == other.linkName) {
            return FollowLink(this.inner.union(other.inner), this.direction, this.linkName)
        }

        fun extractLabel(q: GremlinQuery): String? = if (q is Labeled) q.label else null
        // O19: extend extractCondition to handle Labeled(Labeled(Where(All), T1), T2).
        // This pattern arises when a query targets a class-hierarchy subtype — the inner label T1
        // is treated as an additional HasLabel filter. Only the Where(All) inner case is handled;
        // non-trivial inner conditions fall through to null (Aggregate) to avoid composing
        // GremlinBlock.AndThen which is illegal in the then() dispatch path.
        fun extractCondition(q: GremlinQuery): GremlinBlock? =
            if (q is Labeled && q.inner is Condition) q.inner.asBlock()
            else if (q is Labeled && q.inner is Labeled && q.inner.inner is Condition &&
                     (q.inner.inner as Condition).asBlock() is GremlinBlock.All)
                GremlinBlock.HasLabel(q.inner.label)
            else if (q is Condition) q.asBlock()
            else null

        val thisLabel = extractLabel(this)
        val otherLabel = extractLabel(other)

        if (thisLabel != null && otherLabel != null && thisLabel != otherLabel) {
            return null
        }

        if (this is ByIds && other is ByIds) {
            return ByIds(condCombiner.combineIds(this.ids, other.ids))
        }

        // O7: FollowLink × Condition fusion — avoids Aggregate for FollowLink ∩/\ Condition.
        // When one side is Labeled(FollowLink, T) or bare FollowLink (reached via O17 delegation)
        // and the other has an extractable condition, append the condition directly to the
        // FollowLink traversal. For difference, wrap the condition in Not first.
        // Symmetric for intersect (either side can be the link); for difference only `this` can
        // be the link side (condition \ FollowLink is not safe to rewrite this way).
        if (condCombiner is ConditionCombiner.Intersect || condCombiner is ConditionCombiner.Difference) {
            if (this is FollowLink || (this is Labeled && this.inner is FollowLink)) {
                val condBlock = extractCondition(other)
                if (condBlock != null) {
                    val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                    val base = if (this is Labeled) Labeled(this.inner.then(appended), this.label)
                               else (this as FollowLink).then(appended)
                    // O19 label propagation: if `other` is Labeled(Labeled(Condition, T1), T2),
                    // extractCondition returns HasLabel(T1) but the outer T2 label is not part of
                    // the condition block — apply it explicitly to the result.
                    val extraLabel = if (other is Labeled && other.inner is Labeled) extractLabel(other) else null
                    return if (extraLabel != null) Labeled.of(base, extraLabel) else base
                }
            }
            if (condCombiner is ConditionCombiner.Intersect &&
                (other is FollowLink || (other is Labeled && other.inner is FollowLink))) {
                val condBlock = extractCondition(this)
                if (condBlock != null) {
                    val base = if (other is Labeled) Labeled(other.inner.then(condBlock), other.label)
                               else (other as FollowLink).then(condBlock)
                    val extraLabel = if (this is Labeled && this.inner is Labeled) extractLabel(this) else null
                    return if (extraLabel != null) Labeled.of(base, extraLabel) else base
                }
            }
        }

        // O16: Chained O7 fusion — Labeled(AndThen(FollowLink, cond1), T) OP cond2.
        // When `this` is the result of a prior O7 fusion (an AndThen chain rooted at FollowLink),
        // extend the existing chain with the new condition instead of falling to Aggregate.
        // Also handles bare AndThen(FollowLink, ...) produced after O17 delegation.
        if (condCombiner is ConditionCombiner.Intersect || condCombiner is ConditionCombiner.Difference) {
            if (this is Labeled) {
                val andThen = this.inner as? AndThen
                if (andThen != null && andThen.inner is FollowLink) {
                    val condBlock = extractCondition(other)
                    if (condBlock != null) {
                        val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                        return Labeled(this.inner.then(appended), this.label)
                    }
                }
            }
            // O16 extension: bare AndThen(FollowLink, ...) reached via O17 delegation
            if (this is AndThen && this.inner is FollowLink) {
                val condBlock = extractCondition(other)
                if (condBlock != null) {
                    val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                    return this.then(appended)
                }
            }
        }

        // O11: condition OP FollowLink(srcCond) — inverse-link predicate rewrite.
        // When the right operand is Labeled(FollowLink(srcQuery, dir, link), T) and the left operand
        // has an extractable condition, translate the membership test into an inline predicate on each
        // vertex instead of collecting the right side into an aggregate.
        //
        // For IN  direction: v ∈ FollowLink(src, IN,  link) iff v.out("link_link") reaches src
        //   → inverse traversal step = OutLink(link)
        // For OUT direction: v ∈ FollowLink(src, OUT, link) iff v.in("link_link")  reaches src
        //   → inverse traversal step = InLink(link)
        //
        // The HasLink("link") shortcut (v has any out-edge named link) applies only for IN direction
        // with src=All; there is no HasLinkIn shortcut for the OUT case.
        //
        // Works for both Difference and Union.
        if (condCombiner is ConditionCombiner.Difference || condCombiner is ConditionCombiner.Union) {
            val flQuery = other as? Labeled
            val flInner = flQuery?.inner as? FollowLink
            if (flInner != null) {
                val srcCondBlock = extractCondition(flInner.inner)
                if (srcCondBlock != null) {
                    val srcLabel = extractLabel(flInner.inner)
                    val linkStep = if (flInner.direction == LinkDirection.IN)
                        GremlinBlock.OutLink(flInner.linkName)
                    else
                        GremlinBlock.InLink(flInner.linkName)
                    val inversePredicate: GremlinBlock = when {
                        srcCondBlock is GremlinBlock.All && flInner.direction == LinkDirection.IN ->
                            GremlinBlock.HasLink(flInner.linkName)
                        else -> {
                            val chain = linkStep
                                .andThen(srcCondBlock)
                                .let { if (srcLabel != null) it.andThen(GremlinBlock.HasLabel(srcLabel)) else it }
                            GremlinBlock.Where(chain)
                        }
                    }
                    val thisCondBlock = extractCondition(this)
                    if (thisCondBlock != null) {
                        val label = thisLabel ?: otherLabel
                        val combined = condCombiner.combineBlocks(thisCondBlock, inversePredicate)
                        val result = Where.of(combined)
                        return if (label != null) Labeled.of(result, label) else result
                    }
                }

                // O11b: src is Labeled(FollowLink(innerSrc, innerDir, innerLink), srcLabel) —
                // a two-hop pattern. Build a nested where predicate:
                //   where(outerInvLink.where(innerInvLink.innerSrcCond.hasLabel(innerSrcLabel)).hasLabel(srcLabel))
                //
                // Inverse direction rule (same as O11):
                //   FollowLink direction IN  → inverse step = OutLink (forward was in(), reverse is out())
                //   FollowLink direction OUT → inverse step = InLink  (forward was out(), reverse is in())
                val innerFL = (flInner.inner as? Labeled)?.inner as? FollowLink
                if (innerFL != null) {
                    val innerSrcCondBlock = extractCondition(innerFL.inner)
                    if (innerSrcCondBlock != null) {
                        val srcLabel = (flInner.inner as Labeled).label
                        val innerSrcLabel = extractLabel(innerFL.inner)
                        val outerLinkStep = if (flInner.direction == LinkDirection.IN)
                            GremlinBlock.OutLink(flInner.linkName)
                        else
                            GremlinBlock.InLink(flInner.linkName)
                        val innerLinkStep = if (innerFL.direction == LinkDirection.IN)
                            GremlinBlock.OutLink(innerFL.linkName)
                        else
                            GremlinBlock.InLink(innerFL.linkName)
                        val innerChain = innerLinkStep
                            .andThen(innerSrcCondBlock)
                            .let { if (innerSrcLabel != null) it.andThen(GremlinBlock.HasLabel(innerSrcLabel)) else it }
                        val innerPredicate = GremlinBlock.Where(innerChain)
                        val outerChain = outerLinkStep
                            .andThen(innerPredicate)
                            .andThen(GremlinBlock.HasLabel(srcLabel))
                        val inversePredicate = GremlinBlock.Where(outerChain)
                        val thisCondBlock = extractCondition(this)
                        if (thisCondBlock != null) {
                            val label = thisLabel ?: otherLabel
                            val combined = condCombiner.combineBlocks(thisCondBlock, inversePredicate)
                            val result = Where.of(combined)
                            return if (label != null) Labeled.of(result, label) else result
                        }
                    }
                }
            }
        }

        val thisCondition = extractCondition(this)
        val otherCondition = extractCondition(other)

        if (thisCondition == null || otherCondition == null) {
            return null
        }

        val label = thisLabel ?: otherLabel
        val combinedCondition = Where.of(condCombiner.combineBlocks(thisCondition, otherCondition))

        return if (label != null) Labeled.of(combinedCondition, label) else combinedCondition
    }

    fun union(other: GremlinQuery): GremlinQuery =
        combineEfficient(other, ConditionCombiner.Union)
            ?: run {
                fun flatSubqueries(q: GremlinQuery): List<GremlinQuery> =
                    if (q is Order && q.inner is UnionAll && q.orderBlock == GremlinBlock.Dedup)
                        q.inner.subqueries
                    else listOf(q)
                UnionAll(flatSubqueries(this) + flatSubqueries(other)).then(GremlinBlock.Dedup)
            }

    fun intersect(other: GremlinQuery): GremlinQuery =
        combineEfficient(other, ConditionCombiner.Intersect)
            ?: Aggregate(this, other) { P.within(it) }

    fun difference(other: GremlinQuery): GremlinQuery =
        combineEfficient(other, ConditionCombiner.Difference)
            ?: Aggregate(this, other) { P.without(it) }

    fun unionAll(vararg queries: GremlinQuery) = UnionAll(listOf(this, *queries))

    sealed class Condition(private val _block: GremlinBlock) : GremlinQuery() {
        override fun startTraversal(gs: GraphTraversalSource): YTBuilder = YTBuilder.of(gs.V(), _block)
        override fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder =
            YTBuilder.of(t.V(), _block, paramCounter)

        override fun shortName(): String = _block.shortName

        fun combineBinary(other: Condition, combiner: (GremlinBlock, GremlinBlock) -> GremlinBlock): Condition =
            Where.of(combiner(_block, other._block))

        fun combineUnary(combiner: (GremlinBlock) -> GremlinBlock): Condition =
            Where.of(combiner(_block))

        fun asBlock() = _block
    }

    data class Where(val block: GremlinBlock) : Condition(block) {

        companion object {
            fun of(block: GremlinBlock): Where {
                require(block.type == BlockType.CONDITION || block.type == BlockType.COMBINE)
                return Where(block.simplify() ?: block)
            }
        }
    }

    // todo: think how to preserve the order of the parameters
    // todo: handle Take & Skip differently too
    data class ByIds(val ids: List<RID>) : Condition(GremlinBlock.IdWithin(ids)) {
        override fun startTraversal(gs: GraphTraversalSource): YTBuilder =
            YTBuilder(gs.V(*ids.toTypedArray()).asYT(), 0)
    }

    data class NestedCondition(val structure: List<String>, val condition: Condition) : Condition(
        buildBlock(structure, condition)
    ) {
        companion object {
            private fun buildBlock(structure: List<String>, condition: Condition): GremlinBlock {
                val chain = structure
                    .fold(GremlinBlock.All as GremlinBlock) { a, b ->
                        a.andThen(GremlinBlock.OutLink(b))
                    }
                    .andThen(condition.asBlock())
                val where = GremlinBlock.Where(chain)
                return where.simplify() ?: where
            }
        }
    }

    sealed class Chained(
        private val _inner: GremlinQuery,
        private val _block: GremlinBlock,
        private val dependsOnOrder: Boolean = false,
        private val isOrder: Boolean = false
    ) : GremlinQuery() {

        override fun startTraversal(gs: GraphTraversalSource): YTBuilder =
            _inner.startTraversal(gs).combine(_block)

        override fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder =
            _inner
                .continueTraversal(t, paramCounter, ignoreSort && !dependsOnOrder)
                // todo: this optimization obviously brings some errors
                // .combine(if (isOrder && ignoreSort) GremlinBlock.All else _block)
                .combine(_block)

        override fun shortName(): String = _block.shortName
    }

    data class Labeled(val inner: GremlinQuery, val label: String) :
        Chained(inner, GremlinBlock.HasLabel(label)) {
        companion object {
            fun of(query: GremlinQuery, label: String): GremlinQuery =
                // Flatten nested Labeled with the same label: applying hasLabel("T") twice is idempotent.
                // Different labels (e.g. Labeled(X, "ChildIssue") inside Labeled(_, "Issue")) are kept
                // as-is since they represent two distinct hasLabel filters.
                Labeled(inner = if (query is Labeled && query.label == label) query.inner else query, label = label)
        }
    }

    data class AndThen(val inner: GremlinQuery, val block: GremlinBlock) :
        Chained(inner, block)

    data class Slice(
        val inner: GremlinQuery,
        val sliceBlock: GremlinBlock,
    ) : Chained(inner, sliceBlock, dependsOnOrder = true) {

        companion object {
            fun of(query: GremlinQuery, sliceBlock: GremlinBlock): GremlinQuery {
                require(sliceBlock.type == BlockType.SLICE)
                return Slice(
                    inner = (query as? Slice)?.inner ?: query,
                    sliceBlock = ((query as? Slice)?.sliceBlock ?: GremlinBlock.All).andThen(sliceBlock)
                )
            }
        }
    }

    enum class LinkDirection {
        IN, OUT
    }

    data class FollowLink(
        val inner: GremlinQuery,
        val direction: LinkDirection,
        val linkName: String
    ) : Chained(
        inner,
        when (direction) {
            LinkDirection.IN -> GremlinBlock.InLink(linkName)
            LinkDirection.OUT -> GremlinBlock.OutLink(linkName)
        },
    )

    data class UnionAll(val subqueries: List<GremlinQuery>) : GremlinQuery() {

        private fun subtraversals(counter: Int, sortApplied: Boolean): Pair<Array<YT>, Int> {
            val result = mutableListOf<YT>()
            var c = counter
            subqueries.forEach { sq ->
                val subRes = sq.continueTraversal(`__`.start(), c, sortApplied)
                c = subRes.counter
                result.add(subRes.traversal)
            }

            return Pair(result.toTypedArray(), c)
        }

        override fun startTraversal(gs: GraphTraversalSource): YTBuilder {
            val subi = subtraversals(0, sortApplied = false)
            return YTBuilder.of(gs.union(*subi.first), counter = subi.second)
        }

        override fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder {
            val subi = subtraversals(paramCounter, ignoreSort)
            return YTBuilder.of(t.union(*subi.first), counter = subi.second)
        }

        override fun shortName(): String = "unionAll"
    }

    data class Aggregate(val left: GremlinQuery, val right: GremlinQuery, val fn: (String) -> P<String>) :
        GremlinQuery() {

        override fun startTraversal(gs: GraphTraversalSource): YTBuilder =
            builder(right.startTraversal(gs), ignoreSort = false)

        // we don't need sorting for the right part
        override fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder =
            builder(right.continueTraversal(t, paramCounter, ignoreSort = true), ignoreSort)

        private fun builder(rightInner: YTBuilder, ignoreSort: Boolean): YTBuilder {
            val rightSetName = "aggr_" + rightInner.counter

            return left
                .continueTraversal(
                    rightInner.traversal.aggregate(rightSetName).fold().asYT(),
                    rightInner.counter + 1,
                    ignoreSort = ignoreSort
                )
                .combine { it.where(fn(rightSetName)) }
        }

        override fun shortName(): String = "aggregate"
    }

    data class AggregateNoOrder(
        val query1: GremlinQuery,
        val query2: GremlinQuery,
        val combiner: (GraphTraversal<*, *>, GraphTraversal<*, *>) -> GraphTraversal<*, *>
    ) : GremlinQuery() {

        override fun startTraversal(gs: GraphTraversalSource): YTBuilder {
            val res1 = query1.startTraversal(gs)
            val res2 = query2.continueTraversal(`__`.start(), res1.counter, false)
            return aggregate(res1, res2)
        }

        override fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder {
            val res1 = query1.continueTraversal(t, paramCounter, ignoreSort)
            val res2 = query2.continueTraversal(`__`.start(), res1.counter, ignoreSort)
            return aggregate(res1, res2)
        }

        private fun aggregate(
            res1: YTBuilder,
            res2: YTBuilder
        ): YTBuilder = YTBuilder.of(
            combiner(res1.traversal.fold(), res2.traversal.fold()).unfold<YTDBVertex>(),
            counter = res2.counter
        )

        override fun shortName(): String = "aggregateNoOrder"
    }

    data class SortBy(val inner: GremlinQuery, val sortBlock: GremlinBlock.Sort) :
        Chained(inner, sortBlock, dependsOnOrder = false, isOrder = true) {
        companion object {
            fun of(query: GremlinQuery, sortBlock: GremlinBlock.Sort): GremlinQuery = SortBy(query, sortBlock)
        }

        fun reverseOrder(): SortBy = this.copy(
            inner = inner, sortBlock = sortBlock.copy(
                by = sortBlock.by,
                direction = if (sortBlock.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
            )
        )
    }

    data class ReversedOrder(val inner: GremlinQuery) : Chained(inner, GremlinBlock.Reverse)

    data class Order(val inner: GremlinQuery, val orderBlock: GremlinBlock) :
        Chained(inner, orderBlock, isOrder = true) {

        companion object {
            fun of(query: GremlinQuery, orderBlock: GremlinBlock): GremlinQuery {
                require(orderBlock.type == BlockType.ORDER)
                return Order(
                    inner = (query as? Order)?.inner ?: query,
                    orderBlock = ((query as? Order)?.orderBlock ?: GremlinBlock.All).andThen(orderBlock)
                )
            }
        }
    }
}

