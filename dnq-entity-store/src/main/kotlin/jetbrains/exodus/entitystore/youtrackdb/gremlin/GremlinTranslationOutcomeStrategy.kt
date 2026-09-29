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

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator

/**
 * Reporting-only strategy for [GremlinQueryCollector]: records whether YouTrackDB's [GremlinToMatchStrategy]
 * turned a root traversal into a MATCH boundary step ([AbstractMatchPlanStep]), together with the
 * parameterized native Gremlin shape and an execution example. It never changes or fails the traversal.
 *
 * [GremlinQuery.start] attaches it while the collector is enabled. Anonymous child traversals are not
 * observed independently.
 */
internal class GremlinTranslationOutcomeStrategy(
    private val queryShape: String
) : AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>(),
    TraversalStrategy.ProviderOptimizationStrategy {

    override fun apply(traversal: Traversal.Admin<*, *>) {
        if (!traversal.isRoot) return
        val translated = traversal.steps.any { it is AbstractMatchPlanStep<*, *> }
        GremlinQueryCollector.recordOutcome(
            queryShape,
            translated = translated,
            nativeGremlinShape = { gremlin(traversal, parameterized = true) },
            executionExample = { describeExecution(traversal) }
        )
    }

    // The example of one execution: its Gremlin script with runtime values, the final traversal and, for
    // translated traversals, the execution plan (and MATCH statement, when the planner recorded one) that
    // will actually run. The step exposes its plan only once armed, so the compiled template is read
    // reflectively (diagnostic only).
    private fun describeExecution(traversal: Traversal.Admin<*, *>): String = buildString {
        append("Gremlin: ").append(gremlin(traversal, parameterized = false))
        append("\nFinal traversal: ").append(traversal)
        try {
            for (step in traversal.steps.filterIsInstance<YTDBMatchPlanStep<*, *>>()) {
                val plan = step.plan ?: TEMPLATE_FIELD.get(step) as InternalExecutionPlan? ?: continue
                plan.statement?.let { append("\nMATCH statement: ").append(it) }
                append("\nExecution plan:\n").append(plan.prettyPrint(0, 2))
            }
        } catch (e: Exception) {
            append("\n<MATCH plan unavailable: ${e.javaClass.simpleName}>")
        }
    }

    override fun applyPrior(): Set<Class<out TraversalStrategy.ProviderOptimizationStrategy>> =
        setOf(GremlinToMatchStrategy::class.java)

    private companion object {
        // Resolved lazily so a YouTrackDB version without this field only degrades the diagnostic text.
        val TEMPLATE_FIELD by lazy {
            YTDBMatchPlanStep::class.java.getDeclaredField("template").apply { isAccessible = true }
        }

        // The bytecode still holds every step callers appended after `start()`, so the script is the query as
        // built; with `parameterized` runtime values become `_args_n`. A diagnostic must never break the query
        // it observes, so translator failures are reported inline.
        fun gremlin(traversal: Traversal.Admin<*, *>, parameterized: Boolean): String =
            try {
                GroovyTranslator.of("g", parameterized).translate(traversal.bytecode).script
            } catch (e: RuntimeException) {
                "<Gremlin unavailable: ${e.javaClass.simpleName}>"
            }
    }
}
