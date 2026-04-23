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
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies

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

        /**
         * Creates anonymous child traversals for each subquery.
         *
         * When [optionsStrategy] is provided, it is added to each anonymous child traversal.
         * This propagates query-level config (e.g. `polymorphicQuery`) so that provider
         * optimization strategies (such as `YTDBGraphStepStrategy`) can read it when they
         * are applied to the child traversal.
         *
         * Only [OptionsStrategy] is propagated — not the full strategy list — to avoid
         * interfering with TinkerPop's own strategy application on child traversals.
         * The graph reference is not needed: TinkerPop propagates it automatically when
         * the child traversal is integrated into the parent via `union()`.
         *
         * The child's strategies container is replaced with a fresh [DefaultTraversalStrategies]
         * before adding [optionsStrategy]. `__.start()` aliases its strategies field to the
         * shared `TraversalStrategies.GlobalCache[EmptyGraph]` singleton; mutating it races
         * across threads (`ConcurrentModificationException` in `sortStrategies`) and leaks
         * options globally. A private container eliminates both issues — the child's
         * strategies are only probed via `getStrategy(OptionsStrategy.class)` before
         * TinkerPop's `lock()` overwrites them with the parent's.
         *
         * `GraphTraversal.with()` cannot be used here: it is a step modulator (configures
         * the preceding step), not traversal-wide config. Anonymous traversals need the
         * [OptionsStrategy] set directly via the admin API.
         */
        private fun subtraversals(
            optionsStrategy: OptionsStrategy?,
            counter: Int,
            sortApplied: Boolean
        ): Pair<Array<YT>, Int> {
            val result = mutableListOf<YT>()
            var c = counter
            subqueries.forEach { sq ->
                val child = `__`.start<Any>()
                if (optionsStrategy != null) {
                    val admin = child.asAdmin()
                    admin.strategies = DefaultTraversalStrategies().apply { addStrategies(optionsStrategy) }
                }
                val subRes = sq.continueTraversal(child.asYT(), c, sortApplied)
                c = subRes.counter
                result.add(subRes.traversal)
            }

            return Pair(result.toTypedArray(), c)
        }

        private fun extractOptionsStrategy(strategies: TraversalStrategies): OptionsStrategy? =
            strategies.getStrategy(OptionsStrategy::class.java).orElse(null)

        override fun startTraversal(gs: GraphTraversalSource): YTBuilder {
            val subi = subtraversals(extractOptionsStrategy(gs.strategies), 0, sortApplied = false)
            return YTBuilder.of(gs.union(*subi.first), counter = subi.second)
        }

        override fun continueTraversal(t: YT, paramCounter: Int, ignoreSort: Boolean): YTBuilder {
            val subi = subtraversals(
                extractOptionsStrategy(t.asAdmin().strategies),
                paramCounter,
                ignoreSort
            )
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

