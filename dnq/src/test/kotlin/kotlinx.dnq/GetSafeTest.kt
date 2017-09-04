package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.query.first
import kotlinx.dnq.util.getSafe
import org.junit.Test

class GetSafeTest : DBTest() {

    @Test
    fun `getSafe should return null for undefined properties`() {
        store.transactional {
            val user = User.new()
            assertThat(user.getSafe(User::login)).isNull()
            assertThat(user.getSafe(User::skill)).isNull()
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
            assertThat(user.getSafe(User::login)).isEqualTo("zeckson")
            assertThat(user.getSafe(User::skill)).isEqualTo(1)
        }
    }


    @Test
    fun `getSafe should return null for undefined link`() {
        store.transactional {
            val contact = Contact.new()
            assertThat(contact.getSafe(Contact::user)).isNull()
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
            assertThat(user.contacts.first().getSafe(Contact::user)?.login).isEqualTo("zeckson")
        }
    }


}