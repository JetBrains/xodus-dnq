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
import org.apache.tinkerpop.gremlin.process.traversal.Order
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies
import org.junit.Test

class GremlinCaseInsensitiveOrderStrategyTest {

    private val strategy = GremlinCaseInsensitiveOrderStrategy.instance()

    @Test
    fun `rewrites direct and linked property orders and is idempotent`() {
        val direct = `__`.start<Any>().order().by("name", Order.desc)
        val linked = `__`.start<Any>().order()
            .by(`__`.out("manager").values<Any>("name"), Order.asc)

        strategy.apply(direct.asAdmin())
        strategy.apply(linked.asAdmin())

        val directOrder = direct.asAdmin().steps.filterIsInstance<OrderGlobalStep<*, *>>().single()
        val directComparator = directOrder.comparators.single()
        assertThat(directComparator.value1).isEqualTo(Order.desc)
        assertThat(directComparator.value0.steps.map { it::class.java.simpleName })
            .containsExactly("PropertiesStep", "ChooseStep")
            .inOrder()

        val linkedOrder = linked.asAdmin().steps.filterIsInstance<OrderGlobalStep<*, *>>().single()
        val linkedComparator = linkedOrder.comparators.single()
        assertThat(linkedComparator.value1).isEqualTo(Order.asc)
        assertThat(linkedComparator.value0.steps.map { it::class.java.simpleName })
            .containsExactly("VertexStep", "PropertiesStep", "ChooseStep")
            .inOrder()

        strategy.apply(direct.asAdmin())
        assertThat(directOrder.comparators.single().value0)
            .isSameInstanceAs(directComparator.value0)
    }

    @Test
    fun `leaves non-property order traversal unchanged`() {
        val traversal = `__`.start<Any>().order()
            .by(`__`.constant<Any>("fixed"), Order.asc)
        val order = traversal.asAdmin().steps.filterIsInstance<OrderGlobalStep<*, *>>().single()
        val original = order.comparators.single().value0

        strategy.apply(traversal.asAdmin())

        assertThat(order.comparators.single().value0).isSameInstanceAs(original)
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
        assertThat(ordered.indexOf(GremlinCaseInsensitiveOrderStrategy::class.java))
            .isGreaterThan(ordered.indexOf(YTDBGraphStepStrategy::class.java))
        assertThat(ordered.indexOf(GremlinCaseInsensitiveOrderStrategy::class.java))
            .isGreaterThan(ordered.indexOf(YTDBGraphCountStrategy::class.java))
        assertThat(ordered.indexOf(GremlinCaseInsensitiveOrderStrategy::class.java))
            .isGreaterThan(ordered.indexOf(YTDBGraphMatchStepStrategy::class.java))
    }
}
