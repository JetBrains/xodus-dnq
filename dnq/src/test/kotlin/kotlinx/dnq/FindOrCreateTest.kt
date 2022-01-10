/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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
import org.junit.Test
import java.util.*

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

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(ApprovedScope)
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
            val approvedScope2 = store.transactional(isNew = true) {
                ApprovedScope.findOrNew(user, groups)
            }
            Pair(ApprovedScope.findOrNew(user, groups), approvedScope2)
        }
        store.transactional {
            assertThat(approvedScope1).isEqualTo(approvedScope2)
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
}