package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.PersistentEntity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.single
import kotlinx.dnq.util.isDefined
import org.junit.Test


class ReflectionUtilTest : DBTest() {

    @Test
    fun `isDefined`() {
        store.transactional {
            it.createPersistentEntity(RootGroup) {
                setProperty(Group::name.name, "root")
            }
        }

        store.transactional {
            val rootGroup = RootGroup.all().single()
            assertThat(rootGroup.isDefined(Group::name)).isTrue()
            assertThat(rootGroup.isDefined(Group::users)).isFalse()
            assertThat(rootGroup.isDefined(Group::nestedGroups)).isFalse()
            assertThat(rootGroup.isDefined(Group::owner)).isFalse()
            assertThat(rootGroup.isDefined(Group::autoJoin)).isTrue()
            assertThat(rootGroup.isDefined(RootGroup::name)).isTrue()
            assertThat(rootGroup.isDefined(RootGroup::users)).isFalse()
            assertThat(rootGroup.isDefined(RootGroup::nestedGroups)).isFalse()
            assertThat(rootGroup.isDefined(RootGroup::owner)).isFalse()
            assertThat(rootGroup.isDefined(RootGroup::autoJoin)).isTrue()

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