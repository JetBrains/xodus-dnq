package kotlinx.dnq

import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import org.junit.Test
import kotlin.test.assertNotNull

class CreateTest : DBTest() {

    @Test
    fun create() {
        val login = "mazine"

        store.transactional { txn ->
            User.new {
                this.login = login
                this.skill = 1
            }
        }

        store.transactional {
            assertNotNull(User.query(User::login eq login).firstOrNull())
        }
    }
}