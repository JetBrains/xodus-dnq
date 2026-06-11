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
package jetbrains.exodus.entitystore.youtrackdb

import com.google.common.truth.Truth.assertThat
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import jetbrains.exodus.Questionable
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.PersistentEntityId
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import org.apache.tinkerpop.gremlin.structure.Direction
import org.junit.Assert
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YTDBStoreTransactionTest : OTestMixin {

    @Rule
    @JvmField
    val orientDbRule = InMemoryYouTrackDB(true)

    override val youTrackDb = orientDbRule

    @Test
    fun `should find all`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val issues = tx.getAll(Issues.CLASS)

            // Then
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `should find property equal`() {
        // Given
        givenTestCase()

        // When
        withStoreTx { tx ->
            val result = tx.find(Issues.CLASS, "name", "issue2")

            // Then
            assertNamesExactly(result, "issue2")
        }
    }

    @Test
    fun `findLinks should return correct entityType`() {
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToProject(test.issue1, test.project1)
            tx.addIssueToBoard(test.issue1, test.board1)
        }

        withStoreTx {
            Assert.assertEquals(
                1,
                it.findLinks(Boards.CLASS, test.issue1, Boards.Links.HAS_ISSUE).size()
            )
            Assert.assertEquals(
                1,
                it.findLinks(Projects.CLASS, test.issue1, Boards.Links.HAS_ISSUE).size()
            )
            Assert.assertEquals(
                2,
                test.issue1.vertex.edges(
                    Direction.IN,
                    YTDBVertexEntity.edgeClassName(Boards.Links.HAS_ISSUE)
                )
                    .asSequence().toList().size
            )
        }
    }

    @Test
    fun `should find property contains`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue2.setProperty("case", "Find me if YOU can")
        }

        // When
        withStoreTx { tx ->
            val issues = tx.findContaining(Issues.CLASS, "case", "YOU", true)
            val empty = tx.findContaining(Issues.CLASS, "case", "not", true)

            // Then
            assertNamesExactly(issues, "issue2")
            assertThat(empty).isEmpty()
        }
    }

    @Test
    fun `should find property starts with`() {
        // Given
        val test = givenTestCase()
        withStoreTx { test.issue2.setProperty("case", "Find me if YOU can") }

        // When
        withStoreTx { tx ->
            val issues = tx.findStartingWith(Issues.CLASS, "case", "Find")
            val empty = tx.findStartingWith(Issues.CLASS, "case", "you")

            // Then
            assertNamesExactly(issues, "issue2")
            assertThat(empty).isEmpty()
        }
    }

    @Test
    fun `should find property in range`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue2.setProperty("value", 3)
        }

        // When
        withStoreTx { tx ->
            val exclusive = tx.find(Issues.CLASS, "value", 1, 5)
            val inclusiveMin = tx.find(Issues.CLASS, "value", 3, 5)
            val inclusiveMax = tx.find(Issues.CLASS, "value", 1, 3)
            val empty = tx.find(Issues.CLASS, "value", 6, 12)

            // Then
            assertNamesExactly(exclusive, "issue2")
            assertNamesExactly(inclusiveMin, "issue2")
            assertNamesExactly(inclusiveMax, "issue2")
            assertThat(empty).isEmpty()
        }
    }

    @Test
    fun `should find property exists`() {
        // Given
        val test = givenTestCase()

        withStoreTx { test.issue2.setProperty("prop", "test") }

        // When
        withStoreTx { tx ->
            val issues = tx.findWithProp(Issues.CLASS, "prop")
            val empty = tx.findWithProp(Issues.CLASS, "no_prop")

            // Then
            assertNamesExactly(issues, "issue2")
            assertThat(empty).isEmpty()
        }
    }

    @Test
    fun `should find entity with blob`() {
        // Given
        val test = givenTestCase()

        youTrackDb.withStoreTx {
            //correct blob (can be found)
            test.issue1.setBlob("myBlob", "Hello".toByteArray().inputStream())

            //blob with content of size 0 (can be found)
            test.issue2.setBlob("myBlob", ByteArray(0).inputStream())

            //blob with removed content (cannot be found)
            test.issue3.setBlob("myBlob", "World".toByteArray().inputStream())
        }

        // When
        youTrackDb.withStoreTx { tx ->
            val issues = tx.findWithBlob(Issues.CLASS, "myBlob")

            // Then
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `should sorted by property`() {
        // Given
        val test = givenTestCase()

        withStoreTx {
            test.issue1.setProperty("order", "1")
            test.issue2.setProperty("order", "2")
            test.issue3.setProperty("order", "3")
        }

        // When
        withStoreTx { tx ->
            val issuesAscending = tx.sort(Issues.CLASS, "order", true)
            val issuesDescending = tx.sort(Issues.CLASS, "order", false)

            // Then
            assertNamesExactlyInOrder(issuesAscending, "issue1", "issue2", "issue3")
            assertNamesExactlyInOrder(issuesDescending, "issue3", "issue2", "issue1")
        }
    }

    @Test
    fun `single entity iterable test`() {
        val test = givenTestCase()
        youTrackDb.store.executeInTransaction {
            val issue3 = it.getSingletonIterable(test.issue3).iterator().next()
            Assert.assertEquals(test.issue3, issue3)
        }
    }

    @Test
    fun `should sort iterable by property`() {
        // Given
        val test = givenTestCase()

        withStoreTx {
            test.issue1.setProperty("order", "1")
            test.issue3.setProperty("order", "3")
        }

        // When
        withStoreTx { tx ->
            val issues = tx.findWithProp(Issues.CLASS, "order")
            val issuesAscending = tx.sort(Issues.CLASS, "order", issues, true)
            val issuesDescending = tx.sort(Issues.CLASS, "order", issues, false)

            // Then
            assertNamesExactlyInOrder(issuesAscending, "issue1", "issue3")
            assertNamesExactlyInOrder(issuesDescending, "issue3", "issue1")
        }
    }

    @Test
    fun `should sort iterable by two properties`() {
        // Given
        val test = givenTestCase()

        withStoreTx {
            // Apple -> Appointment -> 3
            test.issue3.setProperty("project", "Apple")
            test.issue3.setProperty("type", "Appointment")

            // Apple -> Billing -> 1
            test.issue1.setProperty("project", "Apple")
            test.issue1.setProperty("type", "Billing")

            // Pear -> Appointment -> 2
            test.issue2.setProperty("project", "Pear")
            test.issue2.setProperty("type", "Appointment")
        }

        // When
        withStoreTx { tx ->
            // Sorted by project then by type in ascending order
            val sortedByProject = tx.findWithPropSortedByValue(Issues.CLASS, "type")
            val issues = tx.sort(Issues.CLASS, "project", sortedByProject, true)

            // Then
            // Apple -> Appointment -> 3
            //       -> Billing     -> 1
            // Pear  -> Appointment -> 2
            assertNamesExactlyInOrder(issues, "issue3", "issue1", "issue2")
        }
    }

    @Test
    fun `should find links by link entity id`() {
        // Given
        val testCase = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToProject(testCase.issue1, testCase.project1)
            tx.addIssueToProject(testCase.issue2, testCase.project1)
            tx.addIssueToProject(testCase.issue3, testCase.project2)
        }

        // When
        withStoreTx { tx ->
            val issues = tx.findLinks(Issues.CLASS, testCase.project1, Issues.Links.IN_PROJECT)

            // Then
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `should find links by link iterables`() {
        // Given
        val testCase = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToProject(testCase.issue1, testCase.project1)
            tx.addIssueToProject(testCase.issue2, testCase.project1)
            tx.addIssueToProject(testCase.issue3, testCase.project2)
        }

        // When
        withStoreTx { tx ->
            val projects = tx.getAll(Projects.CLASS)
            val issues = tx.findLinks(Issues.CLASS, projects, Issues.Links.IN_PROJECT)

            // Then
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `should find with links`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board2)
        }

        // When
        withStoreTx { tx ->
            val issuesOnBoard = tx.findWithLinks(Issues.CLASS, Issues.Links.ON_BOARD)
            val issuesInProject = tx.findWithLinks(Issues.CLASS, Issues.Links.IN_PROJECT)

            // Then
            assertNamesExactly(issuesOnBoard, "issue1", "issue2")
            assertThat(issuesInProject).isEmpty()
        }
    }

    @Test
    fun `should find links and iterable union`() {
        // Given
        val testCase = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToProject(testCase.issue1, testCase.project1)
            tx.addIssueToProject(testCase.issue2, testCase.project1)
            tx.addIssueToProject(testCase.issue3, testCase.project2)
        }

        // When
        withStoreTx { tx ->
            // Find all issues that in project1 or project2
            val issuesInProject1 =
                tx.findLinks(Issues.CLASS, testCase.project1, Issues.Links.IN_PROJECT)
            val issuesInProject2 =
                tx.findLinks(Issues.CLASS, testCase.project2, Issues.Links.IN_PROJECT)
            val issues = issuesInProject1.union(issuesInProject2)

            // Then
            assertNamesExactly(issues, "issue1", "issue2", "issue3")
        }
    }

    @Test
    fun `should find links and iterable intersect`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue2, test.board2)
            tx.addIssueToBoard(test.issue3, test.board3)
        }

        // When
        withStoreTx { tx ->
            // Find all issues that are on board1 and board2 at the same time
            val issuesOnBoard1 = tx.findLinks(Issues.CLASS, test.board1, Issues.Links.ON_BOARD)
            val issuesOnBoard2 = tx.findLinks(Issues.CLASS, test.board2, Issues.Links.ON_BOARD)
            val issues = issuesOnBoard1.intersect(issuesOnBoard2)

            // Then
            assertNamesExactly(issues, "issue2")
        }
    }

    @Test
    fun `should find different links and iterable union`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToProject(test.issue1, test.project1)
            tx.addIssueToBoard(test.issue2, test.board2)
            tx.addIssueToBoard(test.issue3, test.board3)
        }

        // When
        withStoreTx { tx ->
            // Find all issues that are either in project1 or board2
            val issuesOnBoard1 = tx.findLinks(Issues.CLASS, test.project1, Issues.Links.IN_PROJECT)
            val issuesOnBoard2 = tx.findLinks(Issues.CLASS, test.board2, Issues.Links.ON_BOARD)
            val issues = issuesOnBoard1.union(issuesOnBoard2)

            // Then
            assertNamesExactly(issues, "issue1", "issue2")
        }
    }

    @Test
    fun `should sort links by property`() {
        // Given
        val test = givenTestCase()

        // Issues assigned to projects ink reverse order
        withStoreTx { tx ->
            tx.addIssueToProject(test.issue1, test.project1)
            tx.addIssueToProject(test.issue2, test.project2)
            tx.addIssueToProject(test.issue3, test.project3)
        }

        // When
        withStoreTx { tx ->
            val projects = tx.getAll(Projects.CLASS)
            val issues = tx.getAll(Issues.CLASS)

            val projectsAsc = tx.sort(Projects.CLASS, "name", projects, true)
            val issuesAsc = tx.sortLinks(
                Issues.CLASS, // entity class
                projectsAsc, // links sorted asc by name
                false, // is multiple
                Issues.Links.IN_PROJECT, // link name
                issues // entities
            )

            val projectsDesc = tx.sort(Projects.CLASS, "name", projects, false)
            val issuesDesc = tx.sortLinks(
                Issues.CLASS, // entity class
                projectsDesc, // links sorted desc by name
                false, // is multiple
                Issues.Links.IN_PROJECT, // link name
                issues // entities
            )

            // Then
            // As sorted by project name
            assertNamesExactlyInOrder(issuesAsc, "issue1", "issue2", "issue3")
            assertNamesExactlyInOrder(issuesDesc, "issue3", "issue2", "issue1")
        }
    }

    @Test
    fun `should sort links by property distinct`() {
        // Given
        val test = givenTestCase()

        // Issues assigned to projects in reverse order
        withStoreTx { tx ->
            tx.addIssueToProject(test.issue1, test.project3)
            tx.addIssueToProject(test.issue1, test.project2)

            tx.addIssueToProject(test.issue2, test.project2)
            tx.addIssueToProject(test.issue2, test.project1)

            tx.addIssueToProject(test.issue3, test.project1)
            tx.addIssueToProject(test.issue3, test.project2)
        }

        // When
        withStoreTx { tx ->
            val links = tx.getAll(Projects.CLASS)
            val issues = tx.getAll(Issues.CLASS)

            // Find all issues that are either in project1 or board2
            val issuesAsc = tx.sortLinks(
                Issues.CLASS, // entity class
                tx.sort(Projects.CLASS, "name", links, false), // links sorted desc by name
                true, // is multiple
                Issues.Links.IN_PROJECT, // link name
                issues // entities
            ).distinct().toList()

            // Then
            assertNamesExactly(issuesAsc.take(1), "issue1")
            assertNamesExactly(issuesAsc.drop(1), "issue2", "issue3")
        }
    }

    @Test
    fun `should select many links`() {
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
            val issues = tx.getAll(Issues.CLASS) as YTDBEntityIterable
            val boards = issues.selectMany(Issues.Links.ON_BOARD)

            // Then
            assertNamesExactlyInOrder(boards.sorted(), "board1", "board1", "board1", "board2")
        }
    }

    @Test
    fun `should select many links distinct`() {
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
            val issues = tx.getAll(Issues.CLASS)
            val boards = issues.selectDistinct(Issues.Links.ON_BOARD)

            // Then
            assertNamesExactlyInOrder(boards.sorted(), "board1", "board2")
        }
    }

    @Test
    fun `select by id range dummy test`() {
        // Given
        val test = givenTestCase()
        withStoreTx {
            test.issue1.setProperty(YTDBVertexEntity.LOCAL_ENTITY_ID_PROPERTY_NAME, 0L)
            test.issue2.setProperty(YTDBVertexEntity.LOCAL_ENTITY_ID_PROPERTY_NAME, 3L)
            test.issue3.setProperty(YTDBVertexEntity.LOCAL_ENTITY_ID_PROPERTY_NAME, 99L)
        }

        // When
        withStoreTx { tx ->
            val issues = tx.findIds(Issues.CLASS, 2, 100)
            // Then
            assertNamesExactly(
                issues,
                test.issue2.getProperty("name").toString(),
                test.issue3.getProperty("name").toString()
            )
        }
    }

    @Test
    fun `tx lets search for an entity using unresolved RIDEntityId`() {
        val aId = youTrackDb.createIssue("A").id
        val bId = youTrackDb.createIssue("B").id

        // use default ids
        youTrackDb.store.executeInTransaction { tx ->
            val a = tx.getEntity(aId)
            val b = tx.getEntity(bId)

            Assert.assertEquals(aId, a.id)
            Assert.assertEquals(bId, b.id)
        }

        // use unresolved ids
        val unresolvedA = PersistentEntityId(aId.typeId, aId.localId)
        val unresolvedB = PersistentEntityId(bId.typeId, bId.localId)
        youTrackDb.store.executeInTransaction { tx ->
            val a = tx.getEntity(unresolvedA)
            val b = tx.getEntity(unresolvedB)

            Assert.assertEquals(aId, a.id)
            Assert.assertEquals(bId, b.id)
        }
    }

    @Test
    fun `getEntity() throws an exception if the entity not found`() {
        val aId = youTrackDb.createIssue("A").id

        // delete the issue
        youTrackDb.withStoreTx { tx ->
            tx.deleteVertex(aId.asOId())
        }

        // entity not found
        youTrackDb.store.executeInTransaction { tx ->
            assertFailsWith<EntityRemovedInDatabaseException> {
                tx.getEntity(aId)
            }
            assertFailsWith<EntityRemovedInDatabaseException> {
                tx.getEntity(PersistentEntityId(300, 300))
            }
        }
    }

    @Test
    fun `tx works with both resolved and unresolved RIDEntityId representations`() {
        val aId = youTrackDb.createIssue("A").id
        val bId = youTrackDb.createIssue("B").id
        val aIdRepresentation = aId.toString()
        val bIdRepresentation = bId.toString()
        val unresolvedA = PersistentEntityId(aId.typeId, aId.localId)
        val unresolvedB = PersistentEntityId(bId.typeId, bId.localId)
        val unresolvedARepresentation = unresolvedA.toString()
        val unresolvedBRepresentation = unresolvedB.toString()

        youTrackDb.store.executeInTransaction { tx ->
            // Cross-type equals: the parsed logical id equals the resolved RIDEntityId for the same entity.
            assertEquals(aId, tx.toEntityId(aIdRepresentation))
            assertEquals(bId, tx.toEntityId(bIdRepresentation))

            assertEquals(aId, tx.toEntityId(unresolvedARepresentation))
            assertEquals(bId, tx.toEntityId(unresolvedBRepresentation))

            // Parse-only contract: the returned type is always a logical PersistentEntityId,
            // never a resolved RIDEntityId — even for an existing entity.
            val parsed = tx.toEntityId(aIdRepresentation)
            assertTrue(parsed is PersistentEntityId, "expected PersistentEntityId, got ${parsed.javaClass.name}")
        }
    }

    @Test
    fun `toEntityId parses without an active transaction`() {
        // Parse-only needs no active transaction (classic Xodus parity). Finish the transaction
        // first, then parse on it: the previous resolve-against-DB implementation called
        // requireActiveTransaction() and would throw "The transaction is finished" here.
        val tx = youTrackDb.store.beginTransaction()
        tx.commit()
        assertTrue(tx.isFinished)

        val id = tx.toEntityId("12-58")
        assertTrue(id is PersistentEntityId, "expected PersistentEntityId, got ${id.javaClass.name}")
        assertEquals(12, id.typeId)
        assertEquals(58L, id.localId)
    }

    @Test
    fun `toEntityId throws IllegalArgumentException on malformed input`() {
        youTrackDb.store.executeInTransaction { tx ->
            // No separator, extra parts, and non-numeric parts are all malformed.
            assertFailsWith<IllegalArgumentException> { tx.toEntityId("not-an-id-at-all") }
            assertFailsWith<IllegalArgumentException> { tx.toEntityId("12") }
            // NumberFormatException is a subclass of IllegalArgumentException.
            assertFailsWith<IllegalArgumentException> { tx.toEntityId("x-58") }
        }
    }


    @Test
    fun `entity id should be valid and accessible just after creation`() {
        youTrackDb.store.executeInTransaction { tx ->
            val entity = tx.newEntity(Issues.CLASS)
            val orid = (entity.id as YTDBEntityId).asOId()
            Assert.assertTrue(orid.collectionId > 0)
        }
    }

    @Test
    fun `newEntity sets localEntityId`() {
        youTrackDb.store.executeInTransaction { tx ->
            val issue = tx.newEntity(Issues.CLASS)
            assertEquals(issue.id.localId, 0)
        }
    }

    /*
    * This behaviour may change in the future if we support schema changes in transactions
    * */
    @Test
    fun `newEntity() throws exception if the type is not created`() {
        withStoreTx { tx ->
            assertFailsWith<IllegalStateException> {
                tx.newEntity("opca")
            }
        }
    }

    @Test
    fun `read-only transaction forbids changing data in it`() {
        youTrackDb.createIssue("trista")
        val tx = youTrackDb.store.beginReadonlyTransaction()
        assertFailsWith<IllegalStateException> { tx.newEntity(Issues.CLASS) }
    }

    @Test
    @Ignore
    @Questionable("Un-ignore this when we add timeout logic")
    fun `should throw timeout exception when timeout is small`() {
        // Given
        val test = givenTestCase()
        test.createManyIssues(1000)

        // When
        youTrackDb.store.executeInTransaction { transaction ->
            transaction.queryCancellingPolicy = YTDBQueryCancellingPolicy.timeout(0)

            val exception = Assert.assertThrows(YTDBQueryTimeoutException::class.java) {
                transaction.getAll(Issues.CLASS).toList()
            }
            assertThat(exception.message).contains("Query execution timed out")
        }
    }

    @Test
    fun `should not return nulls on empty links`() {
        // Given
        val test = givenTestCase()

        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board2)
        }

        withStoreTx { tx ->
            val boards =
                YTDBEntityIterable
                    .where(Issues.CLASS, tx.getStore(), GremlinBlock.All)
                    .selectManyDistinct(Issues.Links.ON_BOARD)
                    .toList()
            //selectManyDistinct
            Assert.assertEquals(2, boards.size)
            Assert.assertEquals(
                "Should not contain nulls",
                0,
                boards.filter { board -> board == null }.size
            )
        }
    }

    @Test
    fun `contains should work `() {
        // Given
        val test = givenTestCase()
        withStoreTx { tx ->
            tx.addIssueToBoard(test.issue1, test.board1)
        }

        withStoreTx { tx ->
            val issues = YTDBEntityIterable.where(Issues.CLASS, tx.getStore(), GremlinBlock.All)
            Assert.assertTrue(issues.contains(test.issue1))
            val issuesOnBoard =
                YTDBEntityIterable
                    .where(
                        Issues.CLASS, tx.getStore(),
                        GremlinBlock.HasLinkTo(Issues.Links.ON_BOARD, test.board1.id.asOId())
                    )
            Assert.assertEquals(1, issuesOnBoard.toList().size)
            Assert.assertFalse(issuesOnBoard.contains(test.issue2))
            Assert.assertTrue(issuesOnBoard.contains(test.issue1))
        }
    }

    @Test
    fun `getRecord()`() {
        val id = withStoreTx { tx ->
            val e1 = tx.createIssue("opca trista")
            e1.setProperty("mamba", "caramba")
            e1.id
        }

        withStoreTx { tx ->
            val vertex: YTDBVertex = tx.getVertex(id)
            assertEquals("caramba", vertex.property<String>("mamba").value())
            val e1 = tx.getEntity(id)
            e1.delete()
        }

        withStoreTx { tx ->
            try {
                tx.getVertex(id)
                Assert.fail()
            } catch (e: EntityRemovedInDatabaseException) {
                // expected
            }
        }
    }

    @Test
    fun `getEntityTypes returns only real vertex classes`() {
        withSession { session ->
            // Create abstract base class
            val baseEntityClass = session.getOrCreateVertexClass("BaseEntity").apply {
                this.setAbstract(true)
            }

            // Create concrete vertex classes
            val userClass = session.getOrCreateVertexClass("User")
            val projectClass = session.getOrCreateVertexClass("Project")
            val issueClass = session.getOrCreateVertexClass("Issue")

            // Set inheritance relationships
            userClass.addSuperClass(baseEntityClass)
            projectClass.addSuperClass(baseEntityClass)
            issueClass.addSuperClass(baseEntityClass)

            // Create edge classes (associations)
            session.addAssociation("User", "Project", "OWNS_PROJECT", "OWNED_BY")
            session.addAssociation("Project", "Issue", "HAS_ISSUE", "IN_PROJECT")
        }

        val expectedEdgeClasses = listOf("OWNS_PROJECT_link", "OWNED_BY_link", "HAS_ISSUE_link", "IN_PROJECT_link")
        val expectedVertexClasses = listOf("User", "Project", "Issue", "BaseEntity")
        withSession { session ->
            val allClassesByName = session.schema.classes.associateBy { it.name }

            // Verify edge classes are properly marked
            expectedEdgeClasses.forEach {
                assertThat(allClassesByName[it]!!.isEdgeType).isTrue()
            }

            // Verify all vertex classes (including abstract) are properly marked
            expectedVertexClasses.forEach {
                assertThat(allClassesByName[it]!!.isVertexType).isTrue()
            }
        }

        withStoreTx { tx ->

            val entityTypes = tx.entityTypes

            // Verify all vertex classes (including abstract base class) are returned
            assertThat(entityTypes).containsAtLeastElementsIn(expectedVertexClasses)

            // Verify no vertex super classes is returned
            assertThat(entityTypes).doesNotContain(Vertex.CLASS_NAME)

            // Verify edge classes are not returned
            assertThat(entityTypes).containsNoneIn(expectedEdgeClasses)

            // Verify no types start with "O" (are real vertex classes)
            assertThat(entityTypes.none { it.startsWith("O") }).isTrue()
        }
    }
}
