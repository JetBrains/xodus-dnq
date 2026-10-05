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
package jetbrains.exodus.query

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/**
 * DNQ is the query library under test. Its `NodeFactory` must expose safe property filters before link checks.
 * Sequential mixed `AND` steps let YouTrackDB use its SQL (Structured Query Language) engine and an index.
 * This class checks emission and membership through `QueryEngine`, not the executed access plan.
 *
 * Three issues differ in name, verified status, and board links. One issue belongs to two boards.
 * This fixture checks selective conjunctions and set operations without adding or losing members.
 * MATCH is YouTrackDB's pattern query mode translated from Gremlin.
 * The parameterized test sets the session flag explicitly for both MATCH modes.
 */
@RunWith(Parameterized::class)
class MixedAndEngineTest(private val match: Boolean) : OTestMixin {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "MATCH={0}")
        fun modes() = listOf(arrayOf(true), arrayOf(false))
    }
    @Rule @JvmField val db = InMemoryYouTrackDB()
    override val youTrackDb get() = db

    /**
     * Permutes nested name, verified, and board-link filters through `NodeFactory.and`.
     * The query and its intersection with all issues must return only `issue1`.
     * Subtracting the query from all issues must return `issue2` and `issue3`.
     * This fails if emission retains `and()` instead of a property prefix, or set-operation membership changes.
     */
    @Test
    fun `node conjunction exposes scalar prefix and keeps set operation membership`() {
        val data = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToBoard(data.issue1, data.board1)
            tx.addIssueToBoard(data.issue1, data.board2)
            tx.addIssueToBoard(data.issue2, data.board1)
            data.issue1.setProperty("verified", true)
            data.issue2.setProperty("verified", false)
            data.issue3.setProperty("verified", true)
        }
        val engine = QueryEngine(null, youTrackDb.store).apply { sortEngine = SortEngine(this) }
        withStoreTx { tx ->
            (tx as YTDBStoreTransactionImpl).activeYtdbSession().configuration!!.setValue(
                GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, match
            )
            val link = NodeFactory.hasLinkTo(Issues.Links.ON_BOARD, data.board1)
            val name = NodeFactory.propEqual("name", "issue1")
            val verified = NodeFactory.propEqual("verified", true)
            val operands = listOf(link, name, verified)
            for (a in operands.indices) for (b in operands.indices.filter { it != a }) {
                val node = NodeFactory.and(operands[a], NodeFactory.and(operands[b], operands[3 - a - b]))
                val result = engine.query(Issues.CLASS, node) as YTDBEntityIterable
                assertNamesExactly(result, "issue1")
                assertNamesExactly(result.intersect(tx.getAll(Issues.CLASS)), "issue1")
                assertNamesExactly(tx.getAll(Issues.CLASS).minus(result), "issue2", "issue3")
                val traversal = result.traversal()
                try {
                    assertEquals(listOf("V", "has", "has", "where", "hasLabel"),
                        traversal.asAdmin().bytecode.stepInstructions.map { it.operator },
                        "NodeFactory mixed AND must expose its scalar prefix")
                } finally {
                    traversal.close()
                }
            }
        }
    }
}
