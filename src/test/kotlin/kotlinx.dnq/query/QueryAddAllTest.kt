package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test
import kotlin.test.assertTrue

class QueryAddAllTest : DBTest() {

    @Test
    fun `addAll(Sequence) should add all elements`() {
        assertAddAll { users ->
            this.users.addAll(users)
        }
    }

    @Test
    fun `addAll(Iterable) should add all elements`() {
        assertAddAll { users ->
            this.users.addAll(users.asIterable())
        }
    }

    @Test
    fun `addAll(XdQuery) should add all elements`() {
        assertAddAll { users ->
            this.users.addAll(users.asIterable().map { it.entity }.asQuery(User))
        }
    }

    fun assertAddAll(addAll: Group.(Sequence<User>) -> Unit) {
        val users = store.transactional {
            sequenceOf(
                    User.new { login = "1"; skill = 1 },
                    User.new { login = "2"; skill = 3 }
            )
        }

        val group = store.transactional { RootGroup.new { name = "group" } }

        store.transactional {
            group.addAll(users)
        }

        store.transactional {
            assertTrue(group.users.asSequence().any { it.login == "1" })
            assertTrue(group.users.asSequence().any { it.login == "2" })
        }
    }
}