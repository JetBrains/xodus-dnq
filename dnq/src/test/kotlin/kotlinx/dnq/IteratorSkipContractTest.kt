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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.PersistentEntityIteratorWrapper
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.events.Foo
import kotlinx.dnq.events.Goo
import kotlinx.dnq.query.XdQuery
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XD-1292 / audit #9-A and #9-C — [jetbrains.exodus.entitystore.EntityIterator.skip] must return the
 * value of `hasNext()` ("is anything left"), not `true` ("I managed to skip that many").
 *
 * Both sites live in `dnq-transient-store`, which has no test source set, so their tests live here
 * and use `dnq`'s own fixtures.
 *
 * **A FRESH `iterator()` per assertion, everywhere.** Every row is a statement about the *initial*
 * iterator state; after the fix `skip` ends in `hasNext()`, which on some implementations has side
 * effects (disposal), so a shared iterator would make later rows depend on earlier ones.
 */
class IteratorSkipContractTest : DBTest() {

    /**
     * A two-link type, needed for the multi-link-name rollover row of #9-C. `dnq`'s shared `events`
     * model only has the single-link `Goo.content`, and the one existing multi-link fixture
     * (`TransientEntityLinksFromSetTest.testAll`) is `@Ignore`d on XD-1118, so this test brings its
     * own.
     */
    class Node(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Node>()

        var name by xdStringProp()
        val alpha by xdLink0_N(Node, dbPropertyName = "alpha", onTargetDelete = OnDeletePolicy.CLEAR)
        val beta by xdLink0_N(Node, dbPropertyName = "beta", onTargetDelete = OnDeletePolicy.CLEAR)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(User, RootGroup, NestedGroup, Image, Contact, Team, Fellow, Foo, Goo, Node)
    }

    private fun XdQuery<*>.entityIterableOrThrow() = entityIterable as EntityIterable

    /**
     * #9-A — `PersistentEntityIteratorWrapper.skip`. This is the iterator every DNQ caller actually
     * receives (`PersistentEntityIterableWrapper.iterator()` constructs it), so fixing the raw
     * `YTDBEntityIterator` alone leaves the contract violation observable at the DNQ layer.
     */
    @Test
    fun `PersistentEntityIteratorWrapper skip returns hasNext`() {
        transactional {
            repeat(3) { i -> User.new { login = "u$i"; skill = i } }
        }
        transactional {
            val users = User.all().entityIterableOrThrow()
            // Precondition: we really are exercising the transient-store wrapper, not the raw iterator.
            assertTrue(users.iterator() is PersistentEntityIteratorWrapper)
            assertEquals(3L, users.size())

            // skipping to exact exhaustion: nothing is left
            assertFalse(users.iterator().skip(3))
            // one element left
            assertTrue(users.iterator().skip(2))
            // skipping past the end: nothing is left
            assertFalse(users.iterator().skip(4))

            // negative n is a no-op: nothing is consumed, and the answer is hasNext()
            val nonEmpty = users.iterator()
            assertTrue(nonEmpty.skip(-1))
            assertEquals(3, nonEmpty.asSequence().count())

            // ... which on an EMPTY iterable is false, where it used to be an unconditional true
            val empty = Image.all().entityIterableOrThrow()
            assertEquals(0L, empty.size())
            assertFalse(empty.iterator().skip(-1))
            assertFalse(empty.iterator().skip(0))
        }
    }

    /**
     * #9-A, the row that distinguishes a fix routed through the class's own `initCurrent()` from one
     * routed through `source.hasNext()`: this iterator filters out entities the transient changes
     * tracker knows as removed, so `source` can still have elements while this iterator is exhausted.
     *
     * **The removed entity must be the LAST one in iteration order.** Deleting the first one is not
     * discriminating: `source` and this iterator then run out at the same moment, and
     * `return source.hasNext()` gives the same answer as `return initCurrent() != null` on every row.
     * With the last one removed there is exactly one position — after two `consumeCurrent()` calls —
     * where `source` still has an element to yield and this iterator does not.
     */
    @Test
    fun `PersistentEntityIteratorWrapper skip respects removed entities`() {
        transactional {
            repeat(3) { i -> User.new { login = "r$i"; skill = i } }
        }
        transactional {
            val all = User.all().entityIterableOrThrow().iterator().asSequence().toList()
            assertEquals(3, all.size)
            all.last().delete() // the LAST in iteration order - see the KDoc

            val users = User.all().entityIterableOrThrow()
            // Precondition: only two survivors as far as THIS iterator is concerned, while the raw
            // source still yields three, the removed one last.
            assertEquals(2, users.iterator().let { it.asSequence().count() })
            assertFalse(users.iterator().skip(2))
            assertTrue(users.iterator().skip(1))
        }
    }

    /**
     * #9-C — the anonymous `EntityIteratorWithPropId` inside
     * `AddedOrRemovedLinksFromSetTransientEntityIterable`. Only the `Set<String>` overloads of
     * `getAddedLinks`/`getRemovedLinks` reach it; the single-name overloads build a
     * `TransientEntityIterable` whose iterator (`TransientEntityIterator`) is already correct. Hence
     * the explicit `setOf(...)` call below.
     *
     * `dispose()` on this iterator throws by design, so the test must never call it.
     */
    @Test
    fun `AddedOrRemovedLinksFromSet iterator skip returns hasNext`() {
        val goo = transactional { Goo.new() }
        transactional {
            repeat(3) { i -> goo.content.add(Foo.new { intField = i }) }

            val added = (goo.entity as TransientEntity).getAddedLinks(setOf("content"))
            assertEquals(3L, added.size())

            // skipping to exact exhaustion: nothing is left
            assertFalse(added.iterator().skip(3))
            // one element left
            assertTrue(added.iterator().skip(2))
            // skipping past the end: nothing is left
            assertFalse(added.iterator().skip(4))

            // Negative / zero n was already a no-op here (the loop guard is `> 0`) and stays one;
            // pinned as preserved behaviour, not as a discriminator.
            assertTrue(added.iterator().skip(-1))
            assertTrue(added.iterator().skip(0))

            // the empty case: no changed links at all -> YTDBEntityIterable.EMPTY, not this iterator
            val removed = (goo.entity as TransientEntity).getRemovedLinks(setOf("content"))
            assertTrue(removed.isEmpty)
        }
    }

    /**
     * #9-C, the row that exercises the class's *defining* behaviour: rolling over from one link name
     * to the next. The single-link row above is passed by a wrong terminal answer of the form
     * `currentIterator?.hasNext() ?: hasNext()` — that variant only ever looks at the link currently
     * being walked, so it reports "nothing left" the moment the first link is exhausted, even though
     * the next link still has elements.
     *
     * Fixture: `alpha` = 1 target, `beta` = 2 targets, queried as `getAddedLinks(setOf("alpha",
     * "beta"))`. `setOf` preserves insertion order, and the iterator walks `linkNames` in that order,
     * so the boundary sits exactly after the first element. **`skip(1)` is the discriminating row:**
     * it lands on the alpha/beta boundary, and the correct terminal `hasNext()` must roll over and
     * answer `true`.
     *
     * `currentLinkName()` after a boundary-crossing `skip` — reported, not silently shipped. It now
     * names the *upcoming* element's link rather than the last consumed one, because the terminal
     * `hasNext()` performs the rollover. That is not a new convention: `hasNext()` has always mutated
     * `currentLinkName`, and `next()` calls `hasNext()` before returning, so the value has only ever
     * been meaningful *immediately after* `next()` — which is how the sole in-repo reader
     * (`TransientEntityLinksFromSetTest.toNamesAndEntities`) uses it. `EntityIteratorWithPropId`
     * documents no contract for it at all (the interface is a bare method). Pinned below so the
     * behaviour is explicit rather than incidental.
     */
    @Test
    fun `AddedOrRemovedLinksFromSet iterator skip rolls over link names`() {
        val root = transactional { Node.new { name = "root" } }
        transactional {
            root.alpha.add(Node.new { name = "a0" })
            root.beta.add(Node.new { name = "b0" })
            root.beta.add(Node.new { name = "b1" })

            val names = setOf("alpha", "beta")
            val added = (root.entity as TransientEntity).getAddedLinks(names)
            assertEquals(3L, added.size())
            // Precondition: the boundary really is after the first element.
            val walk = added.iterator() as EntityIteratorWithPropId
            val linkNamesInOrder = mutableListOf<String?>()
            while (walk.hasNext()) {
                walk.next()
                linkNamesInOrder += walk.currentLinkName()
            }
            assertEquals(listOf<String?>("alpha", "beta", "beta"), linkNamesInOrder.toList())

            // THE discriminating row: skip(1) exhausts `alpha` and must roll over into `beta`.
            assertTrue(added.iterator().skip(1))
            // and the remaining rows still hold across the boundary
            assertTrue(added.iterator().skip(2))
            assertFalse(added.iterator().skip(3))
            assertFalse(added.iterator().skip(4))

            // Pin of the currentLinkName() shift described in the KDoc above.
            val it1 = added.iterator() as EntityIteratorWithPropId
            it1.skip(1)
            assertEquals("beta", it1.currentLinkName(), "skip() looks ahead, so it names the upcoming link")
            // ... whereas a skip that does not cross a boundary leaves it on the current link
            val it2 = added.iterator() as EntityIteratorWithPropId
            it2.skip(2)
            assertEquals("beta", it2.currentLinkName())
            // ... and after next() it always names the element just returned.
            // NB the leading hasNext() is required, and not by this change: next() captures
            // currentIterator BEFORE calling hasNext(), so a next() on a fresh iterator throws
            // NoSuchElementException. That is a pre-existing defect of this class, reported
            // separately and deliberately not fixed here.
            val it3 = added.iterator() as EntityIteratorWithPropId
            assertTrue(it3.hasNext())
            it3.next()
            assertEquals("alpha", it3.currentLinkName())
        }
    }
}
