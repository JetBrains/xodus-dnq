package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.query.first
import kotlinx.dnq.query.isEmpty
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteTest : DBTest() {

    class Team(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Team>()

        var name by xdRequiredStringProp(trimmed = true)
        var parentTeam: Team? by xdLink0_1(Team::nestedTeams, onDelete = CLEAR)
        val nestedTeams by xdLink0_N(Team::parentTeam, onDelete = CASCADE)
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Team)
    }

    @Test
    fun clear() {
        val (user, group) = store.transactional { txn ->
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
            assertEquals(user, group.users.first())
        }

        store.transactional {
            user.delete()
        }

        store.transactional {
            assertTrue(group.users.isEmpty)
        }
    }

    @Test
    fun clearCascade() {
        val (parent, nested) = store.transactional { txn ->
            val parent = Team.new {
                name = "parent"
            }
            val nested = Team.new {
                name = "nested"
                this.parentTeam = parent
            }
            Pair(parent, nested)
        }

        store.transactional {
            assertEquals(nested, parent.nestedTeams.first())
            assertEquals(parent, nested.parentTeam)
        }

        store.transactional {
            nested.delete()
        }

        store.transactional {
            assertTrue(parent.nestedTeams.isEmpty)
        }

    }
}