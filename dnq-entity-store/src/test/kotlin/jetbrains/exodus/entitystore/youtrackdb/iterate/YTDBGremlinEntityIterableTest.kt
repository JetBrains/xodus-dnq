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
import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

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
                Issues.CLASS, tx, GremlinBlock.PropNull("none")
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
                Issues.CLASS, tx,
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
                tx,
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
            checkGremlin(issues as YTDBEntityIterable, """g.V().or(__.has("name","issue1"),__.has("name","issue2")).hasLabel("Issue")""")
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `union two iterables having the same issue`() {
        // Given
        val test = givenTestCase()

        // When
        withStoreTx { tx ->
            val equal1 = tx.find(Issues.CLASS, "name", test.issue1.name())
            val equal2 = tx.find(Issues.CLASS, "name", test.issue1.name())

            val issues = equal1.union(equal2)

            // Then
            // Union of same condition is optimised to OR, so dedup happens automatically
            checkGremlin(issues as YTDBEntityIterable, """g.V().or(__.has("name","issue1"),__.has("name","issue1")).hasLabel("Issue")""")
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
            checkGremlin(issues as YTDBEntityIterable, """g.V().and(__.has("name","issue2"),__.has("priority","normal")).hasLabel("Issue")""")
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
            val q = (i3only as YTDBEntityIterable).query.start(tx.g())
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
            // RID is dynamic — assert the structural shape only
            assertThat(gremlinOf(issues)).contains("""in("OnBoard_link").hasLabel("Issue")""")
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
            // RIDs are dynamic — assert that both OnBoard_link traversals are present
            val concatGremlin = gremlinOf(concat as YTDBEntityIterable)
            assertThat(concatGremlin).contains("""in("OnBoard_link").hasLabel("Issue")""")
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
            // RIDs are dynamic — assert structural shape
            val distinctGremlin = gremlinOf(issuesDistinct as YTDBEntityIterable)
            assertThat(distinctGremlin).contains("""in("OnBoard_link").hasLabel("Issue")""")
            assertThat(distinctGremlin).contains("dedup()")
            assertThat(issuesDistinct).hasSize(3)
            assertNamesExactly(issuesDistinct, "issue1", "issue2", "issue3")
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
            // RIDs are dynamic — assert structural shape (Aggregate query: right first, then where-without)
            val minusGremlin = gremlinOf(issues as YTDBEntityIterable)
            assertThat(minusGremlin).contains("""in("OnBoard_link").hasLabel("Issue")""")
            assertThat(minusGremlin).contains("where(")
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
            // Note: Reverse has BlockType.ORDER so it goes through Order.of() rather than SortBy.reverseOrder(),
            // producing fold().reverse().unfold() steps appended to the sort traversal.
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
            val issuesOnBoards =
                allIssues.findLinks(boards, Issues.Links.ON_BOARD)

            // Then
            checkGremlin(
                issuesOnBoards as YTDBEntityIterable,
                """g.V().or(__.has("name","board1"),__.has("name","board2")).hasLabel("Board").in("OnBoard_link").dedup()"""
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
                YTDBEntityIterable.where("Issue", txn, GremlinBlock.HasLabel("ChildIssue"))

            val notChildIssues =
                YTDBEntityIterable.where("Issue", txn, GremlinBlock.Not(GremlinBlock.HasLabel("ChildIssue")))
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

    private fun gremlinOf(iterable: YTDBEntityIterable): String =
        GroovyTranslator.of("g")
            .translate(iterable.traversal().asAdmin().bytecode)
            .script

    private fun checkGremlin(iterable: YTDBEntityIterable, expectedGremlin: String) {
        assertEquals(expectedGremlin, gremlinOf(iterable))
    }
}
