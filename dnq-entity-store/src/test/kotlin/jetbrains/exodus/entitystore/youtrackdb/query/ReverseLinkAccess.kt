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

import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.gremlin.asYT
import jetbrains.exodus.entitystore.youtrackdb.testutil.QueryResourceRecorder
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test-only evidence from the optimized source and synchronously copied executed plans. */
data class ReverseLinkAccess<R>(
    val result: R, val sourceIds: List<Any>, val candidates: Int,
    val matched: Boolean, val plans: List<String>, val steps: String
)

fun <R> measureLinkAccess(
    tx: YTDBStoreTransactionImpl,
    traversal: GraphTraversal<*, *>,
    consume: (GraphTraversal<*, *>) -> R
): ReverseLinkAccess<R> {
    val listener = MixedAndPlanListener()
    val graphTx = (tx.g().graph as YTDBGraphInternal).tx()
    graphTx.withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT).withQueryListener(listener)
    try {
        return QueryResourceRecorder().use { recorder ->
            recorder.attach(tx.activeYtdbSession())
            val probe = MixedAndSourceProbe(traversal.asYT(), allowMatch = true)
            // Null plans require verified optimized native positional RID access, never MATCH.
            listener.allowNativeDirectRid = !probe.matched && probe.sourceIds.isNotEmpty()
            val result = traversal.use(consume)
            assertTrue(listener.failures.isEmpty(), listener.failures.toString())
            if (probe.matched || probe.sourceIds.isEmpty()) {
                assertTrue(listener.plans.isNotEmpty(), "Executed plan is required")
            }
            assertEquals(if (probe.matched) 0 else 1, probe.opened)
            assertEquals(probe.opened, probe.closed, "Native source must close even on early success")
            assertTrue(tx.activeYtdbSession().activeQueries.isEmpty(), "Registered queries must close")
            ReverseLinkAccess(result, probe.sourceIds, probe.candidates, probe.matched,
                listener.plans.toList(), traversal.asAdmin().steps.toString())
        }
    } finally {
        traversal.close()
        graphTx.withQueryListener(QueryMetricsListener.NO_OP)
    }
}

/** Observes the actual terminal traversal after optimization without adding traversal steps. */
fun <R> measureLinkTerminal(tx: YTDBStoreTransactionImpl, consume: () -> R): ReverseLinkAccess<R> {
    val source = tx.g()
    val graphTx = (source.graph as YTDBGraphInternal).tx()
    val listener = MixedAndPlanListener()
    var probe: MixedAndSourceProbe? = null
    var steps = ""
    val observer = object : AbstractTraversalStrategy<TraversalStrategy.VerificationStrategy>(), TraversalStrategy.VerificationStrategy {
        override fun apply(traversal: Traversal.Admin<*, *>) {
            if (traversal.parent !is EmptyStep<*, *> || traversal.steps.none { it is YTDBGraphStep<*, *> || it is AbstractMatchPlanStep<*, *> }) return
            check(probe == null) { "Terminal must execute exactly one root traversal" }
            probe = MixedAndSourceProbe((traversal as GraphTraversal<*, *>).asYT(), allowMatch = true, optimized = true)
            listener.allowNativeDirectRid = !probe!!.matched && probe!!.sourceIds.isNotEmpty()
            steps = traversal.steps.toString()
        }
    }
    graphTx.withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT).withQueryListener(listener)
    try {
        mockkObject(tx)
        every { tx.g() } answers { source.withStrategies(observer) }
        return QueryResourceRecorder().use { recorder ->
            recorder.attach(tx.activeYtdbSession())
            val result = consume()
            val observed = checkNotNull(probe) { "Terminal must execute a measured traversal" }
            assertTrue(listener.failures.isEmpty(), listener.failures.toString())
            if (observed.matched || observed.sourceIds.isEmpty()) assertTrue(listener.plans.isNotEmpty(), "Executed plan is required")
            assertEquals(if (observed.matched) 0 else 1, observed.opened)
            assertEquals(observed.opened, observed.closed, "Terminal source must close")
            assertTrue(tx.activeYtdbSession().activeQueries.isEmpty(), "Registered queries must close")
            ReverseLinkAccess(result, observed.sourceIds, observed.candidates, observed.matched, listener.plans.toList(), steps)
        }
    } finally {
        unmockkObject(tx)
        graphTx.withQueryListener(QueryMetricsListener.NO_OP)
    }
}

/** Terminal operations build their traversal internally. Require positive executed-plan evidence. */
fun <R> captureLinkPlans(tx: YTDBStoreTransactionImpl, consume: () -> R): Pair<R, List<String>> {
    val listener = MixedAndPlanListener()
    val graphTx = (tx.g().graph as YTDBGraphInternal).tx()
    graphTx.withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT).withQueryListener(listener)
    try {
        val result = consume()
        assertTrue(listener.failures.isEmpty(), listener.failures.toString())
        assertTrue(listener.plans.isNotEmpty(), "Terminal must produce an executed plan")
        return result to listener.plans.toList()
    } finally {
        graphTx.withQueryListener(QueryMetricsListener.NO_OP)
    }
}
