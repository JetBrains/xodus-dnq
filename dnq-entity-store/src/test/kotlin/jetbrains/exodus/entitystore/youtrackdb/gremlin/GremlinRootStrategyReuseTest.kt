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

import com.google.common.truth.Truth.assertThat
import com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime

/**
 * Checks that equivalent queries reuse the sorted root order without calling the library sorter.
 * Checks that each traversal keeps its own strategy list and options.
 */
class GremlinRootStrategyReuseTest : OTestMixin {

    @Rule
    @JvmField
    val youTrackDbRule = InMemoryYouTrackDB(initializeIssueSchema = false)

    override val youTrackDb = youTrackDbRule

    private val plain = GremlinQuery.all
    private val sorted = plain.then(GremlinBlock.Sort(GremlinBlock.Sort.ByProp("name"), GremlinBlock.SortDirection.ASC))
    private val aggregate = GremlinQuery.Aggregate(plain, plain) { P.without(it) }
    private val asc = YTDBQueryConfigParam.orderByNullsPlacementAsc.name
    private val desc = YTDBQueryConfigParam.orderByNullsPlacementDesc.name
    private val polymorphic = YTDBQueryConfigParam.polymorphicQuery.name
    private val valueKey = "poc-value"
    private val nullsLast = mapOf(asc to OrderByNullsPlacement.LAST.name, desc to OrderByNullsPlacement.LAST.name)

    // Skip when the query collector, which records query shapes for diagnostics, is enabled.
    @Before
    fun requireCollectorOff() {
        // Collector tests can enable diagnostics for the rest of the JVM's lifetime.
        assumeFalse("Root order reuse requires the collector to be disabled", GremlinQueryCollector.enabled)
    }

    // Compare sort counts, order, options, and isolation across plain, sorted, and Aggregate queries.
    // An Aggregate filters its left side using results collected from its right side.
    @Test
    fun `repeated starts reuse the sorted root order without sorting`(): Unit = withStoreTx { tx ->
        val counts = linkedMapOf<String, List<Int>>()
        for ((name, query) in listOf("plain" to plain, "sorted" to sorted, "aggregate" to aggregate, "options" to plain)) {
            val probe = SortProbe()
            val base = tx.g().withStrategies(probe)
            // Vary source options only in the options case.
            fun source(value: Int): GraphTraversalSource =
                if (name == "options") {
                    base.with(polymorphic, false).with(valueKey, value)
                        .with(asc, OrderByNullsPlacement.FIRST.name)
                        .with(desc, OrderByNullsPlacement.FIRST.name)
                } else {
                    base
                }

            val deltas = mutableListOf<Int>()
            // Count sorter runs during query start, not during source setup.
            fun start(gs: GraphTraversalSource): TraversalStrategies {
                val before = probe.sorts
                return query.start(gs).asAdmin().strategies.also { deltas += probe.sorts - before }
            }

            val firstSource = source(0)
            val cold = start(firstSource)
            val hit = start(firstSource)
            val next = start(source(1))
            val last = start(source(2))
            counts[name] = deltas.toList()
            println("Root sorts for $name (first, same source, next, last): $deltas")

            assertThat(hit).isNotSameInstanceAs(cold)
            assertThat(hit).isNotSameInstanceAs(next)
            assertThat(options(hit)).isNotSameInstanceAs(options(cold))
            assertThat(options(hit)).isNotSameInstanceAs(options(next))
            assertEquivalentOrder(cold, hit)
            assertEquivalentOrder(cold, next)
            assertEquivalentOrder(cold, last)
            assertConstraints(hit.toList())
            for ((strategies, value) in listOf(cold to 0, hit to 0, next to 1, last to 2)) {
                val expected = if (name == "options") nullsLast + mapOf(polymorphic to false, valueKey to value) else nullsLast
                assertThat(options(strategies).options).isEqualTo(expected)
                assertThat(strategies.getStrategy(LazyBarrierStrategy::class.java).isPresent).isEqualTo(query !== aggregate)
            }

            val clone = hit.clone()
            clone.removeStrategies(OptionsStrategy::class.java)
            assertThat(clone.getStrategy(OptionsStrategy::class.java).isPresent).isFalse()
            assertThat(options(hit).options).isEqualTo(options(cold).options)
            hit.removeStrategies(GremlinCaseInsensitiveOrderStrategy::class.java)
            assertThat(hit.getStrategy(GremlinCaseInsensitiveOrderStrategy::class.java).isPresent).isFalse()
            assertEquivalentOrder(cold, next)
            assertEquivalentOrder(cold, query.start(firstSource).asAdmin().strategies)
        }
        assertThat(counts).containsExactlyEntriesIn(mapOf(
            "plain" to listOf(1, 0, 0, 0),
            "sorted" to listOf(1, 0, 0, 0),
            "aggregate" to listOf(2, 0, 0, 0),
            "options" to listOf(1, 0, 0, 0)
        ))
    }

    // Check options and LazyBarrierStrategy presence across workers. This check does not prove thread safety.
    @Test
    fun `concurrent starts keep their own options`() = withStoreTx { tx ->
        val workers = 6
        val base = tx.g().withStrategies(SortProbe())
        val queries = listOf(plain, sorted, aggregate)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val results = (0 until workers).map { index ->
                val gs = base.with(polymorphic, index % 2 == 0).with(valueKey, index)
                val query = queries[index % queries.size]
                pool.submit {
                    ready.countDown()
                    check(start.await(10, SECONDS))
                    repeat(8) {
                        val strategies = query.start(gs).asAdmin().strategies
                        assertThat(options(strategies).options)
                            .isEqualTo(nullsLast + mapOf(polymorphic to (index % 2 == 0), valueKey to index))
                        assertThat(strategies.getStrategy(LazyBarrierStrategy::class.java).isPresent)
                            .isEqualTo(query !== aggregate)
                    }
                }
            }
            check(ready.await(10, SECONDS))
            start.countDown()
            results.forEach { it.get(10, SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        Unit
    }

    // Print a rough single-run timing for plain starts. The test asserts nothing.
    @Test
    fun `report rough plain start timing`() = withStoreTx { tx ->
        val gs = tx.g()
        repeat(10_000) { plain.start(gs) }
        val iterations = 50_000
        val elapsed = measureNanoTime { repeat(iterations) { plain.start(gs) } }
        println("Rough single-run plain start: ${elapsed / iterations} ns/call ($iterations calls after warm-up)")
    }

    // Require an OptionsStrategy in the strategy list.
    private fun options(strategies: TraversalStrategies): OptionsStrategy =
        strategies.getStrategy(OptionsStrategy::class.java).orElseThrow()

    // Compare iterator order with list order, then compare classes and non-option instances with the expected order.
    private fun assertEquivalentOrder(expected: TraversalStrategies, actual: TraversalStrategies) {
        val order = actual.toList()
        assertThat(actual.iterator().asSequence().toList()).containsExactlyElementsIn(order).inOrder()
        assertThat(order.map { it.javaClass }).containsExactlyElementsIn(expected.toList().map { it.javaClass }).inOrder()
        for ((first, second) in expected.toList().zip(order)) {
            if (first !is OptionsStrategy) assertThat(second).isSameInstanceAs(first)
        }
    }

    // Check category order and declared dependencies between strategies present in the list.
    private fun assertConstraints(order: List<TraversalStrategy<*>>) {
        val categories = order.map { TraversalStrategies.STRATEGY_CATEGORIES.indexOf(it.traversalCategory) }
        assertThat(categories).doesNotContain(-1)
        assertThat(categories).containsExactlyElementsIn(categories.sorted()).inOrder()
        val positions = order.mapIndexed { index, strategy -> strategy.javaClass to index }.toMap()
        for ((index, strategy) in order.withIndex()) {
            strategy.applyPrior().forEach { positions[it]?.let { prior -> assertThat(prior).isLessThan(index) } }
            strategy.applyPost().forEach { positions[it]?.let { post -> assertThat(post).isGreaterThan(index) } }
        }
    }

    /**
     * Counts runs of the library sorter, [TraversalStrategies.sortStrategies].
     * The library sorter calls `applyPrior()` once on each strategy per run.
     */
    private class SortProbe : AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy>(),
        TraversalStrategy.OptimizationStrategy {

        private val count = AtomicInteger()
        val sorts: Int get() = count.get()

        override fun applyPrior(): Set<Class<out TraversalStrategy.OptimizationStrategy>> {
            count.incrementAndGet()
            return emptySet()
        }

        override fun apply(traversal: Traversal.Admin<*, *>) = Unit
    }
}
