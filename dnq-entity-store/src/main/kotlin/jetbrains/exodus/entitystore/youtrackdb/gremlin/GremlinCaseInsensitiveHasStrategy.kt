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
import org.apache.tinkerpop.gremlin.process.traversal.Compare
import org.apache.tinkerpop.gremlin.process.traversal.Step
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy
import org.apache.tinkerpop.gremlin.structure.T

/**
 * Repairs case-insensitive string equality which remains in native Gremlin after provider
 * optimization. DNQ string properties use case-insensitive collation, but a residual [HasStep]
 * after a link traversal is evaluated by TinkerPop and does not consult that schema collation.
 *
 * This is a temporary workaround and should be removed once YTDB-1297 (Make native Gremlin
 * property predicates honor declared collation) is implemented.
 *
 * This strategy intentionally does not change [GremlinBlock.PropEqual]. MATCH and YTDBGraphStep
 * must keep receiving ordinary has predicates so their provider-specific pushdown remains intact.
 */
class GremlinCaseInsensitiveHasStrategy private constructor() :
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
            .filterIsInstance<HasStep<*>>()
            .forEach { rewriteHasStep(traversal, it) }
    }

    /**
     * Provider strategies have already had the opportunity to consume their native forms before
     * this fallback runs. MATCH is optional in the currently pinned YTDB artifact, hence its
     * class-name lookup rather than a compile-time dependency.
     */
    override fun applyPrior(): Set<Class<out TraversalStrategy.ProviderOptimizationStrategy>> =
        setOf(
            YTDBGraphStepStrategy::class.java,
            YTDBGraphCountStrategy::class.java,
            YTDBGraphMatchStepStrategy::class.java,
            optionalProviderStrategy(GREMLIN_TO_MATCH_STRATEGY)
        ).filterNotNull().toSet()

    private fun rewriteHasStep(traversal: Traversal.Admin<*, *>, step: HasStep<*>) {
        val eligible = step.hasContainers.filter(::isEligible)
        if (eligible.isEmpty()) return

        val index = traversal.steps.indexOf(step)
        val labels = step.labels.toList()
        eligible.forEach(step::removeHasContainer)
        val replacements = eligible.map { replacementStep(traversal, it) }

        if (step.hasContainers.isEmpty()) {
            removeStep(traversal, step)
            replacements.forEachIndexed { offset, replacement ->
                addStepAt(traversal, index + offset, replacement)
            }
            labels.forEach { label -> replacements.first().addLabel(label) }
        } else {
            replacements.forEachIndexed { offset, replacement ->
                addStepAt(traversal, index + 1 + offset, replacement)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addStepAt(
        traversal: Traversal.Admin<*, *>,
        index: Int,
        step: Step<*, *>
    ) {
        (traversal as Traversal.Admin<Any, Any>).addStep<Any, Any>(index, step)
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeStep(traversal: Traversal.Admin<*, *>, step: Step<*, *>) {
        (traversal as Traversal.Admin<Any, Any>).removeStep<Any, Any>(step)
    }

    @Suppress("UNCHECKED_CAST")
    private fun replacementStep(
        traversal: Traversal.Admin<*, *>,
        container: HasContainer
    ): TraversalFilterStep<Any> {
        val filter = GremlinBlock
            .caseInsensitiveStringEqual(container.key, container.predicate.value as String)
            .scalarFilterTraversal()
        return TraversalFilterStep<Any>(traversal, filter as Traversal<Any, *>)
    }

    private fun isEligible(container: HasContainer): Boolean {
        val key = container.key
        val predicate = container.predicate
        return key != T.id.accessor &&
            key != T.label.accessor &&
            predicate.biPredicate == Compare.eq &&
            predicate.value is String
    }

    private fun isMatchBoundary(step: Step<*, *>): Boolean =
        MATCH_BOUNDARY_CLASS?.isAssignableFrom(step.javaClass) == true

    companion object {
        private const val GREMLIN_TO_MATCH_STRATEGY =
            "com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy"
        private const val MATCH_BOUNDARY =
            "com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep"

        private val INSTANCE = GremlinCaseInsensitiveHasStrategy()
        private val MATCH_BOUNDARY_CLASS: Class<*>? by lazy {
            runCatching { Class.forName(MATCH_BOUNDARY) }.getOrNull()
        }

        fun instance(): GremlinCaseInsensitiveHasStrategy = INSTANCE

        private fun optionalProviderStrategy(name: String): Class<out TraversalStrategy.ProviderOptimizationStrategy>? =
            runCatching {
                Class.forName(name).asSubclass(TraversalStrategy.ProviderOptimizationStrategy::class.java)
            }.getOrNull()
    }
}
