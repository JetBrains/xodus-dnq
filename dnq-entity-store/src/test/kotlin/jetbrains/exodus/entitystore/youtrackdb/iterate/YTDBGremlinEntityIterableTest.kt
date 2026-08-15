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

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YTDBGremlinEntityIterableTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB()

    override val youTrackDb = orientDbRule

    @Test
    fun `property is null`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue1.setProperty("none", "n1")
        }

        // When
        withStoreTx { tx ->
            val issues = YTDBEntityIterable.where(
                Issues.CLASS, tx.getStore(), GremlinBlock.PropNull("none")
            )

            // Then
            checkGremlin(issues, """g.V().hasNot("none").hasLabel("Issue")""")
            assertNamesExactly(issues, "issue2", "issue3")
        }
    }

    @Test
    fun `no links`() {
        // Given
        val test = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToProject(test.issue1, test.project1)
        }

        // When
        withStoreTx { tx ->
            val issues = YTDBEntityIterable.where(
                Issues.CLASS, tx.getStore(),
                GremlinBlock.HasNoLink(Issues.Links.IN_PROJECT)
            )

            // Then
            checkGremlin(issues, """g.V().not(__.out("InProject_link")).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue2", "issue3")
        }
    }

    @Test
    fun `property equals`() {
        // Given
        val test = givenTestCase()
        withStoreTx { tx ->
            test.issue1.setProperty("opca", 300)
            test.issue2.setProperty("opca", 200)
            test.issue3.setProperty("opca", 300)
        }

        // When
        withStoreTx { tx ->
            val issues = YTDBEntityIterable.where(
                Issues.CLASS,
                tx.getStore(),
                GremlinBlock.PropEqual("opca", 300)
            )

            // Then
            checkGremlin(issues, """g.V().has("opca",(int) 300).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue3")
        }
    }

    @Test
    fun `union two iterables`() {
        // Given
        val test = givenTestCase()

        // When
        withStoreTx { tx ->
            val equal1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val equal2 = tx.find(Issues.CLASS, "name", test.issue2.name())

            val issues = equal1.union(equal2)

            // Then
            checkGremlin(issues as YTDBEntityIterable, """g.V().has("name",P.within(["issue1", "issue2"])).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `union of identical iterables is optimised to the condition itself`() {
        // Given
        val test = givenTestCase()

        // When
        withStoreTx { tx ->
            val equal1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val equal2 = tx.find(Issues.CLASS, "name", test.issue1.name())

            val issues = equal1.union(equal2)

            // Then
            checkGremlin(issues as YTDBEntityIterable, """g.V().has("name","issue1").hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1")
        }
    }

    @Test
    fun `intersect of identical iterables is optimised to the condition itself`() {
        // Given
        val test = givenTestCase()

        // When
        withStoreTx { tx ->
            val equal1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val equal2 = tx.find(Issues.CLASS, "name", test.issue1.name())

            val issues = equal1.intersect(equal2)

            // Then
            checkGremlin(issues as YTDBEntityIterable, """g.V().has("name","issue1").hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1")
        }
    }

    @Test
    fun `intersect two iterables`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue2.setProperty(Issues.Props.PRIORITY, "normal")
        }

        // When
        withStoreTx { tx ->
            val nameEqual = tx.find(Issues.CLASS, "name", test.issue2.name())
            val priorityEqual = tx.find(Issues.CLASS, Issues.Props.PRIORITY, "normal")
            val issues = nameEqual.intersect(priorityEqual)

            // Then
            checkGremlin(issues as YTDBEntityIterable, """g.V().has("name","issue2").has("priority","normal").hasLabel("Issue")""")
            assertNamesExactly(issues, "issue2")
            assertThat(issues.first().getProperty("priority")).isEqualTo("normal")
        }
    }

    @Test
    fun `intersect after union`() {
        val test = givenTestCase()

        withStoreTx {
            test.issue2.setProperty(Issues.Props.PRIORITY, "normal")
            test.issue3.setProperty(Issues.Props.PRIORITY, "normal")
        }

        withStoreTx { tx ->
            val i1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val i2and3 = tx.find(Issues.CLASS, "priority", "normal")

            val i1and2and3 = i1.union(i2and3)
            assertNamesExactly(i1and2and3, "issue1", "issue2", "issue3")

            val i3 = tx.find(Issues.CLASS, "name", test.issue3.name())
            assertNamesExactly(i3, "issue3")

            val i3only = i3.intersect(i1and2and3)
            // combineEfficient merges: And(PropEqual("name","issue3"), Or(PropEqual("name","issue1"),PropEqual("priority","normal")))
            checkGremlin(
                i3only as YTDBEntityIterable,
                """g.V().and(__.has("name","issue3"),__.or(__.has("name","issue1"),__.has("priority","normal"))).hasLabel("Issue")"""
            )
            assertNamesExactly(i3only, "issue3")
        }
    }

    @Test
    fun `intersect with nested intersect`() {
        val test = givenTestCase()

        withStoreTx {
            test.issue2.setProperty(Issues.Props.PRIORITY, "normal")
            test.issue3.setProperty(Issues.Props.PRIORITY, "normal")
        }

        withStoreTx { tx ->
            val i1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val i2 = tx.find(Issues.CLASS, "name", test.issue2.name())
            val i2or3 = tx.find(Issues.CLASS, "priority", "normal")

            val i2only = i2.intersect(i2or3)
            val i1or2 = i1.union(i2only)
            val i2onlyAgain = i1or2.intersect(i2)

            assertNamesExactly(i2onlyAgain, "issue2")
        }
    }

    @Test
    fun `concat iterables selected by properties`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
        }

        // When
        withStoreTx { tx ->
            val issue1 = tx.find(Issues.CLASS, "name", "issue1")
            val issue2 = tx.find(Issues.CLASS, "name", "issue2")
            val concat = issue1.concat(issue2)

            // Then
            checkGremlin(
                concat as YTDBEntityIterable,
                """g.union(__.V().has("name","issue1").hasLabel("Issue"),__.V().has("name","issue2").hasLabel("Issue"))"""
            )
            assertNamesExactlyInOrder(concat, "issue1", "issue2")

            val concatMore = concat.concat(issue1)
            assertNamesExactlyInOrder(concatMore, "issue1", "issue2", "issue1")


            val concatEvenMore = concatMore.concat(issue2)
            assertNamesExactlyInOrder(concatEvenMore, "issue1", "issue2", "issue1", "issue2")
        }
    }

    @Test
    fun `find links`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
        }

        // When
        withStoreTx { tx ->
            val issues = tx.findLinks(
                Issues.CLASS,
                test.board1,
                Issues.Links.ON_BOARD
            ) as YTDBEntityIterable

            // Then
            checkGremlinPattern(issues, """g.V({rid}).hasLabel("Board").in("OnBoard_link").hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `concat iterables selected by links`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
        }

        // When
        withStoreTx { tx ->
            val issuesOnBoard1 = tx.findLinks(Issues.CLASS, test.board1, Issues.Links.ON_BOARD)
            val issuesOnBoard2 = tx.findLinks(Issues.CLASS, test.board2, Issues.Links.ON_BOARD)
            val concat = issuesOnBoard1.concat(issuesOnBoard2)

            // Then
            // concat produces UnionAll([board1_query, board2_query]); each ByIds uses direct V(ids) in continueTraversal
            checkGremlinPattern(
                concat as YTDBEntityIterable,
                """g.union(__.V({rid}).hasLabel("Board").in("OnBoard_link").hasLabel("Issue"),__.V({rid}).hasLabel("Board").in("OnBoard_link").hasLabel("Issue"))"""
            )
            // Link traversal order is not guaranteed — use unordered check
            assertNamesExactly(concat, "issue1", "issue2", "issue1")
        }
    }

    @Test
    fun distinct() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue3, test.board1)
        }

        // When
        withStoreTx { tx ->
            val issuesOnBoard1 = tx.findLinks(Issues.CLASS, test.board1, Issues.Links.ON_BOARD)
            val issuesOnBoard2 = tx.findLinks(Issues.CLASS, test.board2, Issues.Links.ON_BOARD)
            val issues = issuesOnBoard1.union(issuesOnBoard2)
            val issuesDistinct = issues.distinct()

            // Then
            // union fires O4 → g.V(rid1,rid2).in(...).dedup(); distinct() appends another dedup via Order.of squashing
            checkGremlinPattern(
                issuesDistinct as YTDBEntityIterable,
                """g.V({rid},{rid}).in("OnBoard_link").hasLabel("Issue").dedup().dedup()"""
            )
            assertThat(issuesDistinct).hasSize(3)
            assertNamesExactly(issuesDistinct, "issue1", "issue2", "issue3")
        }
    }

    /**
     * A link read unwrapped into a query must declare the ascending-`localEntityId` order that
     * `YTDBVertexEntity.getLinks` materializes, otherwise every derived operation
     * (intersect/skip/take/...) silently loses it. Shape-only guard: no dependency on physical
     * insertion order.
     */
    @Test
    fun `unwrapped link read declares the local id order`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
        }

        // When
        withStoreTx {
            val unwrapped = test.board1.getLinks(Boards.Links.HAS_ISSUE).unwrap() as YTDBEntityIterable

            // Then
            checkGremlinPattern(
                unwrapped,
                """g.V({rid}).hasLabel("Board").out("HasIssue_link").order().by(__.values("localEntityId").count(),Order.desc).by(__.values("localEntityId").fold(),Order.asc)"""
            )
        }
    }

    @Test
    fun `union of findLinks queries merges source vertices into a single traversal`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)  // issue1 on both boards — tests dedup
        }

        // When
        withStoreTx { tx ->
            val issuesOnBoard1 = tx.findLinks(Issues.CLASS, test.board1, Issues.Links.ON_BOARD)
            val issuesOnBoard2 = tx.findLinks(Issues.CLASS, test.board2, Issues.Links.ON_BOARD)

            // O4: both sides are Labeled(FollowLink(ByIds([rid]), IN, "OnBoard"), "Issue")
            // Merges into: Order(Labeled(FollowLink(ByIds([rid1,rid2]), IN, "OnBoard"), "Issue"), Dedup)
            // Gremlin: g.V(rid1,rid2).in("OnBoard_link").hasLabel("Issue").dedup()
            // O4: Labeled(FollowLink(ByIds([rid1,rid2]), IN, "OnBoard"), "Issue") + Dedup
            val issues = issuesOnBoard1.union(issuesOnBoard2) as YTDBEntityIterable
            checkGremlinPattern(issues, """g.V({rid},{rid}).in("OnBoard_link").hasLabel("Issue").dedup()""")
            // issue1 appears in both boards but must be deduplicated
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `union of condition-based findLinks queries merges inner traversal without RIDs`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board2)
            tx.addIssueToBoard(test.issue1, test.board2)  // issue1 on both boards — tests dedup
        }

        // When
        withStoreTx { tx ->
            val byBoard1 = tx.findLinks(Issues.CLASS, tx.find(Boards.CLASS, "name", test.board1.name()), Issues.Links.ON_BOARD)
            val byBoard2 = tx.findLinks(Issues.CLASS, tx.find(Boards.CLASS, "name", test.board2.name()), Issues.Links.ON_BOARD)

            // O4: both sides are Labeled(FollowLink(Labeled(Condition,"Board"),IN,"OnBoard"),"Issue")
            // Inner sources are condition-based (not ByIds), so O4 merges them into a single traversal
            // with no dynamic RIDs. The merged inner becomes:
            //   Labeled(Where(Or(PropEqual("name","board1"),PropEqual("name","board2"))),"Board")
            val issues = byBoard1.union(byBoard2) as YTDBEntityIterable
            checkGremlin(
                issues,
                """g.V().has("name",P.within(["board1", "board2"])).hasLabel("Board").in("OnBoard_link").hasLabel("Issue").dedup()"""
            )
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `union of three condition-based findLinks queries fully optimises via O17 chained O4`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board2)
            tx.addIssueToBoard(test.issue3, test.board3)
        }

        // When
        withStoreTx { tx ->
            val byBoard1 = tx.findLinks(Issues.CLASS, tx.find(Boards.CLASS, "name", test.board1.name()), Issues.Links.ON_BOARD)
            val byBoard2 = tx.findLinks(Issues.CLASS, tx.find(Boards.CLASS, "name", test.board2.name()), Issues.Links.ON_BOARD)
            val byBoard3 = tx.findLinks(Issues.CLASS, tx.find(Boards.CLASS, "name", test.board3.name()), Issues.Links.ON_BOARD)

            // Step 1: byBoard1.union(byBoard2) → O4 fires → Order(Labeled(FollowLink(PropWithin12)), Dedup)
            // Step 2: .union(byBoard3) → O17 strips the Order(Dedup), O4 fires again on the inner
            //   Labeled(FollowLink(PropWithin12)) + Labeled(FollowLink(PropEqual("name","board3")))
            //   → merges sources into PropWithin(["board1","board2","board3"]).
            //   O17 returns the O4 result as-is (it already carries Dedup).
            // Result: single traversal with PropWithin, no g.union().
            val issues = byBoard1.union(byBoard2).union(byBoard3) as YTDBEntityIterable
            checkGremlin(
                issues,
                """g.V().has("name",P.within(["board1", "board2", "board3"])).hasLabel("Board").in("OnBoard_link").hasLabel("Issue").dedup()"""
            )
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `cascaded union fallbacks flatten into single UnionAll`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            // Sliced queries cannot be combined by combineEfficient → both unions fall back.
            // First fallback: Order(UnionAll([skip1, skip2]), Dedup)
            // Second fallback: O1 detects Order(UnionAll, Dedup) shape and flattens into
            //   Order(UnionAll([skip1, skip2, skip3]), Dedup) — a single g.union(...).dedup()
            val skip1 = tx.getAll(Issues.CLASS).skip(1)
            val skip2 = tx.getAll(Issues.CLASS).skip(2)
            val skip3 = tx.getAll(Issues.CLASS).skip(3)
            val issues = skip1.union(skip2).union(skip3) as YTDBEntityIterable
            checkGremlin(
                issues,
                """g.union(__.V().hasLabel("Issue").skip(1L),__.V().hasLabel("Issue").skip(2L),__.V().hasLabel("Issue").skip(3L)).dedup()"""
            )
        }
    }

    @Test
    fun `union of findLinks queries with different link names does not optimise`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToProject(test.issue2, test.project1)
        }

        // When
        withStoreTx { tx ->
            val byBoard = tx.findLinks(Issues.CLASS, tx.find(Boards.CLASS, "name", test.board1.name()), Issues.Links.ON_BOARD)
            val byProject = tx.findLinks(Issues.CLASS, tx.find(Projects.CLASS, "name", test.project1.name()), Issues.Links.IN_PROJECT)

            // O4 check: same label ("Issue"), same direction (IN), but different link names ("OnBoard" vs "InProject")
            // → O4 does not fire, falls back to g.union(...)
            val issues = byBoard.union(byProject) as YTDBEntityIterable
            checkGremlin(
                issues,
                """g.union(__.V().has("name","board1").hasLabel("Board").in("OnBoard_link").hasLabel("Issue"),__.V().has("name","project1").hasLabel("Project").in("InProject_link").hasLabel("Issue")).dedup()"""
            )
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `all minus find by property`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue1.setProperty("complex", "true")
            test.issue2.setProperty("complex", "true")
        }

        // When
        withStoreTx { tx ->
            val issues = tx.getAll(Issues.CLASS)
            val complexIssues = tx.find(Issues.CLASS, "complex", "true")
            val simpleIssues = issues.minus(complexIssues)

            // Then
            checkGremlin(simpleIssues as YTDBEntityIterable, """g.V().not(__.has("complex","true")).hasLabel("Issue")""")
            assertNamesExactly(simpleIssues, "issue3")
        }
    }

    @Test
    fun `find by property minus find by property`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue1.setProperty("complex", "true")
            test.issue1.setProperty("blocked", "true")

            test.issue2.setProperty("complex", "true")
            test.issue2.setProperty("blocked", "false")

            test.issue3.setProperty("complex", "false")
            test.issue3.setProperty("blocked", "true")

        }

        // When
        withStoreTx { tx ->
            val complexIssues = tx.find(Issues.CLASS, "complex", "true")
            val blockedIssues = tx.find(Issues.CLASS, "blocked", "true")
            val complexUnblockedIssues = complexIssues.minus(blockedIssues)

            // Then
            checkGremlin(
                complexUnblockedIssues as YTDBEntityIterable,
                """g.V().and(__.has("complex","true"),__.not(__.has("blocked","true"))).hasLabel("Issue")"""
            )
            assertNamesExactly(complexUnblockedIssues, "issue2")
        }
    }

    @Test
    fun `find by link minus find by link`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue3, test.board1)
        }

        // When
        withStoreTx { tx ->
            val issuesOnBoard1 = tx.findLinks(Issues.CLASS, test.board1, Issues.Links.ON_BOARD)
            val issuesOnBoard2 = tx.findLinks(Issues.CLASS, test.board2, Issues.Links.ON_BOARD)

            val issues = issuesOnBoard1.minus(issuesOnBoard2)

            // Then
            // Falls back to Aggregate: right (board2) collected first, then left (board1) filtered against it.
            // right.startTraversal → g.V(rid_board2).hasLabel("Board").in(...).hasLabel("Issue")
            // left.continueTraversal → .V(rid_board1).hasLabel("Board").in(...).hasLabel("Issue")  (direct by-id)
            checkGremlinPattern(
                issues as YTDBEntityIterable,
                """g.V({rid}).hasLabel("Board").in("OnBoard_link").hasLabel("Issue").aggregate("aggr_0").fold().V({rid}).hasLabel("Board").in("OnBoard_link").hasLabel("Issue").where(P.without(["aggr_0"]))"""
            )
            assertNamesExactly(issues, "issue2", "issue3")
        }
    }


    @Test
    fun `iterable skip 1`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val issues = tx.sort(Issues.CLASS, "name", true).skip(1)

            // Then
            checkGremlin(
                issues as YTDBEntityIterable,
                "g.V().hasLabel(\"Issue\").order().by(__.values(\"name\").count(),Order.desc).by(__.values(\"name\").fold(),Order.asc).skip(1L)"
            )
            assertNamesExactlyInOrder(issues, "issue2", "issue3")
        }
    }

    @Test
    fun `iterable take 2`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val issues = tx.sort(Issues.CLASS, "name", true).take(2)

            // Then
            checkGremlin(
                issues as YTDBEntityIterable,
                "g.V().hasLabel(\"Issue\").order().by(__.values(\"name\").count(),Order.desc).by(__.values(\"name\").fold(),Order.asc).limit(2L)"
            )
            assertNamesExactlyInOrder(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `iterable skip 1 and take 2`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val issues = tx.sort(Issues.CLASS, "name", true).skip(1).take(2)

            // Then
            checkGremlin(
                issues as YTDBEntityIterable,
                "g.V().hasLabel(\"Issue\").order().by(__.values(\"name\").count(),Order.desc).by(__.values(\"name\").fold(),Order.asc).skip(1L).limit(2L)"
            )
            assertNamesExactlyInOrder(issues, "issue2", "issue3")
        }
    }

    @Test
    fun `iterable take 0`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = tx.sort(Issues.CLASS, "name", true).take(0)

            assertThat(issues).isEmpty()
        }
    }

    @Test
    fun `iterable skip 0`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = tx.sort(Issues.CLASS, "name", true).skip(0)

            assertNamesExactlyInOrder(issues, "issue1", "issue2", "issue3")
        }
    }

    /**
     * XD-1292 / audit #9 — [jetbrains.exodus.entitystore.EntityIterator.skip] must return the value
     * of `hasNext()`, not `true`.
     *
     * **Every assertion obtains a FRESH `iterator()`, and that is load-bearing, not hygiene:** after
     * the fix `skip` ends in `hasNext()`, and `YTDBEntityIterator.hasNext()` **disposes** the
     * traversal on exhaustion. On one shared iterator the `skip(3)` row would leave a disposed,
     * fully-consumed iterator, so a following `skip(2)` would observe `false` instead of `true` — the
     * row that actually discriminates the defect would look wrong for an unrelated reason. Do not
     * "simplify" this back to a single shared iterator.
     */
    @Test
    fun `iterator skip returns hasNext`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = YTDBEntityIterable.where(Issues.CLASS, tx.getStore(), GremlinBlock.All)
            assertEquals(3L, issues.size())

            // skipping to exact exhaustion: nothing is left
            assertEquals(false, issues.iterator().skip(3))
            // one element left
            assertEquals(true, issues.iterator().skip(2))
            // skipping past the end: nothing is left
            assertEquals(false, issues.iterator().skip(4))

            // negative n is a no-op: nothing is consumed, and the answer is hasNext()
            val nonEmpty = issues.iterator()
            assertEquals(true, nonEmpty.skip(-1))
            assertEquals(3, nonEmpty.asSequence().count())

            // ... which on an EMPTY iterable is false, where it used to be an unconditional true
            val empty = YTDBEntityIterable.where(Issues.CLASS, tx.getStore(), GremlinBlock.PropNull("name"))
            assertEquals(0L, empty.size())
            assertEquals(false, empty.iterator().skip(-1))
            assertEquals(false, empty.iterator().skip(0))
        }
    }

    @Test
    fun `iterable sort and reverse`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->

            val reversedByName =
                tx.sort(Issues.CLASS, "name", true).reverse()
            val reversedTwice =
                reversedByName.reverse()

            // Then
            // Note: GremlinBlock.Reverse has BlockType.ORDER, so GremlinQuery.then() routes it through
            // Order.of() (the `block.type == BlockType.ORDER` branch) before ever reaching the
            // `block is GremlinBlock.Reverse` check. This means the entire `block is GremlinBlock.Reverse`
            // branch — including the `is SortBy -> reverseOrder()` case — is dead code. Even a SortBy
            // query gets fold().reverse().unfold() appended rather than having its sort direction flipped.
            checkGremlin(
                reversedByName as YTDBEntityIterable,
                "g.V().hasLabel(\"Issue\").order().by(__.values(\"name\").count(),Order.desc).by(__.values(\"name\").fold(),Order.asc).fold().reverse().unfold()"
            )
            checkGremlin(
                reversedTwice as YTDBEntityIterable,
                "g.V().hasLabel(\"Issue\").order().by(__.values(\"name\").count(),Order.desc).by(__.values(\"name\").fold(),Order.asc).fold().reverse().unfold().fold().reverse().unfold()"
            )
            assertNamesExactlyInOrder(reversedByName, "issue3", "issue2", "issue1")
            assertNamesExactlyInOrder(reversedTwice, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `find issues (as iterable) on boards (as iterable)`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue3, test.board3)
        }

        // When
        withStoreTx { tx ->
            // boards 1 and 2
            val boards = tx.find(Boards.CLASS, "name", test.board1.name())
                .union(tx.find(Boards.CLASS, "name", test.board2.name()))
            val allIssues = tx.getAll(Issues.CLASS)
            // findLinks filters the receiver: the result is `allIssues ∩ boards.in("OnBoard_link")`.
            // The receiver is allOf("Issue"), so O21 rewrites the intersection into a hasLabel("Issue")
            // filter appended to the in-link traversal rather than an Aggregate.
            val issuesOnBoards =
                allIssues.findLinks(boards, Issues.Links.ON_BOARD)

            // Then
            checkGremlin(
                issuesOnBoards as YTDBEntityIterable,
                """g.V().has("name",P.within(["board1", "board2"])).hasLabel("Board").in("OnBoard_link").hasLabel("Issue").dedup()"""
            )
            assertNamesExactly(issuesOnBoards, "issue1", "issue2")
        }
    }

    @Test
    fun `skip and take while intersect`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val skippedIssues = tx.getAll(Issues.CLASS).skip(1).take(2)
            val limitIssues1 = tx.getAll(Issues.CLASS).take(1)
            val limitIssues2 = tx.getAll(Issues.CLASS).take(2)

            // Then
            assertThat(skippedIssues.intersect(limitIssues1)).isEmpty()
            assertThat(skippedIssues.intersect(limitIssues2)).hasSize(1)
        }
    }

    @Test
    fun `skip and take while union`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val skippedIssues = tx.getAll(Issues.CLASS).skip(1).take(1)
            val limitIssues = tx.getAll(Issues.CLASS).take(1)
            val issues = skippedIssues.union(limitIssues)

            assertThat(issues).hasSize(2)
        }
    }

    @Test
    fun `sort iterable and get first`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val sortedIssues = tx.sort(Issues.CLASS, "name", true)
            val firstIssue = sortedIssues.first!!

            // Then
            checkGremlin(
                sortedIssues,
                "g.V().hasLabel(\"Issue\").order().by(__.values(\"name\").count(),Order.desc).by(__.values(\"name\").fold(),Order.asc)"
            )
            assertThat(firstIssue.getProperty("name")).isEqualTo("issue1")
        }
    }

    @Test
    fun `sort iterable and get last`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val issue = tx.sort(Issues.CLASS, "name", true).last!!

            // Then
            assertThat(issue.getProperty("name")).isEqualTo("issue3")
        }
    }

    @Test
    fun `an EntityIterable can be used in different transactions`() {
        givenTestCase()
        val allIssues = withStoreTx { it.getAll(Issues.CLASS) }
        val result = withStoreTx { allIssues.toList() }
        assertEquals(3, result.size)
    }

    @Test
    fun `roughCount() = roughSize(), count() = size()`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val allIssues = tx.getAll(Issues.CLASS)

            assertEquals(3, allIssues.roughCount)
            assertEquals(3, allIssues.roughSize)
            assertEquals(3, allIssues.count())
            assertEquals(3, allIssues.size())

            // one more issue
            tx.newEntity(Issues.CLASS)

            // uses previously calculated value
            assertEquals(3, allIssues.roughCount)
            assertEquals(3, allIssues.roughSize)
            // calculates the actual one
            assertEquals(4, allIssues.count())
            assertEquals(4, allIssues.size())
            assertEquals(4, allIssues.roughCount)
            assertEquals(4, allIssues.roughSize)
        }
    }

    @Test
    fun `instance of should work`() {
        // Create 10 Issue and 1 SubIssue and their classes
        youTrackDb.provider.withSession { session ->
            val subIssue = session.getOrCreateVertexClass("ChildIssue")
            val issueClass = session.getOrCreateVertexClass(Issues.CLASS)
            subIssue.addSuperClass(issueClass)
        }
        (1..10).forEach {
            youTrackDb.createIssue("issue$it")
        }
        withStoreTx { tx ->
            tx.newEntity("ChildIssue")
        }

        withStoreTx { txn ->
            val childIssues =
                YTDBEntityIterable.where("Issue", txn.getStore(), GremlinBlock.HasLabel("ChildIssue"))

            val notChildIssues =
                YTDBEntityIterable.where("Issue", txn.getStore(), GremlinBlock.Not(GremlinBlock.HasLabel("ChildIssue")))
            assertEquals(10, notChildIssues.toList().size)
            assertEquals(1, childIssues.toList().size)
        }
    }

    @Test
    fun `count should select the number of records`() {
        givenTestCase()

        withStoreTx { tx ->
            val issue1 = tx.find(Issues.CLASS, "name", "issue1")
            val issue2 = tx.find(Issues.CLASS, "name", "issue2")
            val issue4 = tx.find(Issues.CLASS, "name", "issue4")

            assertThat(issue1.size()).isEqualTo(1)
            assertThat(issue2.count()).isEqualTo(1)
            assertThat(issue4.count()).isEqualTo(0)

            assertThat(issue1.union(issue2).union(issue4).count()).isEqualTo(2)
        }
    }

    @Test
    fun `count should select the number of records with distinct`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
        }

        withStoreTx { tx ->

            val boards =
                tx.find(Boards.CLASS, "name", test.board1.name())
                    .union(
                        tx.find(Boards.CLASS, "name", test.board2.name())
                    )

            val issues = boards.selectDistinct(Boards.Links.HAS_ISSUE)

            assertThat(issues.toList().size).isEqualTo(2)
            assertThat(issues.count()).isEqualTo(2)
        }
    }

    @Test
    fun `count should count links`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
        }

        withStoreTx { tx ->

            val board1 =
                tx.find(Boards.CLASS, "name", test.board1.name())

            val issues = tx.findLinks(Issues.CLASS, board1, Issues.Links.ON_BOARD)

            assertThat(issues.toList().count()).isEqualTo(2)
            assertThat(issues.count()).isEqualTo(2)
        }
    }

    @Test
    fun `getAll produces hasLabel traversal`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = tx.getAll(Issues.CLASS)

            checkGremlin(issues, """g.V().hasLabel("Issue")""")
            assertThat(issues).hasSize(3)
        }
    }

    @Test
    fun `findWithProp produces has-prop traversal`() {
        val test = givenTestCase()
        withStoreTx {
            test.issue1.setProperty(Issues.Props.PRIORITY, "high")
            test.issue2.setProperty(Issues.Props.PRIORITY, "low")
        }

        withStoreTx { tx ->
            val issues = tx.findWithProp(Issues.CLASS, Issues.Props.PRIORITY)

            checkGremlin(issues, """g.V().has("priority").hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `findWithLinks produces where out-edge traversal`() {
        val test = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
        }

        withStoreTx { tx ->
            val issues = tx.findWithLinks(Issues.CLASS, Issues.Links.ON_BOARD)

            checkGremlin(issues, """g.V().where(__.out("OnBoard_link")).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `findContaining produces toLower contains traversal`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = tx.findContaining(Issues.CLASS, "name", "issue", true)

            checkGremlin(issues, """g.V().where(__.values("name").toLower().is(TextP.containing("issue"))).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `findStartingWith produces toLower startingWith traversal`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = tx.findStartingWith(Issues.CLASS, "name", "issu")

            checkGremlin(issues, """g.V().where(__.values("name").toLower().is(TextP.startingWith("issu"))).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `sort descending produces Order desc traversal`() {
        givenTestCase()

        withStoreTx { tx ->
            val issues = tx.sort(Issues.CLASS, "name", false)

            checkGremlin(
                issues,
                """g.V().hasLabel("Issue").order().by(__.values("name").count(),Order.desc).by(__.values("name").fold(),Order.desc)"""
            )
            assertNamesExactlyInOrder(issues, "issue3", "issue2", "issue1")
        }
    }

    @Test
    fun `sortLinked produces order by out-edge property traversal`() {
        val test = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board2)
            tx.addIssueToBoard(test.issue3, test.board3)
        }

        withStoreTx { tx ->
            val issues = (tx as YTDBStoreTransactionImpl).sortLinked(Issues.CLASS, Issues.Links.ON_BOARD, "name", true)

            checkGremlin(
                issues as YTDBEntityIterable,
                """g.V().hasLabel("Issue").order().by(__.out("OnBoard_link").values("name").count(),Order.desc).by(__.out("OnBoard_link").values("name").fold(),Order.asc)"""
            )
            assertNamesExactlyInOrder(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `union of sort and condition combines efficiently stripping sort`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            val sorted = tx.sort(Issues.CLASS, "name", true)
            // Build SortBy(PropEqual("name","issue1")) via intersect, then union with another condition.
            // This ensures both sides of the union are non-trivial, so Or(...) is produced rather than collapsing to All.
            val sortedIssue1 = sorted.intersect(tx.find(Issues.CLASS, "name", test.issue1.name()))
            val found2 = tx.find(Issues.CLASS, "name", test.issue2.name())

            // O3: this=SortBy(Labeled(PropEqual("name","issue1"))), other=Labeled(PropEqual("name","issue2"))
            // Union strips the left sort — sorting one operand does not define the union's sort order.
            // Result: Labeled(Where(Or(...)), "Issue") — no sort wrapper
            val result = sortedIssue1.union(found2) as YTDBEntityIterable
            checkGremlin(result, """g.V().has("name",P.within(["issue1", "issue2"])).hasLabel("Issue")""")
            assertNamesExactly(result, "issue1", "issue2")
        }
    }

    @Test
    fun `intersect of sort and condition combines efficiently preserving sort`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            test.issue1.setProperty(Issues.Props.PRIORITY, "high")
        }

        withStoreTx { tx ->
            val sorted = tx.sort(Issues.CLASS, "name", true)
            // Build SortBy(PropEqual("name","issue1")) via first intersect, then intersect with another condition.
            // This ensures both sides are non-trivial, producing And(...) rather than collapsing to the condition itself.
            val sortedIssue1 = sorted.intersect(tx.find(Issues.CLASS, "name", test.issue1.name()))
            val highPriority = tx.find(Issues.CLASS, Issues.Props.PRIORITY, "high")

            // O3: this=SortBy(PropEqual("name","issue1")), other=Labeled(PropEqual("priority","high"))
            // Intersect.combineBlocks(PropEqual("name","issue1"), PropEqual("priority","high")) = And(...)
            // Result: SortBy(Labeled(Where(And(...)), "Issue"), sort)
            val result = sortedIssue1.intersect(highPriority) as YTDBEntityIterable
            checkGremlin(result, """g.V().has("name","issue1").has("priority","high").hasLabel("Issue").order().by(__.values("name").count(),Order.desc).by(__.values("name").fold(),Order.asc)""")
            assertNamesExactly(result, "issue1")
        }
    }

    @Test
    fun `difference of sort and condition combines efficiently preserving sort`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            test.issue1.setProperty(Issues.Props.PRIORITY, "high")
            test.issue2.setProperty(Issues.Props.PRIORITY, "high")
        }

        withStoreTx { tx ->
            val sorted = tx.sort(Issues.CLASS, "name", true)
            // Build SortBy(PropEqual("priority","high")) via intersect — matches issue1 and issue2.
            // Then minus issue2 by name. Both sides are non-trivial, producing And(C, Not(C2)).
            val sortedHighPriority = sorted.intersect(tx.find(Issues.CLASS, Issues.Props.PRIORITY, "high"))
            val issue2ByName = tx.find(Issues.CLASS, "name", test.issue2.name())

            // O3: this=SortBy(PropEqual("priority","high")), other=Labeled(PropEqual("name","issue2"))
            // Difference.combineBlocks(PropEqual("priority","high"), PropEqual("name","issue2")) = And(C, Not(C2))
            // Result: SortBy(Labeled(Where(And(PropEqual("priority","high"), Not(PropEqual("name","issue2")))), "Issue"), sort)
            val result = sortedHighPriority.minus(issue2ByName) as YTDBEntityIterable
            checkGremlin(result, """g.V().and(__.has("priority","high"),__.not(__.has("name","issue2"))).hasLabel("Issue").order().by(__.values("name").count(),Order.desc).by(__.values("name").fold(),Order.asc)""")
            assertNamesExactly(result, "issue1")
        }
    }

    @Test
    fun `union of two sorts with same key combines into unsorted traversal stripping both sorts`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            val sorted = tx.sort(Issues.CLASS, "name", true)
            // Build two SortBy(Condition) queries via intersect (O3 applied twice)
            val sortedIssue1 = sorted.intersect(tx.find(Issues.CLASS, "name", test.issue1.name()))
            val sortedIssue2 = sorted.intersect(tx.find(Issues.CLASS, "name", test.issue2.name()))

            // Both sides are SortBy with same sort key.
            // O3: strips both sorts — sorting each operand individually does not define the union's sort order.
            // Result: Labeled(Where(Or(...)), "Issue") — no sort wrapper
            val result = sortedIssue1.union(sortedIssue2) as YTDBEntityIterable
            checkGremlin(result, """g.V().has("name",P.within(["issue1", "issue2"])).hasLabel("Issue")""")
            assertNamesExactly(result, "issue1", "issue2")
        }
    }

    @Test
    fun `intersect of two sorts with different keys strips right sort and preserves left sort`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            test.issue1.setProperty(Issues.Props.PRIORITY, "high")
        }

        withStoreTx { tx ->
            val sortedByName = tx.sort(Issues.CLASS, "name", true)
            val sortedByPriority = tx.sort(Issues.CLASS, Issues.Props.PRIORITY, true)
            // Build two SortBy queries with different sort keys via prior intersects
            val sortedIssue1ByName = sortedByName.intersect(tx.find(Issues.CLASS, "name", test.issue1.name()))
            val sortedHighByPriority = sortedByPriority.intersect(tx.find(Issues.CLASS, Issues.Props.PRIORITY, "high"))

            // O3: different sort keys, ignoreRightSort=true — strip other's sort, keep this's sort (by name)
            // Intersect.combineBlocks(PropEqual("name","issue1"), PropEqual("priority","high")) = And(...)
            // Result: SortBy(Labeled(Where(And(...))), sortByName)
            val result = sortedIssue1ByName.intersect(sortedHighByPriority) as YTDBEntityIterable
            checkGremlin(result, """g.V().has("name","issue1").has("priority","high").hasLabel("Issue").order().by(__.values("name").count(),Order.desc).by(__.values("name").fold(),Order.asc)""")
            assertNamesExactly(result, "issue1")
        }
    }

    @Test
    fun `intersect of condition and sort strips right sort`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            test.issue1.setProperty(Issues.Props.PRIORITY, "high")
        }

        withStoreTx { tx ->
            val sortedByName = tx.sort(Issues.CLASS, "name", true)
            val foundIssue1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val sortedHighByName = sortedByName.intersect(tx.find(Issues.CLASS, Issues.Props.PRIORITY, "high"))

            // this=Labeled(PropEqual("name","issue1")), other=SortBy(PropEqual("priority","high"), sortByName)
            // Second block fires: other is SortBy && ignoreRightSort=true — retry with other.inner
            // Intersect.combineBlocks(PropEqual("name","issue1"), PropEqual("priority","high")) = And(...)
            // Result: Labeled(Where(And(...))) — no sort, since this was not sorted
            val result = foundIssue1.intersect(sortedHighByName) as YTDBEntityIterable
            checkGremlin(result, """g.V().has("name","issue1").has("priority","high").hasLabel("Issue")""")
            assertNamesExactly(result, "issue1")
        }
    }

    @Test
    fun `difference of two sorts with different keys strips right sort and preserves left sort`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            test.issue2.setProperty(Issues.Props.PRIORITY, "high")
        }

        withStoreTx { tx ->
            val sortedByName = tx.sort(Issues.CLASS, "name", true)
            val sortedByPriority = tx.sort(Issues.CLASS, Issues.Props.PRIORITY, true)
            // issue1 is in sortedIssue1ByName; issue2 (not issue1) is in sortedHighByPriority
            val sortedIssue1ByName = sortedByName.intersect(tx.find(Issues.CLASS, "name", test.issue1.name()))
            val sortedHighByPriority = sortedByPriority.intersect(tx.find(Issues.CLASS, Issues.Props.PRIORITY, "high"))

            // O3: different sort keys, ignoreRightSort=true — strip other's sort, keep this's sort (by name)
            // Difference.combineBlocks(PropEqual("name","issue1"), PropEqual("priority","high")) = And(C, Not(C2))
            // Result: SortBy(Labeled(Where(And(PropEqual("name","issue1"), Not(PropEqual("priority","high"))))), sortByName)
            // issue1 has no priority="high" → included; result: issue1
            val result = sortedIssue1ByName.minus(sortedHighByPriority) as YTDBEntityIterable
            checkGremlin(result, """g.V().and(__.has("name","issue1"),__.not(__.has("priority","high"))).hasLabel("Issue").order().by(__.values("name").count(),Order.desc).by(__.values("name").fold(),Order.asc)""")
            assertNamesExactly(result, "issue1")
        }
    }

    @Test
    fun `selectDistinct produces out-link dedup traversal`() {
        val test = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue1, test.board2)
        }

        withStoreTx { tx ->
            val boards = tx.find(Boards.CLASS, "name", test.board1.name())
                .union(tx.find(Boards.CLASS, "name", test.board2.name()))
            val issues = boards.selectDistinct(Boards.Links.HAS_ISSUE) as YTDBEntityIterable

            checkGremlin(
                issues,
                """g.V().has("name",P.within(["board1", "board2"])).hasLabel("Board").out("HasIssue_link").dedup()"""
            )
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    /**
     * XD-1292 / audit #7 — the four binary operators must normalise the right operand with `unwrap()`
     * before casting it, so a link read (a `YTDBVertexEntityIterable`, which implements only
     * `EntityIterable`) can be combined with a query iterable. All four throw
     * `IllegalArgumentException("Only GremlinEntityIterable is supported, but was
     * YTDBVertexEntityIterable")` from `YTDBCasts` today.
     *
     * **The operand must really contain elements.** The naive choice — a *board's*
     * `getLinks(ON_BOARD)` — is empty, because `ON_BOARD` is Issue→Board and `getLinks` follows
     * outgoing edges only; every assertion over it would be satisfied by dropping the operand
     * entirely. Here the operand is `issue1.getLinks(ON_BOARD)` = `[board1]`, asserted non-empty first.
     *
     * Content only, never order: optimiser rule O3 strips the right operand's sort for
     * intersect/difference and both sorts for union, so the link order does not survive a binary op.
     */
    @Test
    fun `binary ops accept a link-read iterable as operand`() {
        val test = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue3, test.board2)
        }

        withStoreTx { tx ->
            val boardsOfIssue1 = test.issue1.getLinks(Issues.Links.ON_BOARD)
            // precondition: a non-empty, non-identity operand
            assertEquals(listOf("board1"), boardsOfIssue1.map { it.getProperty("name") })

            val allBoards = tx.getAll(Boards.CLASS)
            assertNamesExactly(allBoards, "board1", "board2", "board3")

            assertNamesExactly(allBoards.intersect(boardsOfIssue1), "board1")
            assertNamesExactly(allBoards.union(boardsOfIssue1), "board1", "board2", "board3")
            assertNamesExactly(allBoards.minus(boardsOfIssue1), "board2", "board3")
            assertNamesExactly(
                allBoards.concat(boardsOfIssue1),
                "board1", "board2", "board3", "board1"
            )
            assertNamesExactly(allBoards.intersectSavingOrder(boardsOfIssue1), "board1")
        }
    }

    /**
     * XD-1292 / BG15 — the accepted risk of #7, pinned so it is visible rather than latent.
     *
     * Normalising the operand newly routes a `YTDBVertexEntityIterable` into
     * `requirePolymorphicMatch`, and a link read's unwrapped form carries a **hardcoded**
     * `polymorphic = true` (`YTDBVertexEntityIterable.asQueryIterable()` calls the
     * `YTDBEntityIterable.query` factory, whose flag defaults to `true`; there is no source for a
     * per-link flag). So a **non-polymorphic** receiver combined with a link read now fails the flag
     * check instead of the cast.
     *
     * This is not a regression: the same input already threw `IllegalArgumentException` from
     * `YTDBCasts` before the change, so no working input starts failing — only the message changes.
     * Threading a flag through `asQueryIterable()` is out of scope. A `polymorphic = false` receiver
     * *is* reachable from production — `XdEntityType.all(polymorphic = false)` and
     * `YTDBStoreTransaction.getAll(type, polymorphic = false)` are public — but combining one with a
     * link read has never worked: a link read is a `YTDBVertexEntityIterable`, which is not a
     * [YTDBEntityIterable], so the pre-change cast rejected it too.
     */
    @Test
    fun `a non-polymorphic receiver cannot be combined with a link read`() {
        val test = givenTestCase()
        withStoreTx { tx -> tx.addIssueToBoard(test.issue1, test.board1) }

        withStoreTx { tx ->
            val boardsOfIssue1 = test.issue1.getLinks(Issues.Links.ON_BOARD)
            val nonPolymorphic = YTDBEntityIterable.where(
                Boards.CLASS, tx.getStore(), GremlinBlock.All, polymorphic = false
            )

            val e = assertFailsWith<IllegalArgumentException> {
                nonPolymorphic.intersect(boardsOfIssue1)
            }
            assertThat(e).hasMessageThat().contains("Both operands must have the same polymorphic flag")

            // ... while a polymorphic receiver - the only match for a link read's hardcoded true - works
            assertNamesExactly(tx.getAll(Boards.CLASS).intersect(boardsOfIssue1), "board1")
        }
    }

    private fun gremlinOf(iterable: YTDBEntityIterable): String {
        val script = GroovyTranslator.of("g")
            .translate(iterable.traversal().asAdmin().bytecode)
            .script
        // Strip the polymorphicQuery traversal source config to keep tests focused on
        // query algebra. Polymorphic flag behavior is tested separately.
        return script.replaceFirst(
            Regex("""^g\.withStrategies\(new OptionsStrategy\(polymorphicQuery: (true|false)\)\)\."""),
            "g."
        )
    }

    private fun checkGremlin(iterable: YTDBEntityIterable, expectedGremlin: String) {
        assertEquals(expectedGremlin, gremlinOf(iterable))
    }

    /**
     * Checks the Gremlin query of [iterable] against [pattern], where `{rid}` is a placeholder
     * matching any OrientDB RID (e.g. `#29:0`). All other characters are matched literally.
     */
    private fun checkGremlinPattern(iterable: YTDBEntityIterable, pattern: String) {
        val regex = pattern
            .split("{rid}")
            .joinToString("""#\d+:\d+""") { Regex.escape(it) }
            .toRegex()
        val actual = gremlinOf(iterable)
        assertThat(actual).matches(regex.pattern)
    }
}
