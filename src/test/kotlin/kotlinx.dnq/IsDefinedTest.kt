package kotlinx.dnq

import kotlinx.dnq.query.first
import kotlinx.dnq.util.isDefined
import org.joda.time.DateTime
import org.junit.Test
import kotlin.test.assertEquals

class IsDefinedTest : DBTest() {
    @Test
    fun `isDefined should return false for undefined optional properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertEquals(false, user.isDefined(User::name))
            assertEquals(false, user.isDefined(User::isGuest))
            assertEquals(false, user.isDefined(User::isMale))
            assertEquals(false, user.isDefined(User::registered))
        }
    }

    @Test
    fun `isDefined should return true for defined optional properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                name = "Schepotev"
                registered = DateTime.now()
                isGuest = false
                isMale = true
            }
            assertEquals(true, user.isDefined(User::name))
            assertEquals(true, user.isDefined(User::isGuest))
            assertEquals(true, user.isDefined(User::isMale))
            assertEquals(true, user.isDefined(User::registered))
        }
    }

    @Test
    fun `isDefined should return false for undefined required properties`() {
        store.transactional {
            val user = User.new()
            assertEquals(false, user.isDefined(User::login))
            assertEquals(false, user.isDefined(User::skill))
            user.delete()
        }
    }

    @Test
    fun `isDefined should return true for defined required properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertEquals(true, user.isDefined(User::login))
            assertEquals(true, user.isDefined(User::skill))
        }
    }

    @Test
    fun `isDefined should return false for undefined optional link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertEquals(false, user.isDefined(User::supervisor))
        }
    }

    @Test
    fun `isDefined should return true for defined optional link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                supervisor = User.new {
                    login = "pegov"
                    skill = 42
                }
            }
            assertEquals(true, user.isDefined(User::supervisor))
        }
    }


    @Test
    fun `isDefined should return false for undefined required link`() {
        store.transactional {
            val contact = Contact.new()
            assertEquals(false, contact.isDefined(Contact::user))
            contact.delete()
        }
    }

    @Test
    fun `isDefined should return true for defined required link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                contacts.add(Contact.new { email = "zeckson@spb.com" })
            }
            assertEquals(true, user.contacts.first().isDefined(Contact::user))
        }
    }


}