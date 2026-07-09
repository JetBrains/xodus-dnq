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

import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.QueryDetails
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromClassExecutionStep
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromCollectionExecutionStep
import mu.KLogging
import java.util.concurrent.TimeUnit

/**
 * A YTDB [QueryMetricsListener] (XD-1281) that inspects each finished query's execution plan and
 * logs a warning when the query performed an unindexed full scan.
 *
 * A query is flagged as a full scan if its execution plan contains a
 * [FetchFromClassExecutionStep] (full class scan) or a [FetchFromCollectionExecutionStep]
 * (single-cluster unindexed scan) anywhere in the step tree — including steps nested via
 * [ExecutionStep.getSubSteps] and sub-plans exposed via
 * [ExecutionStepInternal.getSubExecutionPlans] (e.g. `ParallelExecStep` branches). We deliberately
 * do NOT check for the absence of an index step: a fully-indexed plan never contains a class/
 * collection scan step, so presence alone is a sound signal with no false positives.
 *
 * The [ExecutionPlan] is only valid synchronously inside the callback, so it is read and
 * pretty-printed immediately and never retained. The potentially-expensive accessors
 * ([QueryDetails.getQuery], [ExecutionPlan.prettyPrint]) are called only on the detected-scan
 * branch, never on the hot path.
 *
 * ### Back-to-back duplicate suppression
 *
 * A single fully-exhausted DNQ traversal fires `queryFinished` twice (TinkerPop auto-closes on
 * iterator exhaustion, then DNQ explicitly closes on dispose; YTDB's metrics step is not
 * idempotent across `close()`). To make one query execution produce exactly one emission, we
 * remember the last emitted [dedup key][DedupKey] — built from *cheap* fields only
 * (`startedAtMillis`, `executionTimeNanos`, `querySummary`) — and suppress an emission whose key
 * equals the immediately-preceding one. Two genuinely-identical queries separated by any other
 * query won't collide, since only the single most-recent key is retained.
 *
 * The listener instance is per-DNQ-transaction (reused across begin/flush/revert). The last-key
 * field is [@Volatile] and the compare-and-store is `synchronized`, so it stays correct even in
 * the (not-expected) event of concurrent callbacks for one transaction.
 */
class FullScanDetectingListener : QueryMetricsListener {

    private data class DedupKey(
        val startedAtMillis: Long,
        val executionTimeNanos: Long,
        val querySummary: String?
    )

    @Volatile
    private var lastEmittedKey: DedupKey? = null

    override fun queryFinished(details: QueryDetails, startedAtMillis: Long, executionTimeNanos: Long) {
        // Defensive: a bug here (isFullScan/prettyPrint/record) must never break the user's query,
        // independent of YTDB's own catch. Catch Throwable so even Errors (e.g. a deep-plan
        // StackOverflow in walk()) are contained.
        try {
            val plan = details.executionPlan ?: return   // null plan = non-classifiable, skip
            if (!isFullScan(plan)) return

            // Collapse the immediate double-fire using only cheap fields, before touching the
            // expensive getQuery()/prettyPrint() accessors.
            val key = DedupKey(startedAtMillis, executionTimeNanos, details.querySummary)
            synchronized(this) {
                if (key == lastEmittedKey) return
                lastEmittedKey = key
            }

            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(executionTimeNanos)
            val prettyPlan = plan.prettyPrint(0, 2)
            val gremlin = details.query
            val shape = details.querySummary

            logger.warn {
                buildString {
                    append("YTDB full scan")
                    if (shape != null) append(" | dnq: ").append(shape)
                    append(" | gremlin: ").append(gremlin)
                    append(" | ").append(elapsedMs).append(" ms")
                    append('\n').append(prettyPlan)
                }
            }

            FullScanDetection.record(
                DetectedScan(shape = shape, gremlin = gremlin, elapsedMs = elapsedMs, plan = prettyPlan)
            )
        } catch (t: Throwable) {
            logger.warn(t) { "Full-scan detection failed; ignoring to protect the user query" }
        }
    }

    private fun isFullScan(plan: ExecutionPlan): Boolean = walk(plan.steps)

    private fun walk(steps: List<ExecutionStep>): Boolean {
        for (step in steps) {
            if (step is FetchFromClassExecutionStep || step is FetchFromCollectionExecutionStep) return true
            if (walk(step.subSteps)) return true
            val subPlans = (step as? ExecutionStepInternal)?.subExecutionPlans
            if (subPlans != null && subPlans.any { walk(it.steps) }) return true
        }
        return false
    }

    companion object : KLogging()
}
