package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FilterQueryAndOrTest : DBTest() {

    @Test
    fun `should search by or`() {
        store.transactional {
            val users = User.filter {
                (it.skill eq 1) or (it.age eq 30)
            }
            assertEquals(2, users.size())
            val sequence = users.asSequence()
            assertNotNull(sequence.first { it.skill == 1 })
            assertNotNull(sequence.first { it.age == 30 })
        }
    }

    @Test
    fun `should search by and`() {
        store.transactional {
            var users = User.filter {
                (it.skill eq 1) and (it.age eq 20)
            }
            assertEquals(0, users.size())

            users = User.filter {
                (it.skill eq 1) and (it.age eq 10)
            }
            val sequence = users.asSequence()
            assertEquals(1, users.size())
            assertNotNull(sequence.first { it.skill == 1 })
        }
    }

    @Test
    fun `should search by number of or`() {
        store.transactional {
            val users = User.filter {
                (it.skill eq 1) or (it.skill eq 2) or (it.skill eq 3)
            }
            val sequence = users.asSequence()
            assertEquals(3, users.size())
            assertNotNull(sequence.first { it.skill == 1 })
            assertNotNull(sequence.first { it.skill == 2 })
            assertNotNull(sequence.first { it.skill == 3 })
        }
    }

    @Test
    fun `should search by number combination of or + and`() {
        store.transactional {
            val users = User.filter {
                (it.isMale eq true) and ((it.skill eq 2) or (it.skill eq 3))
            }
            val sequence = users.asSequence()
            assertEquals(1, users.size())
            assertNotNull(sequence.first { it.skill == 2 })
        }
    }

    @Test
    fun `should search by number combination of or with another condition`() {
        store.transactional {
            val users = User.filter {
                ((it.skill eq 2) or (it.skill eq 3))
                (it.isMale eq true)
            }
            val sequence = users.asSequence()
            assertEquals(1, users.size())
            assertNotNull(sequence.first { it.skill == 2 })
        }
    }


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

}