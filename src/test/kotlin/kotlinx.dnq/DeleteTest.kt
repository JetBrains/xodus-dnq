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

    class CompanyTeam(override val entity: Entity) : XdEntity() {
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