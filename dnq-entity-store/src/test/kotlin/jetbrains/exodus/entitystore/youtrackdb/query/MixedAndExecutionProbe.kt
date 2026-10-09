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

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep
import jetbrains.exodus.entitystore.youtrackdb.gremlin.YT
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator
import java.util.function.Supplier
import kotlin.test.assertNotNull

/**
 * Copies executed plans synchronously while the listener callback can still read them.
 * This supplies positive index-use evidence for mixed property and link filters in the DNQ query library.
 * Duplicate close callbacks share a start time, duration, and query summary, so only one copy is retained.
 * A missing plan records a failure for the measuring test instead of silently accepting an empty capture.
 */
internal class MixedAndPlanListener(var allowNativeDirectRid: Boolean = false) : QueryMetricsListener {
    val plans = mutableListOf<String>()
    val failures = mutableListOf<Throwable>()
    private val seen = mutableSetOf<Triple<Long, Long, String?>>()

    override fun queryFinished(details: QueryMetricsListener.QueryDetails, startedAtMillis: Long, executionTimeNanos: Long) {
        try {
            if (seen.add(Triple(startedAtMillis, executionTimeNanos, details.querySummary))) {
                val plan = details.executionPlan
                if (plan != null || !allowNativeDirectRid) {
                    plans += assertNotNull(plan, "Measured query must have an executed plan").prettyPrint(0, 2)
                }
            }
        } catch (failure: Throwable) {
            // The provider catches listener exceptions. Retain them so the measuring test can fail.
            failures += failure
        }
    }
}

/**
 * Counts vertices from the native `YTDBGraphStep` before residual link filtering, not final query results.
 * DNQ emits safe property filters before link checks so the SQL (Structured Query Language) engine can use an index.
 * The count checks source work on the indexed contact fixture in `MixedAndNativeSourceTest`.
 * `containers` records which predicates reached that source. `opened` and `closed` check iterator cleanup.
 *
 * MATCH is YouTrackDB's pattern query mode translated from Gremlin. The tests exercise both session modes.
 * `allowMatch` permits no native source only if an `AbstractMatchPlanStep` proves translation occurred.
 * That case needs an executed index plan from the listener, not a zero candidate count as evidence.
 * Otherwise `single()` requires exactly one native source and throws if the optimized shape changes.
 */
internal class MixedAndSourceProbe(traversal: YT, allowMatch: Boolean = false, optimized: Boolean = false) {
    var candidates = 0
        private set
    var opened = 0
        private set
    var closed = 0
        private set
    val containers: List<Pair<String, Any?>>
    val matched: Boolean
    val sourceIds: List<Any>

    init {
        val admin = traversal.asAdmin()
        if (!optimized) admin.applyStrategies()
        matched = admin.steps.any { it is AbstractMatchPlanStep<*, *> }
        @Suppress("UNCHECKED_CAST")
        val source = if (allowMatch && matched) null else
            admin.steps.filterIsInstance<YTDBGraphStep<*, *>>().single() as YTDBGraphStep<Any, YTDBVertex>
        sourceIds = source?.ids?.toList() ?: emptyList()
        containers = source?.hasContainers?.map { it.key to it.predicate } ?: emptyList()
        if (source != null) observe(source)
    }

    /**
     * Wraps the optimized iterator supplier without adding a step that could change optimization.
     * `GraphStep` has a supplier setter but no getter, so test-only reflection reads `iteratorSupplier`.
     * Missing fields, access failures, or incompatible types throw instead of disabling observation.
     * Each `next()` counts one source vertex. The close guard counts and closes the delegate only once.
     */
    private fun observe(source: YTDBGraphStep<Any, YTDBVertex>) {
        val field = GraphStep::class.java.getDeclaredField("iteratorSupplier").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val supplier = field.get(source) as Supplier<Iterator<YTDBVertex>>
        source.setIteratorSupplier {
            opened++
            val delegate = supplier.get()
            object : CloseableIterator<YTDBVertex> {
                private var finished = false
                override fun hasNext() = delegate.hasNext()
                override fun remove() = throw UnsupportedOperationException()
                override fun next(): YTDBVertex = delegate.next().also { candidates++ }
                override fun close() {
                    if (!finished) {
                        finished = true
                        closed++
                        CloseableIterator.closeIterator(delegate)
                    }
                }
            }
        }
    }
}
