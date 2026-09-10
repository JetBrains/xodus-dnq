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

import com.google.common.truth.Truth.assertThat
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.HasLabel
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.Sort
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.SortDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryShape
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator
import org.junit.Rule
import org.junit.Test

class GremlinQueryTranslationTest : OTestMixin {

    private val gremlinTranslator = GroovyTranslator.of("g")

    @Rule
    @JvmField
    val youTrackDbRule = InMemoryYouTrackDB()

    override val youTrackDb = youTrackDbRule

    @Test
    fun `hasLabel query is translated to MATCH`() {
        assertTranslationStatus(
            GremlinQuery.all.then(HasLabel("Issue")),
            expectedTranslated = true
        )
    }

    @Test
    fun `hasLabel query with sort is not translated to MATCH`() {
        assertTranslationStatus(
            GremlinQuery.all
                .then(HasLabel("Issue"))
                .then(Sort(Sort.ByProp("name"), SortDirection.ASC)),
            expectedTranslated = false
        )
    }

    @Test
    fun `adjacent property sorts translate as one MATCH order`() {
        val query = GremlinQuery.all
            .then(HasLabel("Issue"))
            .then(Sort(Sort.ByProp("type"), SortDirection.ASC))
            .then(Sort(Sort.ByProp("project"), SortDirection.ASC))

        assertThat(query).isInstanceOf(GremlinQuery.SortBy::class.java)
        assertThat((query as GremlinQuery.SortBy).sortBlocks).hasSize(2)
        assertTranslationStatus(query, expectedTranslated = false)
    }

    private fun assertTranslationStatus(query: GremlinQuery, expectedTranslated: Boolean) {
        withStoreTx { tx ->
            val traversal = query.start(tx.g()).asAdmin()
            val gremlinQuery = gremlinTranslator.translate(traversal.bytecode).script
            traversal.applyStrategies()

            val translated = traversal.steps.any { it is AbstractMatchPlanStep<*, *> }
            println(
                listOf(
                    "======",
                    "DNQ query: ${GremlinQueryShape.of(query)}",
                    "Gremlin query: $gremlinQuery",
                    "Status: ${if (translated) "translated" else "not translated"}",
                    ""
                ).joinToString("\n")
            )
            assertThat(translated).isEqualTo(expectedTranslated)
        }
    }
}
