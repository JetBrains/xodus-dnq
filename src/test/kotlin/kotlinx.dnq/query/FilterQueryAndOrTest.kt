package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Before
import org.junit.Test

class FilterQueryAndOrTest : DBTest() {

    @Before
    fun createDB() {
        store.transactional {
            User.new {
                login = "pedro"
                skill = 1
                age = 10
                isMale = true
            }
            User.new {
                login = "pablo"
                skill = 2
                age = 20
                isMale = true
            }
            User.new {
                login = "angela"
                skill = 3
                age = 30
                isMale = false
            }
            User.new {
                login = "susanna"
                skill = 4
                age = 40
                isMale = false
            }
        }
    }

    @Test
    fun `should search by or`() {
        store.transactional {
            val users = User.filter {
                (it.skill eq 1) or (it.age eq 30)
            }
            assertThat(users.toList()).hasSize(2)
            assertThat(users.toList().map { it.skill }).contains(1)
            assertThat(users.toList().map { it.age }).contains(30)
        }
    }

    @Test
    fun `should search by and`() {
        store.transactional {
            var users = User.filter {
                (it.skill eq 1) and (it.age eq 20)
            }
            assertThat(users.toList()).isEmpty()

            users = User.filter {
                (it.skill eq 1) and (it.age eq 10)
            }
            assertThat(users.toList().map { it.skill }).contains(1)
        }
    }

    @Test
    fun `should search by number of or`() {
        store.transactional {
            val users = User.filter {
                (it.skill eq 1) or (it.skill eq 2) or (it.skill eq 3)
            }
            assertThat(users.toList().map { it.skill })
                    .containsExactly(1, 2, 3)
        }
    }

    @Test
    fun `should search by number combination of or + and`() {
        store.transactional {
            val users = User.filter {
                (it.isMale eq true) and ((it.skill eq 2) or (it.skill eq 3))
            }
            assertThat(users.toList().map { it.skill })
                    .containsExactly(2)
        }
    }

    @Test
    fun `should search by number combination of or with another condition`() {
        store.transactional {
            val users = User.filter {
                ((it.skill eq 2) or (it.skill eq 3))
                (it.isMale eq true)
            }
            assertThat(users.toList().map { it.skill })
        }
    }
}