package kotlinx.dnq

import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.database.exceptions.NullPropertyException
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException
import jetbrains.exodus.database.exceptions.UniqueIndexViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.first
import kotlinx.dnq.query.query
import kotlinx.dnq.simple.regex
import kotlinx.dnq.simple.requireIf
import kotlinx.dnq.util.getOldValue
import kotlinx.dnq.util.hasChanges
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.joda.time.DateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PropertiesTest : DBTest() {
    abstract class Base : XdEntity() {
        companion object : XdNaturalEntityType<Base>()
        var enabled by xdBooleanProp()

        var requiredBaseProp by xdRequiredStringProp()
        var requiredIfBaseProp by xdStringProp { requireIf { enabled } }
        var regexBaseProp by xdStringProp { regex(Regex("good")) }
        var regexWrappedBaseProp by xdStringProp(trimmed = true) { regex(Regex("good")) }
    }

    class Derived(override val entity: Entity) : Base() {
        companion object : XdNaturalEntityType<Derived>()

        var requiredIfDerivedProp by xdStringProp { requireIf { enabled } }
        var regexDerivedProp by xdStringProp { regex(Regex("good")) }
        var regexWrappedDerivedProp by xdStringProp(trimmed = true) { regex(Regex("good")) }
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Derived)
    }

    @Test
    fun setAndGet() {
        val now = DateTime.now()
        store.transactional {
            User.new {
                login = "test"
                name = "Test Name"
                age = 25
                skill = 1
                salary = 100
                isGuest = true
                registered = now
            }
        }

        store.transactional {
            val user = User.query(User::login eq "test").first()

            assertEquals("test", user.login)
            assertEquals("Test Name", user.name)
            assertEquals(25, user.age)
            assertEquals(1, user.skill)
            assertEquals(100, user.salary)
            assertEquals(true, user.isGuest)
            assertEquals(now, user.registered)
        }
    }

    @Test
    fun trim() {
        val user = store.transactional {
            User.new {
                login = "  test  "
                name = "  test  "
                skill = 1
            }
        }

        store.transactional {
            assertEquals("test", user.login)
            assertEquals("  test  ", user.name)
            assertEquals(user.name, user.entity.getProperty("visibleName"))
        }
    }

    @Test
    fun required() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                User.new()
            }
        }
        assertEquals(2, e.causes.count())
        assertTrue {
            e.causes.any { it is NullPropertyException && it.propertyName == User::login.name }
        }
        assertTrue {
            e.causes.any { it is NullPropertyException && it.propertyName == User::skill.name }
        }
    }

    @Test
    fun required_text() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Image.new()
            }
        }
        assertEquals(1, e.causes.count())
        assertTrue {
            e.causes.any { it is NullPropertyException && it.propertyName == Image::content.name }
        }
    }

    @Test
    fun required_in_base() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Derived.new()
            }
        }
        assertEquals(1, e.causes.count())
        assertTrue {
            e.causes.any { it is NullPropertyException && it.propertyName == Derived::requiredBaseProp.name }
        }
    }

    @Test
    fun required_if() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Derived.new().apply {
                    enabled = true
                    requiredBaseProp = "test"
                }
            }
        }
        assertEquals(2, e.causes.count())
        assertTrue {
            e.causes.any { it is NullPropertyException && it.propertyName == Base::requiredIfBaseProp.name }
        }
        assertTrue {
            e.causes.any { it is NullPropertyException && it.propertyName == Derived::requiredIfDerivedProp.name }
        }
    }

    @Test
    fun constraints() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Derived.new().apply {
                    requiredBaseProp = "test"
                    regexBaseProp = "bad"
                    regexWrappedBaseProp = "bad"
                    regexDerivedProp = "bad"
                    regexWrappedDerivedProp = "bad"
                }
            }
        }
        assertEquals(4, e.causes.count())
        assertTrue {
            e.causes.any { it is SimplePropertyValidationException && it.propertyName == Base::regexBaseProp.name }
        }
        assertTrue {
            e.causes.any { it is SimplePropertyValidationException && it.propertyName == Base::regexWrappedBaseProp.name }
        }
        assertTrue {
            e.causes.any { it is SimplePropertyValidationException && it.propertyName == Derived::regexDerivedProp.name }
        }
        assertTrue {
            e.causes.any { it is SimplePropertyValidationException && it.propertyName == Derived::regexWrappedDerivedProp.name }
        }
    }

    @Test
    fun unique() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                User.new {
                    login = "test"
                    skill = 1
                }
                User.new {
                    login = "test"
                    skill = 1
                }
            }
        }
        assertEquals(1, e.causes.count())
        assertTrue {
            e.causes.any { it is UniqueIndexViolationException && it.propertyName == User::login.name }
        }
    }

    @Test
    fun `transient changes should be accessible`() {
        store.transactional {
            val boss = User.new {
                login = "boss"
                skill = 555
                hireDate = DateTime.now().minusHours(1)
            }
            val luckyGuy = User.new {
                login = "lucky"
                skill = 2
                hireDate = DateTime.now().minusHours(1)
            }
            val user = User.new {
                login = "user1"
                skill = 5
                supervisor = boss
                hireDate = DateTime.now().minusHours(1)
            }.apply {
                contacts.add(Contact.new { email = "some@mail.com" })
            }

            // has changes before save
            assertTrue(user.hasChanges(User::skill))
            assertTrue(user.hasChanges(User::supervisor))
            assertTrue(user.hasChanges(User::contacts))
            assertTrue(user.hasChanges(User::hireDate))
            assertFalse(user.hasChanges(User::registered))
            // old values are null
            assertThat(user.getOldValue(User::skill), nullValue())
            assertThat(user.getOldValue(User::supervisor), nullValue())
            assertThat(user.getOldValue(User::hireDate), nullValue())
            assertThat(user.getOldValue(User::registered), nullValue())

            it.flush()

            // a primitive property keeps track of old values
            assertFalse(user.hasChanges(User::skill))
            assertThat(user.getOldValue(User::skill), equalTo(5))
            user.skill = 6
            assertTrue(user.hasChanges(User::skill))
            assertThat(user.getOldValue(User::skill), equalTo(5))

            // a link property keeps track of old values until the flush
            assertFalse(user.hasChanges(User::supervisor))
            assertThat(user.getOldValue(User::supervisor), nullValue())
            user.supervisor = luckyGuy
            assertTrue(user.hasChanges(User::supervisor))
            assertThat(user.getOldValue(User::supervisor), equalTo(boss))

            // a DateTime property keeps track of old values
            val oldHireDate = user.hireDate
            val oldRegistered = user.registered
            assertFalse(user.hasChanges(User::hireDate))
            assertFalse(user.hasChanges(User::registered))
            assertThat(user.getOldValue(User::hireDate), equalTo(oldHireDate))
            assertThat(user.getOldValue(User::registered), equalTo(oldRegistered))
            user.hireDate = DateTime.now()
            user.registered = DateTime.now()
            assertTrue(user.hasChanges(User::hireDate))
            assertTrue(user.hasChanges(User::registered))
            assertThat(user.getOldValue(User::hireDate), equalTo(oldHireDate))
            assertThat(user.getOldValue(User::registered), equalTo(oldRegistered))

            // no changes for not affected properties
            assertFalse(user.hasChanges(User::login))
            assertFalse(user.hasChanges(User::contacts))

            it.flush()

            // a link property forgets about changes after the flush :(
            assertThat(user.getOldValue(User::supervisor), nullValue())
        }
    }

}