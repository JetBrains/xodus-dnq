package kotlinx.dnq.query

import com.google.common.truth.Truth
import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test

class QueryRemoveAllTest : DBTest() {

    @Test
    fun `removeAll(Sequence) should add all elements`() {
        assertRemoveAll { users ->
            this.users.removeAll(users)
        }
    }

    @Test
    fun `removeAll(Iterable) should add all elements`() {
        assertRemoveAll { users ->
            this.users.removeAll(users.asIterable())
        }
    }

    @Test
    fun `removeAll(XdQuery) should add all elements`() {
        assertRemoveAll { users ->
            this.users.removeAll(users.asIterable().map { it.entity }.asQuery(User))
        }
    }

    fun assertRemoveAll(removeAll: Group.(Sequence<User>) -> Unit) {
        val users = store.transactional {
            sequenceOf(
                    User.new { login = "1"; skill = 1 },
                    User.new { login = "2"; skill = 3 }
            )
        }

        val group = store.transactional {
            RootGroup.new {
                name = "group"
                users.forEach {
                    this.users.add(it)
                }
                this.users.add(User.new { login = "3"; skill = 5 })
            }
        }

        store.transactional {
            group.removeAll(users)
        }

        store.transactional {
            Truth.assertThat(group.users.toList().map { it.login })
                    .containsExactly("3")
        }
    }
}