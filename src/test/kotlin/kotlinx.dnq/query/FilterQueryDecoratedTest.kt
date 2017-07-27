package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterQueryDecoratedTest : DBTest() {

    @Test
    fun `searching by link property on same types`() {
        store.transactional {
            User.filter { it.supervisor?.login eq "test" }.let {
                assertEquals(1, it.size())
                assertEquals("test1".lookupUser(), it.first())
            }

            User.filter { it.supervisor?.login = "test" }.let {
                assertEquals(1, it.size())
                assertEquals("test1".lookupUser(), it.first())
            }
        }
    }

    @Test
    fun `searching by link property on different types`() {
        store.transactional {
            Contact.filter { it.user.login eq "test3" }.let {
                assertEquals(1, it.size())
                assertEquals("3@123.com".lookupContact(), it.first())
            }

            Contact.filter { it.user.login = "test3" }.let {
                assertEquals(1, it.size())
                assertEquals("3@123.com".lookupContact(), it.first())
            }
        }
    }

    @Test
    fun `searching by link property should works with AND`() {
        store.transactional {
            Contact.filter { (it.user.login eq "test3") and (it.email eq "1@123.com") }.let {
                assertTrue(it.isEmpty)
            }
            Contact.filter { (it.user.login eq "test3") and (it.email eq "3@123.com") }.let {
                assertEquals(1, it.size())
                assertEquals("3@123.com".lookupContact(), it.first())
            }
        }
    }

    @Test
    fun `searching by link property should works with OR`() {
        store.transactional {
            Contact.filter { (it.user.login eq "test3") or (it.email eq "1@123.com") }.let {
                assertEquals(2, it.size())
                assertTrue(it.contains("3@123.com".lookupContact()))
                assertTrue(it.contains("1@123.com".lookupContact()))
            }

            Contact.filter { (it.user.login eq "test3") or (it.user.login eq "test") }.let {
                assertEquals(2, it.size())
                assertTrue(it.contains("3@123.com".lookupContact()))
                assertTrue(it.contains("1@123.com".lookupContact()))
            }
        }
    }

    @Test
    fun `searching by link property should works with isIn`() {
        store.transactional {
            Contact.filter { it.user.login isIn listOf("test3", "test") }.let {
                assertEquals(2, it.size())
                assertTrue(it.contains("3@123.com".lookupContact()))
                assertTrue(it.contains("1@123.com".lookupContact()))
            }
        }
    }

    @Test
    fun `searching by link property should works with second level`() {
        store.transactional {
            Contact.filter { it.user.supervisor?.login eq "test" }.let {
                assertEquals(1, it.size())
                assertTrue(it.contains("2@123.com".lookupContact()))
            }
        }
    }

    @Test
    fun `searching by link property should works with link equation`() {
        store.transactional {
            val user = User.filter { it.login eq "test" }.first()
            Contact.filter { it.user.supervisor eq user }.let {
                assertEquals(1, it.size())
                assertTrue(it.contains("2@123.com".lookupContact()))
            }
        }
    }

    private fun String.lookupUser() = User.filter { it.login eq this }.first()
    private fun String.lookupContact() = Contact.filter { it.email eq this }.first()

    @Before
    fun fillDB() {
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
            val user3 = User.new {
                login = "test3"
                skill = 3
                supervisor = user2
            }

            Contact.new {
                user = user1
                email = "1@123.com"
            }
            Contact.new {
                user = user2
                email = "2@123.com"
            }

            Contact.new {
                user = user3
                email = "3@123.com"
            }
        }
    }
}