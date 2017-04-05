package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Ignore
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

            var result = User.filter { it.supervisor = user1 }
            assertEquals(1, result.size())
            assertEquals(user2.entityId, result.first().entityId)

            result = User.filter { it.supervisor eq user1 }
            assertEquals(1, result.size())
            assertEquals(user2.entityId, result.first().entityId)
        }
    }

    @Ignore("Fails because of the kotlinx.dnq.query.SearchingEntity.getLink implementation details")
    @Test
    fun `getting a link should not fail`() {
        store.transactional {
            val user1 = User.new {
                login = "user1"
                skill = 1
            }
            val user2 = User.new {
                login = "user2"
                skill = 1
            }
            user1.contacts.add(Contact.new { email = "test1@users.org" })
            user1.contacts.add(Contact.new { email = "test2@users.org" })

            user2.contacts.add(Contact.new { email = "test3@users.org" })

            it.flush()

            val foundUsers = Contact.filter { it.user eq user1 }
            assertEquals(2, foundUsers.size())
        }
    }

}