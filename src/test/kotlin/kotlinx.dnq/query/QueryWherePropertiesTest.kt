package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueryWherePropertiesTest : DBTest() {

    @Test
    fun `firstOrNull should return null if nothing found`() {
        store.transactional {
            assertNull(User.filter {}.firstOrNull())
        }
    }

    @Test
    fun `simple search should work`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            assertEquals(user1.entityId, User.filter { it.login = "test" }.first().entityId)
            assertEquals(user2.entityId, User.filter { it.login = "test1" }.first().entityId)

            assertNull(User.filter { it.skill = 0 }.firstOrNull())

            assertEquals(user1.entityId, User.filter { it.skill = 1 }.first().entityId)
            assertEquals(1, User.filter { it.skill = 1 }.size())

            assertEquals(user2.entityId, User.filter { it.skill = 2 }.first().entityId)
            assertEquals(1, User.filter { it.skill = 2 }.size())
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

            val users1 = User.filter { it.login = "test" }
            assertEquals(1, users1.size())
            assertEquals(user1.entityId, users1.first().entityId)

            val users2 = User.filter { it.skill = 2 }
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

            val users = User.filter { it.name = null }
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

            var users = User.filter { it.skill lt 2 }
            assertEquals(1, users.size())
            assertEquals(user1.entityId, users.first().entityId)

            users = User.filter { it.skill gt 1 }
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

            val users = User.filter { it.skill ne 2 }
            assertEquals(2, users.size())
            val sequence = users.asSequence()
            assertNotNull(sequence.first { it.entityId == user1.entityId })
            assertNotNull(sequence.first { it.entityId == user3.entityId })
        }
    }

    @Test
    fun `should apply multiple causes`() {
        store.transactional {
            val user1 = User.new {
                login = "test1"
                name = "test"
                skill = 1
            }
            User.new {
                login = "test2"
                name = "test"
                skill = 2
            }
            User.new {
                login = "test3"
                name = "test"
                skill = 2
            }

            val users = User.filter {
                it.name = "test"
                it.skill ne 2
            }
            assertEquals(1, users.size())
            assertEquals(user1, users.first())
        }
    }

    @Test
    fun `should search by between`() {
        store.transactional {
            val user1 = User.new {
                login = "test1"
                skill = 1
            }
            val user2 = User.new {
                login = "test2"
                skill = 2
            }
            User.new {
                login = "test3"
                skill = 7
            }

            val users = User.filter {
                it.skill between (1 to 3)
            }
            assertEquals(2, users.size())
            val sequence = users.asSequence()
            assertNotNull(sequence.first { it.entityId == user1.entityId })
            assertNotNull(sequence.first { it.entityId == user2.entityId })
        }
    }

    @Test
    fun `should search by required fields`() {
        store.transactional {
            val user1 = User.new {
                login = "test1"
                skill = 1
            }
            val users1 = User.filter { it.skill ne 0 }
            assertEquals(1, users1.size())
            assertEquals(user1.entityId, users1.first().entityId)

            val users2 = User.filter { it.login ne "" }
            assertEquals(1, users2.size())
            assertEquals(user1.entityId, users2.first().entityId)
        }
    }
}
