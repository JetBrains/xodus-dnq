/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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
import jetbrains.exodus.database.exceptions.EntityRemovedException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.findById
import org.junit.Test
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeleteTest : DBTest() {

    class CompanyTeam(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CompanyTeam>()

        var name by xdRequiredStringProp(trimmed = true)
        var parentTeam: CompanyTeam? by xdLink0_1(CompanyTeam::nestedTeams, onDelete = CLEAR)
        val nestedTeams by xdLink0_N(CompanyTeam::parentTeam, onDelete = CASCADE)
    }

    class Dummy(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Dummy>()

        var name by xdRequiredStringProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(CompanyTeam)
        XdModel.registerNode(Domain)
        XdModel.registerNode(Server)
        XdModel.registerNode(Instance)
        XdModel.registerNode(Dummy)
    }

    @Test
    fun clear() {
        val (user, group) = store.transactional {
            val user = User.new {
                this.login = "mazine"
                this.skill = 1
            }
            val group = RootGroup.new {
                name = "Group"
                users.add(user)
            }

            Pair(user, group)
        }

        store.transactional {
            assertThat(group.users.toList())
                    .containsExactly(user)
        }

        store.transactional {
            user.delete()
        }

        store.transactional {
            assertThat(group.users.toList())
                    .isEmpty()
        }
    }

    @Test
    fun clearCascade() {
        val (parent, nested) = store.transactional {
            val parent = CompanyTeam.new {
                name = "parent"
            }
            val nested = CompanyTeam.new {
                name = "nested"
                this.parentTeam = parent
            }
            Pair(parent, nested)
        }

        store.transactional {
            assertThat(parent.nestedTeams.first()).isEqualTo(nested)
            assertThat(nested.parentTeam).isEqualTo(parent)
        }

        store.transactional {
            nested.delete()
        }

        store.transactional {
            assertThat(parent.nestedTeams.toList()).isEmpty()
        }
    }

    class Domain(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Domain>()

        var name by xdRequiredStringProp(trimmed = true)
        val instances by xdChildren0_N(Instance::parent)
    }

    class Server(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Server>()

        var name by xdRequiredStringProp(trimmed = true)
        val instances by xdLink0_N(Instance::server, onTargetDelete = CLEAR)
    }

    class Instance(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Instance>()

        var name by xdRequiredStringProp(trimmed = true)
        var parent: Domain by xdParent(Domain::instances)
        var server: Server by xdLink1(Server::instances)
    }

    @Test
    fun clearCascadeConcurrent() {
        val server = store.transactional { Server.new { name = "server" } }

        val domains = (0..1).map { i ->
            store.transactional {
                val domain = Domain.new { name = "domain$i" }

                Instance.new { name = "instance$i"
                    this.parent = domain
                    this.server = server
                }

                domain
            }
        }

        val domain1 = domains.first()
        val domain2 = domains.last()

        println("server: ${server.entityId}, domain1: ${domain1.entityId}, domain2: ${domain2.entityId}")

        val bothTxStarted = CountDownLatch(2)
        val firstTxCommited = CountDownLatch(1)

        val deleted = Collections.newSetFromMap(ConcurrentHashMap<EntityId, Boolean>())

        val t1 = thread {
            store.transactional {
                bothTxStarted.countDown()
                bothTxStarted.await()
                domain1.delete()
            }
            firstTxCommited.countDown()
            deleted.add(domain1.entityId)
        }

        val t2 = thread {
            store.transactional {
                bothTxStarted.countDown()
                bothTxStarted.await()
                domain2.delete()
                firstTxCommited.await() // waiting for 1st to commit to cause 2nd tx replay
            }
            deleted.add(domain2.entityId)
        }

        val toWait = Long.MAX_VALUE
        t1.join(toWait)
        t2.join(toWait)

        assertThat(deleted).isEqualTo(setOf(domain1.entityId, domain2.entityId))

        store.transactional {
            assertThat(Domain.all().toList()).isEmpty()
            assertThat(Instance.all().toList()).isEmpty()
        }
    }

    @Test
    fun `transaction should not see the records it has just deleted`() {
        store.transactional {
            Dummy.new { name = "name1" }
            Dummy.new { name = "name2" }
        }

        store.transactional {
            assertEquals(
                setOf("name1", "name2"),
                Dummy.all().toList().map { it.name }.toSet()
            )
            val found = Dummy.filter { it.name eq "name1" }.first()
            found.delete()
            assertEquals(
                setOf("name2"),
                Dummy.all().toList().map { it.name }.toSet()
            )

            assertFailsWith<EntityRemovedException> {
                Dummy.findById(found.xdId)
            }
        }
    }
}
