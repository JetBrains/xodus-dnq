package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test
import kotlin.test.assertEquals

class FilterQueryLinksTest : DBTest() {

    @Test
    fun `search by undirected association should work`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            val contact1 = Contact.new {
                user = user1
                email = "123@test.com"
            }
            Contact.new {
                user = user2
                email = "123@test.com"
            }

            assertEquals(contact1.entityId, Contact.filter { it.user = user1 }.first().entityId)
        }
    }

    @Test
    fun `simple search by directed association`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
                supervisor = user1
            }

            assertEquals(user2.entityId, User.filter { it.supervisor = user1 }.first().entityId)
        }
    }

}