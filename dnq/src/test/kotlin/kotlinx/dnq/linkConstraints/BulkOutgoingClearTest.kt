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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.LinkChange
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.entitystore.youtrackdb.getTargetLocalEntityIds
import jetbrains.exodus.entitystore.youtrackdb.raw
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * Deleting a source clears its directed, non-cascading plural link with a guarded bulk removal.
 * The observable result must be the one of removing the selected targets one by one: the edges
 * are gone before the flush, the change tracking has the same membership and order, and an
 * adjacency that is not exactly the selection keeps the per-target removal.
 */
class BulkOutgoingClearTest : DBTest() {
    class Root(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Root>()
        val items by xdLink0_N(Item, onDelete = OnDeletePolicy.CLEAR)
    }

    open class Item(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Item>()
    }

    class ItemA(entity: Entity) : Item(entity) {
        companion object : XdNaturalEntityType<ItemA>()
    }

    class ItemB(entity: Entity) : Item(entity) {
        companion object : XdNaturalEntityType<ItemB>()
    }

    /** The link takes part in a unique composite index, so it has a complementary bag. */
    class KeyedRoot(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<KeyedRoot>() {
            override val compositeIndices = listOf(listOf(KeyedRoot::items, KeyedRoot::key))
        }

        var key by xdRequiredStringProp()
        val items by xdLink0_N(Item, onDelete = OnDeletePolicy.CLEAR)
    }

    /** `[1..n]` link. */
    class RequiredRoot(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RequiredRoot>()
        val items by xdLink1_N(Item, onDelete = OnDeletePolicy.CLEAR)
    }

    /** The link may point back at its own source. */
    class Node(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Node>()
        val nodes by xdLink0_N(Node, onDelete = OnDeletePolicy.CLEAR)
    }

    class Counter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Counter>()
        var value by xdRequiredIntProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Root, Item, ItemA, ItemB, KeyedRoot, RequiredRoot, Node, Counter)
    }

    private fun persistent(entity: XdEntity) = (entity.entity as TransientEntity).entity as YTDBVertexEntity

    private fun rawEdges(entity: XdEntity, linkName: String = "items") =
        persistent(entity).countLinksUpTo(linkName, Int.MAX_VALUE)

    private fun rawBagSize(entity: XdEntity, linkName: String = "items") =
        persistent(entity).vertex.raw().getTargetLocalEntityIds(linkName).size()

    private fun linkChange(entity: XdEntity, linkName: String = "items"): LinkChange? =
        store.threadSession!!.transientChangesTracker
            .getChangedLinksDetailed(entity.entity as TransientEntity)?.get(linkName)

    private fun ids(entities: Collection<TransientEntity>?) = entities.orEmpty().map { it.id }

    private fun mixedItems() = listOf<Item>(
        ItemA.new(), ItemB.new(), ItemA.new(), ItemB.new(), ItemA.new(), ItemB.new()
    )

    @Test
    fun `saved source with mixed target types is cleared before the flush and the targets survive`() {
        val (root, items) = transactional {
            val items = mixedItems()
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        transactional {
            val expectedOrder = root.items.toList().map { it.entity.id }
            assertThat(expectedOrder).hasSize(items.size)
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
            assertThat(ids(linkChange(root)!!.removedEntities)).containsExactlyElementsIn(expectedOrder).inOrder()
            assertThat(linkChange(root)!!.addedEntities.orEmpty()).isEmpty()
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactlyElementsIn(items)
        }
    }

    @Test
    fun `source and targets created in the same transaction`() {
        val items = transactional {
            val items = mixedItems()
            val root = Root.new { items.forEach { this.items.add(it) } }
            assertThat(rawEdges(root)).isEqualTo(items.size)
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
            items
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactlyElementsIn(items)
        }
    }

    @Test
    fun `required plural link is cleared with its source`() {
        val (root, items) = transactional {
            val items = mixedItems()
            RequiredRoot.new { items.forEach { this.items.add(it) } } to items
        }
        transactional {
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
        }
        transactional {
            assertThat(RequiredRoot.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactlyElementsIn(items)
        }
    }

    @Test
    fun `a self link is cleared with the others and reported as a deleted target`() {
        val (node, others) = transactional {
            val others = listOf(Node.new(), Node.new())
            val node = Node.new()
            node.nodes.add(node)
            others.forEach { node.nodes.add(it) }
            node to others
        }
        transactional {
            node.delete()
            assertThat(rawEdges(node, "nodes")).isEqualTo(0)
            // the source is not removed yet while its links are cleared; once it is, the tracker
            // moves it from the removed to the deleted targets of that same change
            val change = linkChange(node, "nodes")!!
            assertThat(ids(change.removedEntities)).containsExactlyElementsIn(others.map { it.entity.id })
            assertThat(ids(change.deletedEntities)).containsExactly(node.entity.id)
        }
        transactional {
            assertThat(Node.all().toList()).containsExactlyElementsIn(others)
            others.forEach { assertThat(it.nodes.toList()).isEmpty() }
        }
    }

    @Test
    fun `an empty and a single-target link are cleared as before`() {
        val (empty, single, target) = transactional {
            val target = ItemA.new()
            Triple(Root.new(), Root.new { items.add(target) }, target)
        }
        transactional {
            empty.delete()
            single.delete()
            assertThat(rawEdges(single)).isEqualTo(0)
            assertThat(ids(linkChange(single)!!.removedEntities)).containsExactly(target.entity.id)
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactly(target)
        }
    }

    @Test
    fun `links added in this transaction cancel out and leave no empty link change`() {
        val root = transactional { Root.new() }
        transactional {
            val a = ItemA.new()
            val b = ItemB.new()
            root.items.add(a)
            root.items.add(b)
            assertThat(linkChange(root)!!.changeType.name).isEqualTo("ADD")
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
            // every addition was cancelled by its removal: no entry, so no "No added or removed links."
            assertThat(linkChange(root)).isNull()
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).hasSize(2)
        }
    }

    @Test
    fun `a link added in this transaction is cancelled and the saved ones are reported as removed`() {
        val (root, saved) = transactional {
            val saved = listOf(ItemA.new(), ItemB.new())
            Root.new { saved.forEach { items.add(it) } } to saved
        }
        transactional {
            val added = ItemA.new()
            root.items.add(added)
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
            val change = linkChange(root)!!
            assertThat(change.addedEntities.orEmpty()).isEmpty()
            assertThat(ids(change.removedEntities)).containsExactlyElementsIn(saved.map { it.entity.id })
        }
        transactional {
            assertThat(Item.all().toList()).hasSize(3)
        }
    }

    @Test
    fun `an already removed target declines the bulk clearing and keeps its edge until the flush`() {
        val (root, items) = transactional {
            val items = listOf(ItemA.new(), ItemA.new(), ItemA.new())
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        transactional {
            items[0].delete()
            root.delete()
            // the edge to the removed target is left for the native deletion of that vertex
            assertThat(rawEdges(root)).isEqualTo(1)
            assertThat(ids(linkChange(root)!!.removedEntities))
                .containsExactly(items[1].entity.id, items[2].entity.id).inOrder()
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactly(items[1], items[2])
        }
    }

    @Test
    fun `targets deleted together with the source are removed`() {
        val (root, items) = transactional {
            val items = mixedItems()
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        transactional {
            root.delete()
            items.forEach { it.delete() }
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).isEmpty()
        }
    }

    @Test
    fun `a rolled back deletion restores the source and its links`() {
        val (root, items) = transactional {
            val items = mixedItems()
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        assertFailsWith<IllegalStateException> {
            transactional {
                root.delete()
                assertThat(rawEdges(root)).isEqualTo(0)
                throw IllegalStateException("abort")
            }
        }
        transactional {
            assertThat(Root.all().toList()).containsExactly(root)
            assertThat(root.items.toList()).containsExactlyElementsIn(items)
            assertThat(rawEdges(root)).isEqualTo(items.size)
        }
    }

    @Test
    fun `deletion after an intermediate flush`() {
        val (root, items) = transactional {
            val items = mixedItems()
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        transactional { session ->
            root.items.add(ItemB.new())
            session.flush()
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
        }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).hasSize(items.size + 1)
        }
    }

    @Test
    fun `companion bag of an indexed link is emptied and its unique index is free in the same transaction`() {
        val (root, items) = transactional {
            val items = mixedItems()
            KeyedRoot.new { key = "k"; items.forEach { this.items.add(it) } } to items
        }
        transactional {
            assertThat(rawBagSize(root)).isEqualTo(items.size)
            root.delete()
            assertThat(rawEdges(root)).isEqualTo(0)
            assertThat(rawBagSize(root)).isEqualTo(0)
            // the same (target, key) pairs are unique again
            KeyedRoot.new { key = "k"; items.forEach { this.items.add(it) } }
        }
        transactional {
            val current = KeyedRoot.all().toList().single()
            assertThat(current).isNotEqualTo(root)
            assertThat(current.items.toList()).containsExactlyElementsIn(items)
            assertThat(rawBagSize(current)).isEqualTo(items.size)
        }
    }

    @Test
    fun `a rolled back deletion restores the companion bag`() {
        val (root, items) = transactional {
            val items = mixedItems()
            KeyedRoot.new { key = "k"; items.forEach { this.items.add(it) } } to items
        }
        assertFailsWith<IllegalStateException> {
            transactional {
                root.delete()
                throw IllegalStateException("abort")
            }
        }
        transactional {
            assertThat(rawBagSize(root)).isEqualTo(items.size)
            assertThat(rawEdges(root)).isEqualTo(items.size)
            assertThat(root.items.toList()).containsExactlyElementsIn(items)
        }
    }

    /**
     * The losing transaction deletes [root] while the winner runs [winnerWork] and commits first.
     * Both write the counter, so the losing flush can only succeed by replaying its changes.
     */
    private fun deleteRootLosingTheRace(root: Root, winnerWork: () -> Unit) {
        transactional { Counter.new { value = 0 } }
        val counter = transactional { Counter.all().toList().single() }

        val start = CyclicBarrier(2)
        val loserWorkDone = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        var loserError: Throwable? = null
        var winnerError: Throwable? = null
        val pool = Executors.newFixedThreadPool(2)
        try {
            val winner = pool.submit {
                try {
                    transactional {
                        start.await(60, TimeUnit.SECONDS)
                        counter.value += 1
                        winnerWork()
                        check(loserWorkDone.await(30, TimeUnit.SECONDS)) { "loser did not finish its work" }
                    }
                } catch (t: Throwable) {
                    winnerError = t
                } finally {
                    winnerCommitted.countDown()
                }
            }
            val loser = pool.submit {
                try {
                    transactional {
                        start.await(60, TimeUnit.SECONDS)
                        counter.value += 1
                        try {
                            root.delete()
                            assertThat(rawEdges(root)).isEqualTo(0)
                        } finally {
                            loserWorkDone.countDown()
                        }
                        check(winnerCommitted.await(30, TimeUnit.SECONDS)) { "winner did not commit" }
                    }
                } catch (t: Throwable) {
                    loserError = t
                } finally {
                    loserWorkDone.countDown()
                }
            }
            listOf(winner, loser).forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(winnerError).isNull()
        assertThat(loserError).isNull()
        // A replay applies the captured value, so the loser's 1 overwrites the winner's 1. Without
        // a replay the flush would have failed, or both increments would be visible.
        assertThat(transactional { Counter.all().toList().single().value }).isEqualTo(1)
    }

    @Test
    fun `replay with unchanged adjacency clears the links again`() {
        val (root, items) = transactional {
            val items = mixedItems()
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        deleteRootLosingTheRace(root) { }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactlyElementsIn(items)
        }
    }

    @Test
    fun `replay against an adjacency that lost a target declines and removes the remaining ones`() {
        val (root, items) = transactional {
            val items = mixedItems()
            Root.new { items.forEach { this.items.add(it) } } to items
        }
        deleteRootLosingTheRace(root) { root.items.remove(items[0]) }
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsExactlyElementsIn(items)
        }
    }

    @Test
    fun `replay of a source and targets created in the losing transaction`() {
        transactional { Root.new() } // keeps the races' entity ids apart from the transient ones
        val root = transactional { Root.new() }
        transactional { Counter.new { value = 0 } }
        val counter = transactional { Counter.all().toList().single() }

        val start = CyclicBarrier(2)
        val loserWorkDone = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        var loserError: Throwable? = null
        var winnerError: Throwable? = null
        val pool = Executors.newFixedThreadPool(2)
        try {
            val winner = pool.submit {
                try {
                    transactional {
                        start.await(60, TimeUnit.SECONDS)
                        counter.value += 1
                        check(loserWorkDone.await(30, TimeUnit.SECONDS)) { "loser did not finish its work" }
                    }
                } catch (t: Throwable) {
                    winnerError = t
                } finally {
                    winnerCommitted.countDown()
                }
            }
            val loser = pool.submit {
                try {
                    transactional {
                        start.await(60, TimeUnit.SECONDS)
                        counter.value += 1
                        try {
                            val created = Root.new { mixedItems().forEach { this.items.add(it) } }
                            assertThat(rawEdges(created)).isEqualTo(6)
                            created.delete()
                            assertThat(rawEdges(created)).isEqualTo(0)
                            // a surviving saved source keeps its links through the replay
                            root.items.add(ItemA.new())
                        } finally {
                            loserWorkDone.countDown()
                        }
                        check(winnerCommitted.await(30, TimeUnit.SECONDS)) { "winner did not commit" }
                    }
                } catch (t: Throwable) {
                    loserError = t
                } finally {
                    loserWorkDone.countDown()
                }
            }
            listOf(winner, loser).forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(winnerError).isNull()
        assertThat(loserError).isNull()
        transactional {
            assertThat(Counter.all().toList().single().value).isEqualTo(1)
            // the created source is gone; the saved root kept its new link
            assertThat(Root.all().toList()).hasSize(2)
            assertThat(root.items.toList()).hasSize(1)
            assertThat(Item.all().toList()).hasSize(7)
        }
    }
}
