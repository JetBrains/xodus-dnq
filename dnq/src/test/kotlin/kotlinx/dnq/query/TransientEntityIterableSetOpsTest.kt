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
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.events.Foo
import kotlinx.dnq.events.Goo
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import kotlinx.dnq.util.getAddedLinks
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests that QueryEngine set operations (query/filter, intersect, union, exclude,
 * selectDistinct, selectManyDistinct) work correctly when one operand is a
 * TransientEntityIterable.
 *
 * TransientEntityIterable implements EntityIterable but throws UnsupportedOperationException
 * on intersect/union/minus/selectDistinct/selectManyDistinct. The QueryEngine must detect
 * non-persistent EntityIterables and fall back to in-memory implementations.
 */
class TransientEntityIterableSetOpsTest : DBTest() {

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Foo, Goo)
    }

    /**
     * Helper: runs a block inside a Goo updatedSync listener where getAddedLinks(Goo::content)
     * produces a TransientEntityIterable-backed XdQuery<Foo>.
     *
     * Returns the result of [block] or throws if the listener threw.
     */
    private fun <T> withAddedFooLinks(
        fooCount: Int = 3,
        block: (addedLinks: XdQuery<Foo>) -> T
    ): T {
        val g = store.transactional { Goo.new() }
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                try {
                    result.set(block(old.getAddedLinks(Goo::content)))
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        })
        store.transactional {
            repeat(fooCount) { i ->
                g.content.add(Foo.new { intField = i + 1 })
            }
        }
        error.get()?.let { throw AssertionError("Listener should not crash", it) }
        return result.get()
    }

    // --- QueryEngine.query path (filter) ---

    @Test
    fun `filter TransientEntityIterable`() {
        val values = withAddedFooLinks {
            it.filter { f -> f.intField eq 2 }.toList().map { f -> f.intField }
        }
        assertThat(values).containsExactly(2)
    }

    // --- canAggregate path: intersect, union, exclude ---

    @Test
    fun `intersect TransientEntityIterable with persistent query`() {
        val values = withAddedFooLinks {
            it.intersect(Foo.all()).toList().map { f -> f.intField }
        }
        assertThat(values).containsExactly(1, 2, 3)
    }

    @Test
    fun `union TransientEntityIterable with persistent query`() {
        // pre-create a Foo that won't be in the added links
        store.transactional { Foo.new { intField = 99 } }
        val g = store.transactional { Goo.new() }
        val result = AtomicReference<List<Int>>()
        val error = AtomicReference<Throwable>()
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                try {
                    val added = old.getAddedLinks(Goo::content)
                    val unionResult = added.union(Foo.all()).toList().map { it.intField }
                    result.set(unionResult)
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        })
        store.transactional {
            g.content.add(Foo.new { intField = 1 })
        }
        error.get()?.let { throw AssertionError("Listener should not crash", it) }
        assertThat(result.get()).containsExactly(1, 99)
    }

    @Test
    fun `exclude persistent query from TransientEntityIterable`() {
        // pre-create a Foo that will also be added to Goo
        val existing = store.transactional { Foo.new { intField = 99 } }
        val g = store.transactional { Goo.new() }
        val result = AtomicReference<List<Int>>()
        val error = AtomicReference<Throwable>()
        Goo.addListener(store, object : XdEntityListener<Goo> {
            override fun updatedSync(old: Goo, current: Goo) {
                try {
                    val added = old.getAddedLinks(Goo::content)
                    // Query that matches only intField==99
                    val toExclude = Foo.all().filter { it.intField eq 99 }
                    // addedLinks(1,2,99) \ toExclude(99) should keep 1 and 2
                    // TransientEntityIterable is on the LEFT — this is the crashing path
                    val excludeResult = added.exclude(toExclude).toList().map { it.intField }
                    result.set(excludeResult)
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        })
        store.transactional {
            g.content.add(Foo.new { intField = 1 })
            g.content.add(Foo.new { intField = 2 })
            g.content.add(existing)
        }
        error.get()?.let { throw AssertionError("Listener should not crash", it) }
        assertThat(result.get()).containsExactly(1, 2)
    }

    // --- selectDistinct / selectManyDistinct path ---

    @Test
    fun `mapDistinct on TransientEntityIterable`() {
        // Setup: RootGroup links to Users, User has supervisor (0_1 link)
        val (_, worker1, worker2) = store.transactional {
            val boss = User.new { login = "boss"; skill = 1 }
            val w1 = User.new { login = "w1"; skill = 1; supervisor = boss }
            val w2 = User.new { login = "w2"; skill = 1; supervisor = boss }
            Triple(boss, w1, w2)
        }
        val group = store.transactional { RootGroup.new { name = "g1" } }
        val result = AtomicReference<List<String>>()
        val error = AtomicReference<Throwable>()
        RootGroup.addListener(store, object : XdEntityListener<RootGroup> {
            override fun updatedSync(old: RootGroup, current: RootGroup) {
                try {
                    val addedUsers = old.getAddedLinks(RootGroup::users)
                    // mapDistinct(supervisor) calls queryEngine.selectDistinct()
                    val supervisors = addedUsers
                        .mapDistinct(User::supervisor)
                        .toList()
                        .map { it.login }
                    result.set(supervisors)
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        })
        store.transactional {
            group.users.add(worker1)
            group.users.add(worker2)
        }
        error.get()?.let { throw AssertionError("Listener should not crash", it) }
        assertThat(result.get()).containsExactly("boss")
    }

    @Test
    fun `flatMapDistinct on TransientEntityIterable`() {
        // Setup: RootGroup links to Users, User has contacts (0_N link)
        val (user1, user2) = store.transactional {
            val u1 = User.new { login = "u1"; skill = 1 }
            Contact.new { user = u1; email = "a@test.com" }
            Contact.new { user = u1; email = "b@test.com" }
            val u2 = User.new { login = "u2"; skill = 1 }
            Contact.new { user = u2; email = "c@test.com" }
            Pair(u1, u2)
        }
        val group = store.transactional { RootGroup.new { name = "g2" } }
        val result = AtomicReference<List<String>>()
        val error = AtomicReference<Throwable>()
        RootGroup.addListener(store, object : XdEntityListener<RootGroup> {
            override fun updatedSync(old: RootGroup, current: RootGroup) {
                try {
                    val addedUsers = old.getAddedLinks(RootGroup::users)
                    // flatMapDistinct(contacts) calls queryEngine.selectManyDistinct()
                    val emails = addedUsers
                        .flatMapDistinct(User::contacts)
                        .toList()
                        .map { it.email }
                    result.set(emails)
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        })
        store.transactional {
            group.users.add(user1)
            group.users.add(user2)
        }
        error.get()?.let { throw AssertionError("Listener should not crash", it) }
        assertThat(result.get()).containsExactly("a@test.com", "b@test.com", "c@test.com")
    }
}
