package kotlinx.dnq

import kotlinx.dnq.query.first
import kotlinx.dnq.util.getSafe
import org.junit.Test
import kotlin.test.assertEquals

class GetSafeTest : DBTest() {

    @Test
    fun `getSafe should return null for undefined properties`() {
        store.transactional {
            val user = User.new()
            assertEquals(null, user.getSafe(User::login))
            assertEquals(null, user.getSafe(User::skill))
            user.delete()
        }
    }

    @Test
    fun `getSafe should return value for defined properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertEquals("zeckson", user.getSafe(User::login))
            assertEquals(1, user.getSafe(User::skill))
        }
    }


    @Test
    fun `getSafe should return null for undefined link`() {
        store.transactional {
            val contact = Contact.new()
            assertEquals(null, contact.getSafe(Contact::user))
            contact.delete()
        }
    }

    @Test
    fun `getSafe should return value for defined link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                contacts.add(Contact.new { email = "zeckson@spb.com" })
            }
            assertEquals("zeckson", user.contacts.first().getSafe(Contact::user)?.login)
        }
    }


}