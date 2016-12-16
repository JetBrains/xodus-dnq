package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueryWhereTest : DBTest() {

    @Test
    fun `firstOrNull should return null if nothing found`() {
        store.transactional {
            assertNull(User.where {}.firstOrNull())
        }
    }

    @Test
    fun `firstOrNull should return entity if something is there`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            assertEquals(user1.entityId, User.where { login = "test" }.first().entityId)
            assertEquals(user2.entityId, User.where { login = "test1" }.first().entityId)

            assertNull(User.where { skill = 0 }.firstOrNull())

            assertEquals(user1.entityId, User.where { skill = 1 }.first().entityId)
            assertEquals(1, User.where { skill = 1 }.size())

            assertEquals(user2.entityId, User.where { skill = 2 }.first().entityId)
            assertEquals(1, User.where { skill = 2 }.size())
        }
    }

    @Test
    fun `should filter by property`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            val users1 = User.where { login = "test" }
            assertEquals(1, users1.size())
            assertEquals(user1.entityId, users1.first().entityId)

            val users2 = User.where { skill = 2 }
            assertEquals(user2.entityId, users2.first().entityId)
            assertEquals(1, users2.size())
        }
    }

    @Test
    fun `should found by null property`() {
        store.transactional {
            User.new {
                login = "test"
                name = "some"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            val users = User.where { name = null }
            assertEquals(1, users.size())
            assertEquals(user2.entityId, users.first().entityId)
        }
    }

    @Test
    fun `should found by less and greater property`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            var users = User.where { skill less 2 }
            assertEquals(1, users.size())
            assertEquals(user1.entityId, users.first().entityId)

            users = User.where { skill greater 1 }
            assertEquals(1, users.size())
            assertEquals(user2.entityId, users.first().entityId)
        }
    }

    @Test
    fun `should found by not value property`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            User.new {
                login = "test1"
                skill = 2
            }
            val user3 = User.new {
                login = "test2"
                skill = 3
            }

            val users = User.where { skill not 2 }
            assertEquals(2, users.size())
            val sequence = users.asSequence()
            assertNotNull(sequence.first { it.entityId == user1.entityId })
            assertNotNull(sequence.first { it.entityId == user3.entityId })
        }
    }

}