package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test

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

            assertThat(Contact.filter { it.user = user1 }.first()).isEqualTo(contact1)
            assertThat(Contact.filter { it.user eq user1 }.first()).isEqualTo(contact1)
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
            assertThat(result.toList()).containsExactly(user2)

            result = User.filter { it.supervisor eq user1 }
            assertThat(result.toList()).containsExactly(user2)
        }
    }

}