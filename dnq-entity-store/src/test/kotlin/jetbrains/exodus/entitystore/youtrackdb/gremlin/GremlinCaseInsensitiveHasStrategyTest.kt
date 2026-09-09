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
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphCountStrategy
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphMatchStepStrategy
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies
import org.apache.tinkerpop.gremlin.structure.T
import org.junit.Test

class GremlinCaseInsensitiveHasStrategyTest {

    private val strategy = GremlinCaseInsensitiveHasStrategy.instance()

    @Test
    fun `rewrites only direct non-token string equality`() {
        val traversal = `__`.start<Any>()
        val admin = traversal.asAdmin()
        admin.addStep(
            HasStep(
                admin,
                HasContainer("name", P.eq("Lev")),
                HasContainer("age", P.eq(7)),
                HasContainer("status", P.within("open", "closed")),
                HasContainer(T.label.accessor, P.eq("User")),
            )
        )

        strategy.apply(admin)

        assertThat(admin.steps).hasSize(2)
        assertThat(admin.steps[0]).isInstanceOf(HasStep::class.java)
        assertThat(admin.steps[1]).isInstanceOf(TraversalFilterStep::class.java)
        val remaining = (admin.steps[0] as HasStep<*>).hasContainers
        assertThat(remaining.map { it.toString() }).containsExactly(
            "age.eq(7)",
            "status.within([open, closed])",
            "~label.eq(User)",
        ).inOrder()
    }

    @Test
    fun `rewrites multiple eligible containers and is idempotent`() {
        val traversal = `__`.start<Any>()
        val admin = traversal.asAdmin()
        admin.addStep(
            HasStep(
                admin,
                HasContainer("firstName", P.eq("Lev")),
                HasContainer("lastName", P.eq("Volk")),
            )
        )

        strategy.apply(admin)
        val once = admin.steps.toList()
        strategy.apply(admin)

        assertThat(admin.steps).containsExactlyElementsIn(once).inOrder()
        assertThat(admin.steps).hasSize(2)
        assertThat(admin.steps.all { it is TraversalFilterStep<*> }).isTrue()
    }

    @Test
    fun `provider ordering runs fallback after current YTDB optimizers`() {
        assertThat(strategy.applyPrior()).containsAtLeast(
            YTDBGraphStepStrategy::class.java,
            YTDBGraphCountStrategy::class.java,
            YTDBGraphMatchStepStrategy::class.java,
        )

        val ordered = DefaultTraversalStrategies().addStrategies(
            strategy,
            YTDBGraphMatchStepStrategy.instance(),
            YTDBGraphCountStrategy.instance(),
            YTDBGraphStepStrategy.instance(),
        ).toList().map { it::class.java }
        assertThat(ordered.indexOf(GremlinCaseInsensitiveHasStrategy::class.java))
            .isGreaterThan(ordered.indexOf(YTDBGraphStepStrategy::class.java))
        assertThat(ordered.indexOf(GremlinCaseInsensitiveHasStrategy::class.java))
            .isGreaterThan(ordered.indexOf(YTDBGraphCountStrategy::class.java))
        assertThat(ordered.indexOf(GremlinCaseInsensitiveHasStrategy::class.java))
            .isGreaterThan(ordered.indexOf(YTDBGraphMatchStepStrategy::class.java))
    }
}
