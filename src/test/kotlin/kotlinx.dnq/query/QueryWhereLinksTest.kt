package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test
import kotlin.test.assertEquals

class QueryWhereLinksTest : DBTest() {

    @Test
    fun `simple search by link should work`() {
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

}