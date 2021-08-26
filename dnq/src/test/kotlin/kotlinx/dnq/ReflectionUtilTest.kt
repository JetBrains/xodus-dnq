/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentEntity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.single
import kotlinx.dnq.util.isDefined
import org.junit.Test


class ReflectionUtilTest : DBTest() {

    class TestGroup(entity: Entity) : RootGroup(entity) {
        companion object : XdNaturalEntityType<TestGroup>()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(TestGroup)
    }

    @Test
    fun `isDefined`() {
        store.transactional {
            it.createPersistentEntity(TestGroup) {
                setProperty(Group::name.name, "root")
            }
        }

        store.transactional {
            val rootGroup = TestGroup.all().single()
            assertThat(rootGroup.isDefined(Group::name)).isTrue()
            assertThat(rootGroup.isDefined(Group::users)).isFalse()
            assertThat(rootGroup.isDefined(Group::nestedGroups)).isFalse()
            assertThat(rootGroup.isDefined(Group::owner)).isFalse()
            assertThat(rootGroup.isDefined(Group::autoJoin)).isTrue()
            assertThat(rootGroup.isDefined(TestGroup::name)).isTrue()
            assertThat(rootGroup.isDefined(TestGroup::users)).isFalse()
            assertThat(rootGroup.isDefined(TestGroup::nestedGroups)).isFalse()
            assertThat(rootGroup.isDefined(TestGroup::owner)).isFalse()
            assertThat(rootGroup.isDefined(TestGroup::autoJoin)).isTrue()

            val admin = User.new {
                login = "admin"
                skill = 1
            }
            rootGroup.users.add(admin)
        }

        store.transactional {
            val rootGroup = RootGroup.all().single()

            assertThat(rootGroup.isDefined(Group::users)).isTrue()
            assertThat(rootGroup.isDefined(RootGroup::users)).isTrue()
        }

        store.transactional {
            it.createPersistentEntity(NestedGroup) {
                setProperty(Group::name.name, "nested")
            }
        }

        store.transactional {
            val rootGroup = RootGroup.all().single()
            val nestedGroup = NestedGroup.all().single()

            assertThat(nestedGroup.isDefined(Group::name)).isTrue()
            assertThat(nestedGroup.isDefined(Group::users)).isFalse()
            assertThat(nestedGroup.isDefined(Group::nestedGroups)).isFalse()
            assertThat(nestedGroup.isDefined(Group::owner)).isFalse()
            assertThat(nestedGroup.isDefined(Group::autoJoin)).isFalse()
            assertThat(nestedGroup.isDefined(NestedGroup::name)).isTrue()
            assertThat(nestedGroup.isDefined(NestedGroup::users)).isFalse()
            assertThat(nestedGroup.isDefined(NestedGroup::nestedGroups)).isFalse()
            assertThat(nestedGroup.isDefined(NestedGroup::owner)).isFalse()
            assertThat(nestedGroup.isDefined(NestedGroup::autoJoin)).isFalse()

            rootGroup.nestedGroups.add(nestedGroup)
            nestedGroup.owner = User.all().single(User::login eq "admin")
            nestedGroup.autoJoin = true
        }

        store.transactional {
            val rootGroup = RootGroup.all().single()
            val nestedGroup = NestedGroup.all().single()

            assertThat(nestedGroup.isDefined(Group::autoJoin)).isTrue()
            assertThat(nestedGroup.isDefined(NestedGroup::autoJoin)).isTrue()
            assertThat(rootGroup.isDefined(Group::nestedGroups)).isTrue()
            assertThat(nestedGroup.isDefined(Group::owner)).isTrue()
            assertThat(rootGroup.isDefined(RootGroup::nestedGroups)).isTrue()
            assertThat(nestedGroup.isDefined(NestedGroup::owner)).isTrue()
        }

    }

    private fun <T : XdEntity> TransientStoreSession.createPersistentEntity(entityType: XdEntityType<T>, init: PersistentEntity.() -> Unit) {
        this.persistentTransaction.store.beginTransaction().apply {
            (newEntity(entityType.entityType) as PersistentEntity).init()
        }.flush()
    }
}