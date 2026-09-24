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
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryCollector
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Deletion-specific raw adjacency must not change public link reads or mixed-type callback order. */
class OutgoingDeletionReadTest : DBTest() {
    class Root(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Root>()

        val children by xdLink0_N(Child, onDelete = CASCADE)
        var addChildOnRemove by xdBooleanProp()

        override fun destructor() {
            if (addChildOnRemove) children.add(FirstChild.new { name = "late" })
        }
    }

    open class Child(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Child>() {
            val removedNames = mutableListOf<String>()
        }

        var name by xdRequiredStringProp()
        var linkWaitingChildOnRemove by xdBooleanProp()

        override fun destructor() {
            removedNames.add(name)
            if (linkWaitingChildOnRemove) {
                // Called only after the root's phase-1 outgoing child list is materialized.
                val root = Root.all().toList().single()
                val waiting = FirstChild.all().toList().single { it.name == "waiting" }
                root.children.add(waiting)
            }
        }
    }

    class FirstChild(entity: Entity) : Child(entity) {
        companion object : XdNaturalEntityType<FirstChild>()
    }

    class SecondChild(entity: Entity) : Child(entity) {
        companion object : XdNaturalEntityType<SecondChild>()
    }

    class Counter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Counter>()
        var value by xdRequiredIntProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Root, Child, FirstChild, SecondChild, Counter)
        Child.removedNames.clear()
    }

    private fun outgoingSince(before: Map<String, Int>): Int =
        GremlinQueryCollector.countSince(before) { "FollowLink" in it && "OUT" in it && "children" in it }

    private fun incomingSince(before: Map<String, Int>): Int =
        GremlinQueryCollector.countSince(before) { "FollowLink" in it && "IN" in it && "children" in it }

    @Test
    fun `empty outgoing link avoids query in both phases`() {
        val root = transactional { Root.new() }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { root.delete() }
        assertThat(outgoingSince(before)).isEqualTo(0)
    }

    @Test
    fun `single concrete type includes new and committed children and destructor mutation`() {
        val root = transactional {
            val r = Root.new { addChildOnRemove = true }
            r.children.add(FirstChild.new { name = "committed" })
            r
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional {
            root.children.add(FirstChild.new { name = "new" })
            root.delete()
        }
        assertThat(outgoingSince(before)).isEqualTo(0)
        assertThat(Child.removedNames).containsExactly("committed", "new", "late")
        transactional { assertThat(Child.all().toList()).isEmpty() }
    }

    @Test
    fun `child destructor adds link after phase one list and phase two rereads it`() {
        transactional {
            val root = Root.new()
            root.children.add(FirstChild.new {
                name = "trigger"
                linkWaitingChildOnRemove = true
            })
            FirstChild.new { name = "waiting" } // not linked when phase 1 visits root
        }

        val root = transactional { Root.all().toList().single() }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { root.delete() }

        assertThat(outgoingSince(before)).isEqualTo(0)
        // Phase 1 cannot visit "waiting". The child links it only after root's list is read.
        assertThat(Child.removedNames).containsExactly("trigger")
        transactional {
            assertThat(Root.all().toList()).isEmpty()
            assertThat(FirstChild.all().toList()).isEmpty()
        }
    }

    @Test
    fun `removed child is not visited again and surviving child is cascaded`() {
        val root = transactional {
            val r = Root.new()
            r.children.add(FirstChild.new { name = "removed" })
            r.children.add(FirstChild.new { name = "survivor" })
            r
        }
        val removed = transactional { root.children.toList().first { it.name == "removed" } }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional {
            removed.delete()
            root.delete()
        }
        assertThat(outgoingSince(before)).isEqualTo(0)
        assertThat(Child.removedNames).containsExactly("removed", "survivor")
        transactional { assertThat(FirstChild.all().toList()).isEmpty() }
    }

    @Test
    fun `ordinary deletion issues three incoming child queries`() {
        val root = transactional {
            val r = Root.new()
            r.children.add(FirstChild.new { name = "linked" })
            r
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { root.delete() }
        assertThat(incomingSince(before)).isEqualTo(3)
        assertThat(outgoingSince(before)).isEqualTo(0)
    }

    @Test
    fun `replay rereads outgoing children in fresh transaction`() {
        val (root, counter) = transactional {
            val r = Root.new()
            r.children.add(FirstChild.new { name = "linked" })
            r to Counter.new { value = 0 }
        }
        val bothStarted = CyclicBarrier(2)
        val deleted = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        val winnerError = AtomicReference<Throwable?>()
        val loserError = AtomicReference<Throwable?>()
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()

        val winner = thread {
            try {
                store.transactional {
                    bothStarted.await(30, TimeUnit.SECONDS)
                    counter.value = 1
                    check(deleted.await(30, TimeUnit.SECONDS))
                    root.children.add(FirstChild.new { name = "arrived during replay" })
                }
            } catch (t: Throwable) {
                winnerError.set(t)
            } finally {
                winnerCommitted.countDown()
            }
        }
        val loser = thread {
            try {
                store.transactional {
                    bothStarted.await(30, TimeUnit.SECONDS)
                    counter.value = 2 // concurrent write forces the loser to replay
                    try { root.delete() } finally { deleted.countDown() }
                    check(winnerCommitted.await(30, TimeUnit.SECONDS))
                }
            } catch (t: Throwable) {
                loserError.set(t)
            } finally {
                deleted.countDown()
            }
        }
        winner.join(45_000)
        loser.join(45_000)
        assertThat(winner.isAlive).isFalse()
        assertThat(loser.isAlive).isFalse()
        assertThat(winnerError.get()).isNull()
        assertThat(loserError.get()).isNull()
        // Ordinary deletion performs two incoming policy reads plus one validation read.
        // Replay rechecks constraints in the new transaction, which must exceed that baseline.
        assertThat(incomingSince(before)).isGreaterThan(3)
        assertThat(outgoingSince(before)).isEqualTo(0)
        // The winner's child did not exist in the loser's original deletion phase.
        transactional { assertThat(FirstChild.all().toList()).isEmpty() }
    }

    @Test
    fun `mixed concrete types keep query backed callback order`() {
        val root = transactional {
            FirstChild.new { name = "unlinked" } // ensure distinct local ids across types
            val r = Root.new()
            r.children.add(FirstChild.new { name = "first-type" })
            r.children.add(SecondChild.new { name = "second-type" })
            r
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { root.delete() }
        assertThat(outgoingSince(before)).isEqualTo(2)
        assertThat(Child.removedNames).containsExactly("second-type", "first-type").inOrder()
        transactional { assertThat(FirstChild.all().toList().map { it.name }).containsExactly("unlinked") }
    }
}
