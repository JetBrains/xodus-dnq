package kotlinx.dnq

import com.google.common.truth.Truth
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import org.junit.Test

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
            Truth.assertThat(User.query(User::login eq login).firstOrNull())
                    .isNotNull()
        }
    }
}