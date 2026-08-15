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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import io.mockk.every
import io.mockk.mockk
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.PersistentEntityId
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.linkTargetEntityIdPropertyName
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBVertexEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.testutil.*
import jetbrains.exodus.entitystore.youtrackdb.testutil.Issues.Links.IN_PROJECT
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random
import java.util.*
import kotlin.test.*

class YTDBEntityTest : OTestMixin {
    @Rule
    @JvmField
    val youTrackDbRule = InMemoryYouTrackDB()

    override val youTrackDb = youTrackDbRule

    @Test
    fun `create entities`() {
        val (e1, e2) = youTrackDb.withStoreTx { tx ->
            val e1 = tx.newEntity(Issues.CLASS)
            val e2 = tx.newEntity(Issues.CLASS)
            assertEquals(tx.getTypeId(Issues.CLASS), e1.id.typeId)
            assertEquals(tx.getTypeId(Issues.CLASS), e2.id.typeId)
            assertEquals(0, e1.id.localId)
            assertEquals(1, e2.id.localId)
            assertEquals(2, tx.getAll(Issues.CLASS).size())

            assertEquals(e1, tx.getEntity(e1.id))
            assertEquals(e2, tx.getEntity(e2.id))

            Pair(e1, e2)
        }

        youTrackDb.withStoreTx { tx ->
            assertTrue(e1.delete())
        }

        youTrackDb.withStoreTx { tx ->
            tx.getEntity(e2.id)
            assertFailsWith<EntityRemovedInDatabaseException> { tx.getEntity(e1.id) }
            assertEquals(1, tx.getAll(Issues.CLASS).size())
        }
    }

    @Test
    fun `link names should return actual link names`(){
        val issue = withStoreTx { tx ->
            val issue = tx.newEntity(Issues.CLASS) as YTDBEntity
            val project = tx.newEntity(Projects.CLASS) as YTDBEntity
            tx.addIssueToProject(issue, project)
            issue
        }
        withStoreTx {
            Assert.assertEquals(arrayListOf(IN_PROJECT), issue.linkNames)
        }
    }

    @Test
    fun `an entity sees changes made to it in another part of the application`() {
        // your entity
        val e1 = youTrackDb.withStoreTx { tx ->
            val e1 = tx.newEntity(Issues.CLASS)
            e1.setProperty("name", "Pumba")
            e1
        }

        // this changes happen in another part of the application
        val e1Again = youTrackDb.withStoreTx { tx ->
            val e1Again = tx.getEntity(e1.id)
            e1Again.setProperty("name", "Bampu")
            e1Again
        }

        // make sure we do not deal with physically the same instance
        assertNotSame(e1, e1Again)

        // your entity sees changes made in another part of the application
        youTrackDb.withStoreTx { tx ->
            assertEquals("Bampu", e1.getProperty("name"))
        }
    }


    @Test
    fun `rename entity type`() {
        youTrackDb.withStoreTx { tx ->
            for (i in 0..9) {
                tx.newEntity("Issue")
            }
            assertEquals(10, tx.getAll("Issue").size())
        }
        youTrackDb.withStoreTx {
            youTrackDb.store.renameEntityType("Issue", "Comment")
        }
        youTrackDb.withStoreTx { tx ->
            assertEquals(10, tx.getAll("Comment").size())
        }
    }

    @Test
    fun `multiple links should work`() {
        val issueA = youTrackDb.createIssue("A")
        val issueB = youTrackDb.createIssue("B")
        val issueC = youTrackDb.createIssue("C")
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        youTrackDb.withStoreTx {
            issueA.addLink(linkName, issueB)
            issueA.addLink(linkName, issueC)
        }

        youTrackDb.withStoreTx {
            val links = issueA.getLinks(linkName)
            assertTrue(links.contains(issueB))
            assertTrue(links.contains(issueC))
        }
    }

    /**
     * Xodus iterates a link in ascending target `(typeId, localId)` order — see
     * [YTDBVertexEntity.getLinks]. This pins that sort directly: iterating the returned
     * [jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBVertexEntityIterable] walks the
     * materialized vertex list, so it bypasses `unwrap()` and the Gremlin query.
     *
     * The targets are created in id order but attached in the scrambled order `E, C, A, D, B`, and
     * they are new records in the linking transaction, so their temporary RID positions *decrease*.
     * Neither insertion order nor its reverse coincides with the expected result.
     */
    @Test
    fun `getLinks returns targets in ascending entity id order`() {
        val source = youTrackDb.createIssue("source")
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        val names = listOf("A", "B", "C", "D", "E")
        youTrackDb.withStoreTx { tx ->
            val created = names.associateWith { tx.createIssue(it) }
            listOf("E", "C", "A", "D", "B").forEach { source.addLink(linkName, created.getValue(it)) }

            assertNamesExactlyInOrder(source.getLinks(linkName), *names.toTypedArray())
        }

        youTrackDb.withStoreTx {
            assertNamesExactlyInOrder(source.getLinks(linkName), *names.toTypedArray())
        }
    }

    /**
     * XD-1292 / audit #3 — the anonymous [jetbrains.exodus.entitystore.EntityIterator] returned by
     * `YTDBVertexEntityIterable.iterator()`.
     *
     * Contract ([jetbrains.exodus.entitystore.EntityIterator.skip]): *"Skips specified number of
     * entities and returns the value of `hasNext()`"* — i.e. `skip(n)` must **consume** up to `n`
     * elements and then answer "is anything left", not "did I manage to count n".
     *
     * **Every assertion builds a FRESH `iterator()`. This is load-bearing, not hygiene:** each row is
     * a statement about the *initial* iterator state, so a shared iterator would make later rows
     * depend on how much earlier rows consumed. `iterator()` is cheap — `getLinks` materialises a
     * sorted `List` and the iterator just walks it.
     *
     * Expected elements are derived from `links.toList()` rather than from the order the links were
     * added: since `getLinks` sorts targets ascending by entity id, insertion order is not the
     * iteration order.
     */
    @Test
    fun `getLinks iterator skip advances by n and returns hasNext`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        val (source, unlinked) = youTrackDb.withStoreTx { tx ->
            val source = tx.createIssue("source")
            val unlinked = tx.createIssue("unlinked")
            val names = listOf("A", "B", "C", "D")
            val created = names.associateWith { tx.createIssue(it) }
            // scrambled attach order, so insertion order != id order
            listOf("D", "B", "A", "C").forEach { source.addLink(linkName, created.getValue(it)) }
            source to unlinked
        }

        youTrackDb.withStoreTx {
            val links = source.getLinks(linkName)
            val expected = links.toList().map { it.id }
            assertEquals(4, expected.size)

            // skip(0) must consume nothing
            assertEquals(expected[0], links.iterator().let { it.skip(0); it.next().id })

            // skip(2) must consume exactly two
            assertEquals(expected[2], links.iterator().let { it.skip(2); it.next().id })

            // skip(size) lands past the end -> hasNext() == false
            assertFalse(links.iterator().skip(4))

            // skip(size - 1) leaves exactly one element
            assertTrue(links.iterator().skip(3))
            assertEquals(expected[3], links.iterator().let { it.skip(3); it.next().id })

            // negative n: a no-op that consumes nothing and reports hasNext()
            assertTrue(links.iterator().skip(-1))
            assertEquals(expected[0], links.iterator().let { it.skip(-1); it.next().id })

            // empty link set: skip must return false, not throw
            val noLinks = unlinked.getLinks(linkName)
            assertFalse(noLinks.iterator().skip(0))
            assertFalse(noLinks.iterator().skip(1))
            assertFalse(noLinks.iterator().skip(-1))
        }
    }

    /**
     * XD-1292 / audit #4 — `YTDBVertexEntityIterable.getLast()`.
     *
     * Sizes 0/1/2/3 in one test. Today size 1 throws `NoSuchElementException`, size 2 passes by
     * accident and size 3 returns the element at index 1; size 0 must stay `null` (a
     * preserved-behaviour pin, not a regression assertion).
     *
     * Expectations are derived from `getLinks(link).toList()`, not from the attach order: `getLinks`
     * sorts its targets ascending by entity id.
     */
    @Test
    fun `getLinks getLast returns the last link`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        val sources = youTrackDb.withStoreTx { tx ->
            (0..3).map { size ->
                val source = tx.createIssue("source$size")
                val targets = (0 until size).map { tx.createIssue("t$size-$it") }
                // reversed attach order, so insertion order != id order for size >= 2
                targets.reversed().forEach { source.addLink(linkName, it) }
                source
            }
        }

        youTrackDb.withStoreTx {
            sources.forEachIndexed { size, source ->
                val links = source.getLinks(linkName)
                val expected = links.toList().lastOrNull()?.id
                assertEquals(size, links.toList().size)
                assertEquals(expected, links.last?.id, "getLast() on a link set of size $size")
            }
        }
    }

    /**
     * XD-1292 / audit #4, the row that pins the `getLast()` **rewrite** rather than the `skip()` fix.
     *
     * The three rows above go through `YTDBVertexEntity.getLinks`, which always hands
     * [YTDBVertexEntityIterable] an already-materialised `List`. With a `List` source `count()` is
     * exact, so once `skip()` consumes correctly the old `skip(count() - 1) + next()` form happens to
     * be right too — those rows cannot tell the two implementations apart.
     *
     * The rewrite's actual claim is **independence from `count()`**, which returns `-1` for any source
     * that is neither a `Collection` nor a `Sizeable` (`YTDBVertexEntityIterable.count()`). The
     * constructor is public, so that source is constructible: with `count() == -1` the old form
     * evaluates `skip(-2)`, which consumes nothing and returns `hasNext()`, and then `next()` yields
     * element **0** instead of the last. This test is therefore the one that fails if the `getLast`
     * edit alone is reverted.
     */
    @Test
    fun `getLast does not depend on count for a non-Collection source`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }
        val (source, targets) = youTrackDb.withStoreTx { tx ->
            val source = tx.createIssue("lazySource")
            source to (0..2).map { tx.createIssue("lazyTarget$it") }
        }

        withStoreTx { tx ->
            val vertices = targets.map { it.vertex }
            // Neither a Collection nor a Sizeable, so count() cannot answer.
            val lazySource: Iterable<YTDBVertex> = Iterable { vertices.iterator() }
            val iterable = YTDBVertexEntityIterable(tx, lazySource, tx.getStore(), linkName, source.id)

            // Precondition: this is exactly the shape the count()-based form cannot handle.
            assertEquals(-1L, iterable.count())
            assertEquals(3, iterable.toList().size)

            assertEquals(targets.first().id, iterable.first?.id)
            assertEquals(targets.last().id, iterable.last?.id)
        }
    }

    /**
     * XD-1292 / audit #11 — `YTDBVertexEntityIterable.contains`/`indexOf` must compare `EntityId`s,
     * not objects.
     *
     * The old form went through commons-collections `IteratorUtils` + `EqualPredicate`, which is
     * **argument-first**: it runs the *argument's* `equals`. So a foreign `Entity` implementation
     * carrying a member's id missed — either because `YTDBVertexEntity.equals` has a
     * `javaClass != other.javaClass` check, or (the realistic DNQ case) because
     * `TransientEntityImpl.equals` rejects any non-`TransientEntity` before comparing ids.
     *
     * Three guards, all mandatory:
     *  1. the positive target sits at a **non-zero** index and the index is asserted exactly —
     *     otherwise `indexOf(e) = if (contains(e)) 0 else -1` would pass;
     *  2. a **cross-type collision** control: `localId` sequences are per class, so a Board with the
     *     same `localId` as the target Issue really exists — an implementation comparing only
     *     `localId` must fail here;
     *  3. an **absent** same-type entity control.
     */
    @Test
    fun `getLinks contains and indexOf compare entity ids`() {
        val test = givenTestCase()
        withStoreTx { tx ->
            // board1 -> HasIssue -> all three issues, so the link set has size 3
            tx.addIssueToBoard(test.issue1, test.board1)
            tx.addIssueToBoard(test.issue2, test.board1)
            tx.addIssueToBoard(test.issue3, test.board1)
        }

        // guard 3's subject: a same-type entity that is simply not in the link set
        val absentIssue = youTrackDb.createIssue("absent")

        withStoreTx {
            val links = test.board1.getLinks(Boards.Links.HAS_ISSUE)
            val members = links.toList()
            assertEquals(3, members.size)

            // GUARD 1 - positive row on a NON-FIRST element, exact index asserted.
            val targetIndex = members.size - 1
            val target = members[targetIndex]
            assertTrue(targetIndex > 0, "the positive row must not target index 0")
            val foreignWrapper = mockk<Entity> { every { id } returns target.id }
            assertEquals(targetIndex, links.indexOf(foreignWrapper))
            assertTrue(links.contains(foreignWrapper))

            // GUARD 2 - cross-type collision: same localId, different typeId, must NOT be found.
            val collider = listOf(test.board1, test.board2, test.board3)
                .first { it.id.localId == target.id.localId }
            assertNotEquals(collider.id.typeId, target.id.typeId, "collider must be of another type")
            assertEquals(collider.id.localId, target.id.localId, "collider must share the localId")
            assertFalse(links.contains(collider))
            assertEquals(-1, links.indexOf(collider))

            // GUARD 3 - an absent entity of the same type.
            assertFalse(links.contains(absentIssue))
            assertEquals(-1, links.indexOf(absentIssue))
        }
    }

    /**
     * XD-1292 / audit #21 — the anonymous iterator's `dispose()` claimed `true` ("the EntityIterator
     * was actually disposed") while holding nothing and while `shouldBeDisposed()` said `false`.
     * Both halves are asserted so the pair cannot drift apart again.
     */
    @Test
    fun `getLinks iterator is honestly non-disposable`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }
        val source = youTrackDb.withStoreTx { tx ->
            val source = tx.createIssue("source")
            source.addLink(linkName, tx.createIssue("target"))
            source
        }

        youTrackDb.withStoreTx {
            val links = source.getLinks(linkName)
            assertFalse(links.iterator().shouldBeDisposed())
            assertFalse(links.iterator().dispose())
        }
    }

    /**
     * Orient may once in a while go crazy and add random bytes to
     * a blob. It does not like blobs of size 1023, 2047, 4095, 8191 and so on.
     *
     * One should NOT use fromInputStream() in the blob implementation is fixed.
     *
     * This test checks that our implementation behaves.
     */
    @Test
    fun `set a hard blob`() {
        val hardBlob = Random.nextBytes(1023)
        val id = youTrackDb.withStoreTx { tx ->
            val issue = tx.createIssueImpl("iss")
            issue.setBlob("blob1", ByteArrayInputStream(hardBlob))
            issue.id
        }

        youTrackDb.withStoreTx { tx ->
            val issue = tx.getEntity(id)
            val gotBlob = issue.getBlob("blob1")!!.readAllBytes()
            assertContentEquals(hardBlob, gotBlob)
        }
    }

    @Test
    fun `set, change and delete blobs`() {
        val issue = youTrackDb.createIssue("iss")

        // set
        val expectedBlob1 = byteArrayOf(0x01, 0x02)
        val expectedBlob2 = byteArrayOf(0x04, 0x05, 0x06)
        youTrackDb.withStoreTx {
            issue.setBlob("blob1", ByteArrayInputStream(expectedBlob1))
            issue.setBlob("blob2", ByteArrayInputStream(expectedBlob2))
        }
        youTrackDb.withStoreTx {
            assertContentEquals(expectedBlob1, issue.getBlob("blob1")!!.readAllBytes())
            assertContentEquals(expectedBlob2, issue.getBlob("blob2")!!.readAllBytes())
            assertEquals(expectedBlob1.size.toLong(), issue.getBlobSize("blob1"))
            assertEquals(expectedBlob2.size.toLong(), issue.getBlobSize("blob2"))
        }

        // change
        val expectedResetBlob1 = byteArrayOf(0x01, 0x03, 0x04, 0x05)
        youTrackDb.withStoreTx {
            issue.setBlob("blob1", ByteArrayInputStream(expectedResetBlob1))
        }
        youTrackDb.withStoreTx {
            val resetBlob1 = issue.getBlob("blob1")!!.readAllBytes()
            assertContentEquals(expectedResetBlob1, resetBlob1)
            assertEquals(expectedResetBlob1.size.toLong(), issue.getBlobSize("blob1"))
        }

        // delete
        youTrackDb.withStoreTx {
            issue.deleteBlob("blob1")
        }
        youTrackDb.withStoreTx {
            assertNull(issue.getBlob("blob1"))
            assertEquals(-1, issue.getBlobSize("blob1"))
            // another blob is still here
            assertContentEquals(expectedBlob2, issue.getBlob("blob2")!!.readAllBytes())
            assertEquals(expectedBlob2.size.toLong(), issue.getBlobSize("blob2"))
        }
    }

    @Test
    fun `set, change and delete string blobs`() {
        val issue = youTrackDb.createIssue("iss")

        // set
        val expectedBlob1 = "Abc"
        val expectedBlob2 = "dxYz"
        youTrackDb.withStoreTx {
            issue.setBlobString("blob1", expectedBlob1)
            issue.setBlobString("blob2", expectedBlob2)
        }
        youTrackDb.withStoreTx {
            assertEquals(expectedBlob1, issue.getBlobString("blob1"))
            assertEquals(expectedBlob2, issue.getBlobString("blob2"))
            assertEquals(expectedBlob1.length.toLong() + 2, issue.getBlobSize("blob1"))
            assertEquals(expectedBlob2.length.toLong() + 2, issue.getBlobSize("blob2"))
        }

        // change
        val expectedResetBlob1 = "Caramba"
        youTrackDb.withStoreTx {
            issue.setBlobString("blob1", expectedResetBlob1)
        }
        youTrackDb.withStoreTx {
            val resetBlob1 = issue.getBlobString("blob1")
            assertEquals(expectedResetBlob1, resetBlob1)
            assertEquals(expectedResetBlob1.length.toLong() + 2, issue.getBlobSize("blob1"))
        }

        // delete
        youTrackDb.withStoreTx {
            issue.deleteBlob("blob1")
        }
        youTrackDb.withStoreTx {
            assertNull(issue.getBlobString("blob1"))
            assertEquals(-1, issue.getBlobSize("blob1"))
            // another blob is still here
            assertEquals(expectedBlob2, issue.getBlobString("blob2"))
            assertEquals(expectedBlob2.length.toLong() + 2, issue.getBlobSize("blob2"))
        }
    }

    @Test
    fun `string blobs size`() {
        val issue = youTrackDb.createIssue("iss")

        // set
        val englishStr = "mamba, mamba, caramba"
        val notEnglishStr = "вы хотите песен, их есть у меня"
        val mixedStr = "magic пипл woodoo пипл"
        youTrackDb.withStoreTx {
            issue.setBlobString("blob1", englishStr)
            issue.setBlobString("blob2", notEnglishStr)
            issue.setBlobString("blob3", mixedStr)
        }
        youTrackDb.withStoreTx {
            assertEquals(englishStr, issue.getBlobString("blob1"))
            assertEquals(notEnglishStr, issue.getBlobString("blob2"))
            assertEquals(mixedStr, issue.getBlobString("blob3"))

            // we use modified UTF-8 for string blobs, it adds the string size to 2 first bytes
            assertEquals(englishStr.length.toLong() + 2, issue.getBlobSize("blob1"))

            assertNotEquals(notEnglishStr.length.toLong(), issue.getBlobSize("blob2"))
            assertNotEquals(notEnglishStr.length.toLong(), issue.getBlobSize("blob2"))
            assertEquals(57, issue.getBlobSize("blob2"))

            assertEquals(32, issue.getBlobSize("blob3"))
        }
    }

    @Test
    fun `add blob should be reflected in get blob names`() {
        val issue = youTrackDb.createIssue("TestBlobs")

        youTrackDb.withStoreTx {
            issue.setBlob("blob1", ByteArrayInputStream(byteArrayOf(0x01, 0x02, 0x03)))
            issue.setBlob("blob2", ByteArrayInputStream(byteArrayOf(0x04, 0x05, 0x06)))
            issue.setBlob("blob3", ByteArrayInputStream(byteArrayOf(0x07, 0x08, 0x09)))
            issue.setProperty("version", 99)
        }

        youTrackDb.withStoreTx {
            val blobNames = issue.getBlobNames()
            assertTrue(blobNames.contains("blob1"))
            assertTrue(blobNames.contains("blob2"))
            assertTrue(blobNames.contains("blob3"))
            assertEquals(3, blobNames.size)
        }
    }

    @Test
    fun `set the same string blob should return false`() {
        val issue = youTrackDb.createIssue("GetPropertyTest")

        val propertyName = "SampleProperty"
        val propertyValue = "SampleValue"
        youTrackDb.withStoreTx {
            issue.setBlobString(propertyName, propertyValue)
        }
        youTrackDb.withStoreTx {
            assertEquals(false, issue.setBlobString(propertyName, propertyValue))
        }
    }

    @Test
    fun `delete links`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
            val oClass = session.schema.getClass(Issues.CLASS)!!
            // pretend that the link is indexed
            oClass.createProperty(
                linkTargetEntityIdPropertyName(linkName),
                PropertyType.LINKBAG
            )
        }

        val issueA = youTrackDb.createIssue("A")
        val issueB = youTrackDb.createIssue("B")
        val issueC = youTrackDb.createIssue("C")
        val issueD = youTrackDb.createIssue("D")

        youTrackDb.withStoreTx {
            issueA.addLink(linkName, issueB)
            issueA.addLink(linkName, issueC)
            issueA.addLink(linkName, issueD)
        }

        youTrackDb.withStoreTx {
            issueA.deleteLink(linkName, issueB)
            issueA.deleteLink(linkName, issueC.id)

            val links = issueA.getLinks(linkName)
            assertEquals(1, links.size())
            assertTrue(links.any { it.id == issueD.id })

            val bag = issueA.vertex.raw().getTargetLocalEntityIds(linkName)
            assertEquals(1, bag.size())
            assertTrue(bag.contains(issueD.vertex.id()))
        }
    }

    @Test
    fun `set links`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
            val oClass = session.schema.getClass(Issues.CLASS)!!
            // pretend that the link is indexed
            oClass.createProperty(
                linkTargetEntityIdPropertyName(linkName),
                PropertyType.LINKBAG
            )
        }

        val issueA = youTrackDb.createIssue("A")
        val issueB = youTrackDb.createIssue("B")
        val issueC = youTrackDb.createIssue("C")

        youTrackDb.withStoreTx {
            assertTrue(issueA.setLink(linkName, issueB))
            assertFalse(issueA.setLink(linkName, issueB))

            assertEquals(issueB, issueA.getLink(linkName))
            val bag = issueA.vertex.raw().getTargetLocalEntityIds(linkName)
            assertEquals(1, bag.size())
            assertTrue(bag.contains(issueB.vertex.id()))
        }

        youTrackDb.withStoreTx {
            assertTrue(issueA.setLink(linkName, issueC))

            assertEquals(issueC, issueA.getLink(linkName))
            val bag = issueA.vertex.raw().getTargetLocalEntityIds(linkName)
            assertEquals(1, bag.size())
            assertTrue(bag.contains(issueC.vertex.id()))
        }

        youTrackDb.withStoreTx {
            assertTrue(issueA.setLink(linkName, issueB.id))
            assertFalse(issueA.setLink(linkName, issueB.id))

            assertEquals(issueB, issueA.getLink(linkName))
            val bag = issueA.vertex.raw().getTargetLocalEntityIds(linkName)
            assertEquals(1, bag.size())
            assertTrue(bag.contains(issueB.vertex.id()))
        }
    }

    @Test
    fun `should delete all links`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
            val oClass = session.schema.getClass(Issues.CLASS)!!
            // pretend that the link is indexed
            oClass.createProperty(
                linkTargetEntityIdPropertyName(linkName),
                PropertyType.LINKBAG
            )
        }

        val issueA = youTrackDb.createIssue("A")
        val issueB = youTrackDb.createIssue("B")
        val issueC = youTrackDb.createIssue("C")

        youTrackDb.withStoreTx {
            issueA.addLink(linkName, issueB)
            issueA.addLink(linkName, issueC)
        }

        youTrackDb.withStoreTx {
            issueA.deleteLinks(linkName)
            val links = issueA.getLinks(linkName)
            assertEquals(0, links.size())
            // the complementary property must also be cleared
            val bag = issueA.vertex.raw().getTargetLocalEntityIds(linkName)
            assertEquals(0, bag.size())
        }
    }

    @Test
    fun `should replace a link correctly`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        val issueA = youTrackDb.createIssue("A")
        val issueB = youTrackDb.createIssue("B")
        val issueC = youTrackDb.createIssue("C")

        youTrackDb.withStoreTx {
            issueA.setLink(linkName, issueB.id)
        }
        youTrackDb.withStoreTx {
            assertEquals(issueB, issueA.getLink(linkName))
        }
        youTrackDb.withStoreTx {
            issueA.setLink(linkName, issueC.id)
        }
        youTrackDb.withStoreTx {
            assertEquals(issueC, issueA.getLink(linkName))
        }
    }

    @Test
    fun `setLink() and addLink() should work correctly with unresolved RIDEntityId`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        val issueA = youTrackDb.createIssue("A")
        val issueB = youTrackDb.createIssue("B")
        val issueC = youTrackDb.createIssue("C")

        youTrackDb.withStoreTx {
            val unresolvedB = PersistentEntityId(issueB.id.typeId, issueB.id.localId)
            issueA.setLink(linkName, unresolvedB)
        }
        youTrackDb.withStoreTx {
            assertEquals(issueB, issueA.getLink(linkName))
        }
        youTrackDb.withStoreTx {
            val unresolvedC = PersistentEntityId(issueC.id.typeId, issueC.id.localId)
            issueB.addLink(linkName, unresolvedC)
        }
        youTrackDb.withStoreTx {
            assertEquals(issueB, issueA.getLink(linkName))
        }
    }

    @Test
    fun `setLink() and addLink() return false if the target entity is not found`() {
        val linkName = "link"
        youTrackDb.withSession { session ->
            session.schema.createEdgeClass(YTDBVertexEntity.edgeClassName(linkName))
        }

        val issueB = youTrackDb.createIssue("A")

        youTrackDb.withStoreTx { tx ->
            issueB.delete()
        }

        youTrackDb.withStoreTx {
            assertFalse(issueB.addLink(linkName, issueB.id))
            assertFalse(issueB.addLink(linkName, PersistentEntityId(300, 300)))
        }

        youTrackDb.withStoreTx {
            assertFalse(issueB.setLink(linkName, issueB.id))
            assertFalse(issueB.setLink(linkName, PersistentEntityId(300, 300)))
        }
    }

    @Test
    fun `should get property`() {
        val issue = youTrackDb.createIssue("GetPropertyTest")

        val propertyName = "SampleProperty"
        val propertyValue = "SampleValue"
        youTrackDb.withStoreTx {
            issue.setProperty(propertyName, propertyValue)
        }
        youTrackDb.withStoreTx {
            val value = issue.getProperty(propertyName)
            assertEquals(propertyValue, value)
        }
    }

    @Test
    fun `should delete property`() {
        val issue = youTrackDb.createIssue("DeletePropertyTest")

        val propertyName = "SampleProperty"
        val propertyValue = "SampleValue"
        youTrackDb.withStoreTx {
            issue.setProperty(propertyName, propertyValue)
        }
        youTrackDb.withStoreTx {
            issue.deleteProperty(propertyName)
            val value = issue.getProperty(propertyName)
            assertNull(value)
        }
    }

    @Test
    fun `set, read, change and delete properties`() {
        val issue = youTrackDb.createIssue("Test1")

        youTrackDb.withStoreTx {
            issue.setProperty("hello", "world")
            issue.setProperty("june", 6)
            issue.setProperty("year", 44L)
            issue.setProperty("floatProp", 1.3f)
            issue.setProperty("doubleProp", 2.3)
            issue.setProperty("dateProp", Date(300))
            issue.setProperty("boolProp", true)
        }

        youTrackDb.withStoreTx {
            assertEquals("world", issue.getProperty("hello"))
            assertEquals(6, issue.getProperty("june"))
            assertEquals(44L, issue.getProperty("year"))
            assertEquals(1.3f, issue.getProperty("floatProp"))
            assertEquals(2.3, issue.getProperty("doubleProp"))
            assertEquals(Date(300), issue.getProperty("dateProp"))
            assertEquals(true, issue.getProperty("boolProp"))
        }

        youTrackDb.withStoreTx {
            assertEquals(false, issue.setProperty("hello", "world"))
            assertEquals(false, issue.setProperty("june", 6))
            assertEquals(false, issue.setProperty("year", 44L))
            assertEquals(false, issue.setProperty("floatProp", 1.3f))
            assertEquals(false, issue.setProperty("doubleProp", 2.3))
            assertEquals(false, issue.setProperty("dateProp", Date(300)))
            assertEquals(false, issue.setProperty("boolProp", true))
        }

        youTrackDb.withStoreTx {
            assertEquals(true, issue.setProperty("hello", "xodus"))
            assertEquals(true, issue.setProperty("june", 8))
            assertEquals(true, issue.setProperty("year", 34L))
            assertEquals(true, issue.setProperty("floatProp", 2.3f))
            assertEquals(true, issue.setProperty("doubleProp", 4.3))
            assertEquals(true, issue.setProperty("dateProp", Date(303)))
            assertEquals(true, issue.setProperty("boolProp", false))
        }

        youTrackDb.withStoreTx {
            assertEquals("xodus", issue.getProperty("hello"))
            assertEquals(8, issue.getProperty("june"))
            assertEquals(34L, issue.getProperty("year"))
            assertEquals(2.3f, issue.getProperty("floatProp"))
            assertEquals(4.3, issue.getProperty("doubleProp"))
            assertEquals(Date(303), issue.getProperty("dateProp"))
            assertEquals(false, issue.getProperty("boolProp"))
        }


        youTrackDb.withStoreTx {
            issue.deleteProperty("dateProp")
            assertNull(issue.getProperty("dateProp"))
            // check that other properties are still there
            assertEquals("xodus", issue.getProperty("hello"))
        }

        youTrackDb.withStoreTx {
            assertEquals(
                listOf(
                    "hello",
                    "name",
                    "june",
                    "year",
                    "floatProp",
                    "doubleProp",
                    "boolProp"
                ).sorted(),
                issue.propertyNames.sorted()
            )
        }
    }

    @Test
    fun `it is forbidden to use entities outside transactions, except for id`() {
        val iss = youTrackDb.createIssue("trista")
        val anotherIss = youTrackDb.createIssue("sto")

        // no properties
        assertFailsWith<IllegalStateException> { iss.getProperty("name") }
        assertFailsWith<IllegalStateException> { iss.setProperty("name", "dvesti") }
        assertFailsWith<IllegalStateException> { iss.propertyNames }
        assertFailsWith<IllegalStateException> { iss.deleteProperty("name") }
        assertFailsWith<IllegalStateException> { iss.getRawProperty("name") }

        // no blobs
        assertFailsWith<IllegalStateException> { iss.getBlob("blob1") }
        assertFailsWith<IllegalStateException> { iss.getBlobSize("blob1") }
        assertFailsWith<IllegalStateException> { iss.getBlobString("blob1") }
        assertFailsWith<IllegalStateException> {
            iss.setBlob(
                "blob1",
                ByteArrayInputStream(byteArrayOf(100))
            )
        }
        assertFailsWith<IllegalStateException> { iss.setBlobString("blob1", "opca") }
        assertFailsWith<IllegalStateException> { iss.deleteBlob("blob1") }

        // no links
        assertFailsWith<IllegalStateException> { iss.getLink("link1") }
        assertFailsWith<IllegalStateException> { iss.linkNames }
        assertFailsWith<IllegalStateException> { iss.setLink("link1", anotherIss) }
        assertFailsWith<IllegalStateException> { iss.deleteLink("link1", anotherIss) }
        assertFailsWith<IllegalStateException> { iss.deleteLinks("link1") }
        assertFailsWith<IllegalStateException> { iss.getLinks("link1") }
        assertFailsWith<IllegalStateException> { iss.getLinks(listOf("link1")); }

        // getting id is ok
        iss.id
    }

    @Test
    fun `dummy unique entityID_localId test`() {
        val localIdSet = hashSetOf<Long>()
        val typeIdSet = hashSetOf<Int>()
        (0..1000).map {
            val issue = youTrackDb.createIssue("Issue$it")
            typeIdSet.add(issue.id.typeId)
            localIdSet.add(issue.id.localId)
        }
        assertEquals(1001, localIdSet.size)
        assertEquals(1, typeIdSet.size)
    }

    @Test
    fun `setProperty and setBlobString returns false in case of equal values`() {
        val iss = youTrackDb.createIssue("trista")
        withStoreTx { tx ->
            iss.setProperty("test", 1)
            iss.setBlobString("blobString", "hello")
        }
        withStoreTx { tx ->
            assertEquals(false, iss.setProperty("test", 1))
            assertEquals(false, iss.setBlobString("blobString", "hello"))
        }
    }

    @Test
    fun `add new link types in a transaction`() {
        withStoreTx { tx ->
            val iss1 = tx.createIssue("iss1")
            val iss2 = tx.createIssue("iss2")

            iss1.addLink("trista", iss2)
        }
    }
}
