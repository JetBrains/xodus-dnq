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
package jetbrains.exodus.entitystore.youtrackdb.iterate

import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.testutil.Boards
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues
import jetbrains.exodus.entitystore.youtrackdb.testutil.OTestMixin
import jetbrains.exodus.entitystore.youtrackdb.testutil.Projects
import jetbrains.exodus.entitystore.youtrackdb.testutil.name
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `EntityIterable.findLinks(entities, linkName)` must return **the subset of the receiver** whose
 * `linkName` link points into `entities` — the Xodus contract implemented by `FilterLinksIterable`
 * (`return new FilterLinksIterable(txn, linkId, this, entities)`), which filters `this`.
 *
 * The YouTrackDB implementation used to build only `entities → in(linkName)` and ignore the
 * receiver entirely, silently returning a superset (every filter on the left-hand side was lost).
 */
class YTDBFindLinksTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    /**
     * issue1 (high) → board1, issue2 (low) → board1, issue3 (high) → board2.
     * board3 has no issues.
     */
    private fun givenIssuesOnBoards() = givenTestCase().also { test ->
        withStoreTx { tx ->
            test.issue1.setProperty(Issues.Props.PRIORITY, "high")
            test.issue2.setProperty(Issues.Props.PRIORITY, "low")
            test.issue3.setProperty(Issues.Props.PRIORITY, "high")
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue3, test.board2)
        }
    }

    @Test
    fun `receiver condition survives findLinks`() {
        givenIssuesOnBoards()

        withStoreTx { tx ->
            // Every issue is on some board, so the in-link set alone is {issue1, issue2, issue3};
            // only the receiver's condition removes issue2.
            val highPriority = YTDBEntityIterable.where(
                Issues.CLASS, tx.getStore(), GremlinBlock.PropEqual(Issues.Props.PRIORITY, "high")
            )
            val allBoards = YTDBEntityIterable.where(Boards.CLASS, tx.getStore(), GremlinBlock.All)

            assertNamesExactly(highPriority.findLinks(allBoards, Issues.Links.ON_BOARD), "issue1", "issue3")
        }
    }

    @Test
    fun `findLinks returns the intersection of the receiver and the in-link set`() {
        val test = givenIssuesOnBoards()

        withStoreTx { tx ->
            // receiver = {issue1, issue3}, in-link set of board1 = {issue1, issue2} → {issue1}
            val highPriority = YTDBEntityIterable.where(
                Issues.CLASS, tx.getStore(), GremlinBlock.PropEqual(Issues.Props.PRIORITY, "high")
            )
            val board1 = tx.find(Boards.CLASS, "name", test.board1.name())

            assertNamesExactly(highPriority.findLinks(board1, Issues.Links.ON_BOARD), "issue1")
        }
    }

    @Test
    fun `union-shaped receiver keeps both branches' conditions`() {
        val test = givenIssuesOnBoards()

        withStoreTx { tx ->
            // Two differently-labelled operands cannot be merged into a single condition, so the
            // receiver stays a real UnionAll — the shape the reports call site uses
            // (XdRealEvent ∪ XdImportedEvent).
            // receiver = {issue1, issue3} ∪ {project1, project2, project3}
            // in-link set of board1 = {issue1, issue2}  → {issue1}
            val highPriority = YTDBEntityIterable.where(
                Issues.CLASS, tx.getStore(), GremlinBlock.PropEqual(Issues.Props.PRIORITY, "high")
            )
            val allProjects = YTDBEntityIterable.where(Projects.CLASS, tx.getStore(), GremlinBlock.All)
            val receiver = highPriority.union(allProjects)
            val board1 = tx.find(Boards.CLASS, "name", test.board1.name())

            assertNamesExactly(receiver.findLinks(board1, Issues.Links.ON_BOARD), "issue1")
        }
    }

    @Test
    fun `findLinks deduplicates entities reachable through several links`() {
        val test = givenIssuesOnBoards()
        withStoreTx { tx ->
            // issue1 is now on board1 AND board2 — two edges into the entities set.
            tx.addIssueToBoard(test.issue1, test.board2)
        }

        withStoreTx { tx ->
            val allIssues = YTDBEntityIterable.where(Issues.CLASS, tx.getStore(), GremlinBlock.All)
            val allBoards = YTDBEntityIterable.where(Boards.CLASS, tx.getStore(), GremlinBlock.All)

            assertNamesExactly(
                allIssues.findLinks(allBoards, Issues.Links.ON_BOARD),
                "issue1", "issue2", "issue3"
            )
        }
    }

    /**
     * The receiver's **type** constraint is a filter like any other, and it is the only thing that can
     * separate two source types that share a link name: edge classes are named `<linkName>_link` only
     * (`YTDBVertexEntity.edgeClassName`), so `Boards.Links.HAS_ISSUE` and `Projects.Links.HAS_ISSUE` —
     * both `"HasIssue"` — are the *same* edge class, and `in("HasIssue_link")` from an issue yields its
     * board **and** its project.
     *
     * This is the shape of the `CommentSecurityService` call site
     * (`commentsIterable.findLinks(user.groups…, "permittedGroup")`), where a link name is shared by
     * several YouTrack types and the receiver's type is what scopes the permission decision.
     */
    @Test
    fun `receiver type constraint survives findLinks when two source types share a link name`() {
        val test = givenTestCase()
        withStoreTx { tx ->
            // issue1 is reachable over "HasIssue" from a Board AND from a Project.
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToProject(test.issue1, test.project1)
        }

        withStoreTx { tx ->
            // receiver: every named Board = {board1, board2, board3}
            val namedBoards = tx.findWithProp(Boards.CLASS, "name")
            val issue1 = tx.find(Issues.CLASS, "name", test.issue1.name())

            // in-link set of issue1 over "HasIssue" = {board1, project1}; intersecting with the receiver
            // must drop project1, which is not a Board.
            val result = namedBoards.findLinks(issue1, Boards.Links.HAS_ISSUE) as YTDBEntityIterable

            assertNamesExactly(result, "board1")
            // The receiver's hasLabel("Board") must reach the traversal as the last filter — it is the
            // only step that can eliminate project1.
            checkGremlin(
                result,
                """g.V().has("name","issue1").hasLabel("Issue").in("HasIssue_link").has("name").hasLabel("Board").dedup()"""
            )
        }
    }

    /**
     * XD-1292 / audit #7 — `findLinks` must normalise its `entities` operand with `unwrap()` before
     * casting it, so a link read can be passed as the operand.
     *
     * **The operand must be non-empty or the row is vacuous.** The naive choice — a *board's*
     * `getLinks(ON_BOARD)` — is empty: `ON_BOARD` is Issue→Board and `getLinks` follows outgoing edges
     * only, so any "doesn't throw" assertion over it would be satisfied by an implementation that
     * simply dropped the operand. The operand here is `issue1.getLinks(ON_BOARD)` = the boards issue1
     * is on = `[board1]`, asserted non-empty before use.
     *
     * The expectation also discriminates the *operand* rather than merely "no longer throws":
     * `issue3` is on board2, so a fix that widened the operand into "all boards" would include it.
     */
    @Test
    fun `findLinks accepts a link-read iterable as its entities operand`() {
        val test = givenIssuesOnBoards()

        withStoreTx { tx ->
            val boardsOfIssue1 = test.issue1.getLinks(Issues.Links.ON_BOARD)
            // precondition: a non-empty, non-identity operand (a YTDBVertexEntityIterable)
            assertEquals(listOf("board1"), boardsOfIssue1.map { it.getProperty("name") })

            val allIssues = YTDBEntityIterable.where(Issues.CLASS, tx.getStore(), GremlinBlock.All)
            val onBoard1 = allIssues.findLinks(boardsOfIssue1, Issues.Links.ON_BOARD)

            // issue1 and issue2 are on board1; issue3 is on board2 and must be absent
            assertEquals(setOf("issue1", "issue2"), onBoard1.map { it.getProperty("name") }.toSet())
        }
    }

    /**
     * XD-1292 / B1 — the one `YTDBStoreTransactionImpl.findLinks` overload reachable from the public
     * session API (`SessionQueryMixin` forwards `entities` unchanged) must normalise its operand too.
     * Its own `entities.asYTDBIterable()` throws from `YTDBCasts` for a link-read operand today.
     */
    @Test
    fun `transaction findLinks accepts a link-read iterable as its entities operand`() {
        val test = givenIssuesOnBoards()

        withStoreTx { tx ->
            val boardsOfIssue1 = test.issue1.getLinks(Issues.Links.ON_BOARD)
            assertEquals(listOf("board1"), boardsOfIssue1.map { it.getProperty("name") })

            val issues = tx.findLinks(Issues.CLASS, boardsOfIssue1, Issues.Links.ON_BOARD, true)

            assertEquals(setOf("issue1", "issue2"), issues.map { it.getProperty("name") }.toSet())
        }
    }

    @Test
    fun `findLinks short-circuits on EMPTY`() {
        givenIssuesOnBoards()

        withStoreTx { tx ->
            val allIssues = YTDBEntityIterable.where(Issues.CLASS, tx.getStore(), GremlinBlock.All)

            assertSame(
                YTDBEntityIterable.EMPTY,
                allIssues.findLinks(YTDBEntityIterable.EMPTY, Issues.Links.ON_BOARD)
            )
            assertSame(
                YTDBEntityIterable.EMPTY,
                YTDBEntityIterable.EMPTY.findLinks(allIssues, Issues.Links.ON_BOARD)
            )
        }
    }

    private fun checkGremlin(iterable: YTDBEntityIterable, expectedGremlin: String) {
        val script = GroovyTranslator.of("g")
            .translate(iterable.traversal().asAdmin().bytecode)
            .script
        // Strip the polymorphicQuery traversal source config — see YTDBGremlinEntityIterableTest.gremlinOf.
        val actual = script.replaceFirst(
            Regex("""^g\.withStrategies\(new OptionsStrategy\(polymorphicQuery: (true|false)\)\)\."""),
            "g."
        )
        assertEquals(expectedGremlin, actual)
    }
}
