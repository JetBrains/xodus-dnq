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
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy

/**
 * Test-only diagnostic guard for the Gremlin-to-MATCH migration.
 *
 * The guard is disabled unless [PROPERTY] is set to `true` in the test JVM. When enabled, it is
 * attached to every root traversal produced by [GremlinQuery]. It runs after YouTrackDB's
 * [GremlinToMatchStrategy] and rejects a traversal that did not become a MATCH boundary step.
 * Anonymous child traversals are not attached to the guard independently.
 */
internal object GremlinQueryTranslationGuard {
    const val PROPERTY = "dnq.query.requireMatchTranslation"

    val enabled: Boolean
        get() = System.getProperty(PROPERTY) == "true"

    fun attach(traversal: GraphTraversal<*, *>, queryShape: String) {
        traversal.asAdmin().strategies.addStrategies(RequireMatchTranslationStrategy(queryShape))
    }

    private class RequireMatchTranslationStrategy(
        private val queryShape: String
    ) : AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>(),
        TraversalStrategy.ProviderOptimizationStrategy {

        override fun apply(traversal: Traversal.Admin<*, *>) {
            if (traversal.steps.none { it is AbstractMatchPlanStep<*, *> }) {
                throw IllegalStateException(
                    "GremlinQuery was not translated to YouTrackDB MATCH. " +
                        "Query shape: $queryShape; final traversal: $traversal"
                )
            }
        }

        override fun applyPrior(): Set<Class<out TraversalStrategy.ProviderOptimizationStrategy>> =
            setOf(GremlinToMatchStrategy::class.java)
    }
}
