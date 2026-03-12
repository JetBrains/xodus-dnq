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

        fun extractLabel(q: GremlinQuery): String? = if (q is Labeled) q.label else null
        fun extractCondition(q: GremlinQuery): GremlinBlock? =
            if (q is Labeled && q.inner is Condition) q.inner.asBlock()
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
        // When one side is Labeled(FollowLink, T) and the other has an extractable condition,
        // append the condition directly to the FollowLink traversal instead of collecting into
        // a named aggregate set. For difference, wrap the condition in Not first.
        // Symmetric for intersect (either side can be the link); for difference only `this` can
        // be the link side (condition \ FollowLink is not safe to rewrite this way).
        if (condCombiner is ConditionCombiner.Intersect || condCombiner is ConditionCombiner.Difference) {
            val linkQuery: Labeled?
            val condBlock: GremlinBlock?
            when {
                this is Labeled && this.inner is FollowLink -> {
                    condBlock = extractCondition(other)
                    linkQuery = if (condBlock != null) this else null
                }
                condCombiner is ConditionCombiner.Intersect && other is Labeled && other.inner is FollowLink -> {
                    condBlock = extractCondition(this)
                    linkQuery = if (condBlock != null) other else null
                }
                else -> { linkQuery = null; condBlock = null }
            }
            if (linkQuery != null && condBlock != null) {
                val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                return Labeled(linkQuery.inner.then(appended), linkQuery.label)
            }
        }

        // O16: Chained O7 fusion — Labeled(AndThen(FollowLink, cond1), T) OP cond2.
        // When `this` is the result of a prior O7 fusion (an AndThen chain rooted at FollowLink),
        // extend the existing chain with the new condition instead of falling to Aggregate.
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
        }

        // O11: condition OP FollowLink(srcCond) — inverse-link predicate rewrite.
        // When the right operand is Labeled(FollowLink(srcQuery, IN, link), T) and the left operand
        // has an extractable condition, translate the membership test into an inline predicate on each
        // vertex (e.g. where(out("link_link").srcCond.hasLabel(srcLabel))) instead of collecting the
        // right side into an aggregate. Works for both Difference and Union.
        if (condCombiner is ConditionCombiner.Difference || condCombiner is ConditionCombiner.Union) {
            val flQuery = other as? Labeled
            val flInner = flQuery?.inner as? FollowLink
            if (flInner != null && flInner.direction == LinkDirection.IN) {
                val srcCondBlock = extractCondition(flInner.inner)
                if (srcCondBlock != null) {
                    val srcLabel = extractLabel(flInner.inner)
                    val inversePredicate: GremlinBlock = when {
                        srcCondBlock is GremlinBlock.All ->
                            GremlinBlock.HasLink(flInner.linkName)
                        else -> {
                            val chain = GremlinBlock.OutLink(flInner.linkName)
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

