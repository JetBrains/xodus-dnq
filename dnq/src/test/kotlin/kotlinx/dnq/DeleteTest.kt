/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import org.junit.Test

class DeleteTest : DBTest() {

    class CompanyTeam(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CompanyTeam>()

        var name by xdRequiredStringProp(trimmed = true)
        var parentTeam: CompanyTeam? by xdLink0_1(CompanyTeam::nestedTeams, onDelete = CLEAR)
        val nestedTeams by xdLink0_N(CompanyTeam::parentTeam, onDelete = CASCADE)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(CompanyTeam)
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
}