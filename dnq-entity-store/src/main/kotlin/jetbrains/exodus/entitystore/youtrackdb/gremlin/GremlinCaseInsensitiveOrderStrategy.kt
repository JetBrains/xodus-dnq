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

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.Step
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import org.apache.tinkerpop.gremlin.structure.Element
import org.apache.tinkerpop.gremlin.structure.PropertyType

/**
 * Repairs case-insensitive string ordering which remains in native Gremlin after provider
 * optimization. The original query retains ordinary `order().by(property)` modulators so MATCH and
 * other provider strategies can consume their native form before this fallback runs.
 *
 * This is a temporary workaround and should be removed once YTDB-1297 (Make native Gremlin
 * property operations honor declared collation) is implemented.
 */
class GremlinCaseInsensitiveOrderStrategy private constructor() :
    AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>(),
    TraversalStrategy.ProviderOptimizationStrategy {

    override fun apply(traversal: Traversal.Admin<*, *>) {
        // A translated traversal is an opaque provider boundary. In the current checked-in YTDB
        // pin the boundary class is absent; resolving it optionally keeps this workaround source
        // compatible with both that pin and the newer translator-enabled snapshots.
        if (traversal.steps.any { isMatchBoundary(it) }) return

        // TinkerPop applies strategies recursively to TraversalParent children. Only inspect this
        // traversal's own steps here; descending manually would rewrite provider-owned internals.
        traversal.steps.toList()
            .filterIsInstance<OrderGlobalStep<*, *>>()
            .forEach(::rewriteOrderStep)
    }

    /** Provider strategies get the ordinary order form before this native Gremlin fallback. */
    override fun applyPrior(): Set<Class<out TraversalStrategy.ProviderOptimizationStrategy>> =
        setOf(
            YTDBGraphStepStrategy::class.java,
            YTDBGraphCountStrategy::class.java,
            YTDBGraphMatchStepStrategy::class.java,
            optionalProviderStrategy(GREMLIN_TO_MATCH_STRATEGY)
        ).filterNotNull().toSet()

    private fun rewriteOrderStep(step: OrderGlobalStep<*, *>) {
        step.comparators.forEach { comparator ->
            val traversal = comparator.value0
            caseInsensitiveOrderTraversal(traversal)?.let { replacement ->
                step.replaceLocalChild(traversal, replacement)
            }
        }
    }

    /**
     * Property-name `by()` modulators use [ValueTraversal], while linked-property sorts use an
     * explicit traversal ending in [PropertiesStep]. Rewriting only those two shapes avoids
     * changing arbitrary user comparators. An absent property remains unproductive so YTDB can
     * expose it as a null sort key and apply the query's NULLS LAST policy.
     */
    @Suppress("UNCHECKED_CAST")
    private fun caseInsensitiveOrderTraversal(
        traversal: Traversal.Admin<*, *>
    ): Traversal.Admin<*, *>? {
        val propertyTraversal: GraphTraversal.Admin<Any, Any> = when (traversal) {
            is ValueTraversal<*, *> ->
                `__`.values<Element, Any>(traversal.propertyKey).asAdmin() as GraphTraversal.Admin<Any, Any>

            is GraphTraversal.Admin<*, *> -> {
                val propertiesStep = traversal.endStep as? PropertiesStep<*> ?: return null
                if (
                    propertiesStep.returnType != PropertyType.VALUE ||
                    propertiesStep.propertyKeys.size != 1
                ) {
                    return null
                }
                traversal.clone() as GraphTraversal.Admin<Any, Any>
            }

            else -> return null
        }

        return (propertyTraversal as GraphTraversal<Any?, Any>)
            .choose(
                P.typeOf<Any>(String::class.java),
                `__`.toLower<Any>() as GraphTraversal<*, Any>,
                `__`.identity<Any>()
            )
            .asAdmin()
    }

    private fun isMatchBoundary(step: Step<*, *>): Boolean =
        MATCH_BOUNDARY_CLASS?.isAssignableFrom(step.javaClass) == true

    companion object {
        private const val GREMLIN_TO_MATCH_STRATEGY =
            "com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy"
        private const val MATCH_BOUNDARY =
            "com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep"

        private val INSTANCE = GremlinCaseInsensitiveOrderStrategy()
        private val MATCH_BOUNDARY_CLASS: Class<*>? by lazy {
            runCatching { Class.forName(MATCH_BOUNDARY) }.getOrNull()
        }

        fun instance(): GremlinCaseInsensitiveOrderStrategy = INSTANCE

        private fun optionalProviderStrategy(name: String): Class<out TraversalStrategy.ProviderOptimizationStrategy>? =
            runCatching {
                Class.forName(name).asSubclass(TraversalStrategy.ProviderOptimizationStrategy::class.java)
            }.getOrNull()
    }
}
