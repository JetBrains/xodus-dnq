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

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.creator.findOrNew
import kotlinx.dnq.query.addAll
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class FindOrCreateTest : DBTest() {

    class ApprovedScope(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ApprovedScope>() {
            override val compositeIndices = listOf(
                    listOf(ApprovedScope::user, ApprovedScope::groupsConvolution)
            )

            fun findOrNew(user: User, groups: Sequence<Group>): ApprovedScope {
                val groupsConvolution = groups.map { it.entityId }.sorted().joinToString(":")

                return (findOrNew {
                    this.user = user
                    this.groupsConvolution = groupsConvolution
                }).apply {
                    this.groups.addAll(groups)
                }
            }
        }

        var id by xdRequiredStringProp()
        var user by xdLink1(User)
        val groups by xdLink0_N(Group)
        var groupsConvolution by xdRequiredStringProp()

        override fun constructor() {
            super.constructor()
            id = UUID.randomUUID().toString()
        }
    }

    class JustCounter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<JustCounter>()

        var value by xdRequiredIntProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(ApprovedScope, JustCounter)
    }

    @Test
    fun `sequential creation should return the same entity`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val groups = store.transactional {
            sequenceOf(RootGroup.new { name = "A" }, RootGroup.new { name = "B" })
        }
        val approvedScope1 = store.transactional {
            ApprovedScope.findOrNew(user, groups)
        }
        val approvedScope2 = store.transactional {
            ApprovedScope.findOrNew(user, groups)
        }
        store.transactional {
            assertThat(approvedScope1).isEqualTo(approvedScope2)
        }
    }

    @Test
    fun `parallel creation should return the same entity`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val groups = store.transactional {
            sequenceOf(RootGroup.new { name = "A" }, RootGroup.new { name = "B" })
        }
        val (approvedScope1, approvedScope2) = store.transactional {
            var approvedScope2: ApprovedScope? = null
            thread {
                approvedScope2 = store.transactional {
                    ApprovedScope.findOrNew(user, groups)
                }
            }.join()
            Pair(ApprovedScope.findOrNew(user, groups), approvedScope2)
        }
        store.transactional {
            assertThat(approvedScope1).isEqualTo(approvedScope2)
        }
    }

    @Test
    fun `concurrent creation — orphaned empty vertex must be deleted on replay`() {
        // Two transactions start concurrently and both find nothing for the same (user, groups)
        // query. Both call createEntity. One commits first; the other gets a NeedRetryException
        // (ConcurrentCreateException). During replay, resetIfNew() creates a fresh empty vertex,
        // and creator.find() returns the entity committed by the first transaction.
        //
        // Without the persistentEntity.delete() call (XD-1264 fix), the empty vertex stays in
        // the YTDB transaction as a CREATED record with no properties; YTDB's validation then
        // fails flushAfterReplay with a mandatory-link constraint error.
        //
        // The CyclicBarrier ensures both transactions take their initial snapshots before either
        // has called creator.created(), so both creator.find() calls return null and both go
        // through the createEntity path.
        val user = transactional { User.new { login = "concurrent-create-user"; skill = 1 } }
        val groupA = transactional { RootGroup.new { name = "concurrent-group-A" } }
        val groupB = transactional { RootGroup.new { name = "concurrent-group-B" } }
        val groups = sequenceOf(groupA, groupB)

        val barrier = CyclicBarrier(2)
        var result1: ApprovedScope? = null
        var result2: ApprovedScope? = null
        var error1: Throwable? = null
        var error2: Throwable? = null

        val t1 = thread {
            try {
                result1 = transactional { barrier.await(); ApprovedScope.findOrNew(user, groups) }
            } catch (e: Throwable) { error1 = e }
        }
        val t2 = thread {
            try {
                result2 = transactional { barrier.await(); ApprovedScope.findOrNew(user, groups) }
            } catch (e: Throwable) { error2 = e }
        }
        t1.join(); t2.join()

        assertThat(error1).isNull()
        assertThat(error2).isNull()
        transactional {
            // Exactly one scope must exist — no orphaned empty vertex, no duplicates.
            assertThat(ApprovedScope.all().toList()).hasSize(1)
            // Both threads must have received the same entity.
            assertThat(result1).isEqualTo(result2)
        }
    }

    @Test
    fun `different parameters should result into different entities`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val groups = store.transactional {
            sequenceOf(RootGroup.new { name = "A" }, RootGroup.new { name = "B" })
        }
        val approvedScope1 = store.transactional {
            ApprovedScope.findOrNew(user, groups)
        }
        val approvedScope2 = store.transactional {
            ApprovedScope.findOrNew(user, groups.take(1))
        }
        store.transactional {
            assertThat(approvedScope1).isNotEqualTo(approvedScope2)
        }
    }

    @Test
    fun `simple findOrNew`() {
        val user = store.transactional {
            User.new { login = "zeckson"; skill = 1 }
        }
        val user1 = store.transactional {
            User.findOrNew {
                login = "zeckson1"
                skill = 2
            }
        }
        val user2 = store.transactional {
            User.findOrNew {
                login = "zeckson"
                skill = 1
            }
        }
        store.transactional {
            assertThat(user1).isNotEqualTo(user2)
            assertThat(user2).isEqualTo(user)
        }
    }

    @Test
    fun `findOrNew with replay`() {
        val counter = transactional { JustCounter.new { value = 0 } }

        val userName = "test findOrNew"
        val boss = transactional { User.new { login = "supervisor"; skill = 123 } }
        val user = transactional { User.new {
            login = userName
            skill = 123
            supervisor = boss
        } }

        val start = CyclicBarrier(2)
        val end = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val t1 = pool.submit {
            transactional {
                start.await()
                counter.value += 1
                user.delete()
            }
            transactional {
                assertThat(User.all().toList()).hasSize(1)
            }
            end.countDown()
        }

        val t2 = pool.submit {
            transactional {
                start.await()
                counter.value += 1
                User.findOrNew(User.filter { it.login eq userName }) {
                    login = userName
                    skill = 456
                    supervisor = boss
                }
                end.await()
            }
        }

        listOf(t1, t2).forEach { it.get() }

        transactional {
            val users = User.all().filter { it.login eq userName }.toList()

            assertThat(users).hasSize(1)
            assertThat(users.first().login).isEqualTo(userName)
            assertThat(users.first().entityId).isNotEqualTo(user.entityId)
        }
    }
}
