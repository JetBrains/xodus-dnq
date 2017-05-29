package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test


class MapDistinctTest : DBTest() {

    @Test
    fun `mapDistinct should work with xdLink*`() {
        store.transactional {
            User.all().mapDistinct(User::supervisor).let {
                assertEquals(2, it.size())
                assertEquals(1, it.asSequence().count())
            }
            User.all().mapDistinct { it.supervisor }.let {
                assertEquals(2, it.size())
                assertEquals(1, it.asSequence().count())
            }
        }
    }

    @Test
    fun `flatMapDistinct should work with xdLink*`() {
        store.transactional {
            User.all().flatMapDistinct(User::contacts).let {
                assertEquals(3, it.size())
                assertEquals(2, it.asSequence().count())
            }
            User.all().flatMapDistinct { it.contacts }.let {
                assertEquals(3, it.size())
                assertEquals(2, it.asSequence().count())
            }
        }
    }

    @Before
    fun initStructure() {
        val boss = store.transactional {
            User.new {
                login = "boss"
                skill = 1
            }
        }
        store.transactional {
            User.new {
                login = "slave"
                supervisor = boss
                skill = 1
            }
        }

        val anotherGuy = store.transactional {
            User.new {
                login = "anotherGuy"
                skill = 1
            }
        }

        store.transactional {
            Contact.new {
                email = "boss@123.org"
                user = boss
            }
            Contact.new {
                email = "anotherGuy@123.org"
                user = anotherGuy
            }
        }
    }
}