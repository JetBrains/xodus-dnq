package kotlinx.dnq

import kotlinx.dnq.query.first
import kotlinx.dnq.query.isEmpty
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteTest : DBTest() {

    @Test
    fun clear() {
        val login = "mazine"

        val (user, group) = store.transactional { txn ->
            val user = User.new {
                this.login = login
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
}