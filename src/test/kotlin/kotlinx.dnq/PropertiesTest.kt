package kotlinx.dnq

import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.database.exceptions.NullPropertyException
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException
import jetbrains.exodus.database.exceptions.UniqueIndexViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.*
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

    class Employee(override val entity: Entity) : XdEntity() {
        companion object : XdNaturalEntityType<Employee>()

        var login by xdRequiredStringProp(trimmed = true, unique = true)
        var skill by xdRequiredIntProp()
        var registered by xdDateTimeProp()
        val contacts by xdLink0_N(Contact::user)
        var supervisor by xdLink0_1(Employee, "boss")
        var hireDate by xdRequiredDateTimeProp()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNode(Derived)
        XdModel.registerNode(Employee)
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
            val boss = Employee.new {
                login = "boss"
                skill = 555
                hireDate = DateTime.now().minusHours(1)
            }
            val luckyGuy = Employee.new {
                login = "lucky"
                skill = 2
                hireDate = DateTime.now().minusHours(1)
            }
            val user = Employee.new {
                login = "user1"
                skill = 5
                supervisor = boss
                hireDate = DateTime.now().minusHours(1)
            }.apply {
                contacts.add(Contact.new { email = "some@mail.com" })
            }

            // has changes before save
            assertTrue(user.hasChanges(Employee::skill))
            assertTrue(user.hasChanges(Employee::supervisor))
            assertTrue(user.hasChanges(Employee::contacts))
            assertTrue(user.hasChanges(Employee::hireDate))
            assertFalse(user.hasChanges(Employee::registered))
            // old values are null
            assertThat(user.getOldValue(Employee::skill), nullValue())
            assertThat(user.getOldValue(Employee::supervisor), nullValue())
            assertThat(user.getOldValue(Employee::hireDate), nullValue())
            assertThat(user.getOldValue(Employee::registered), nullValue())

            it.flush()

            // a primitive property keeps track of old values
            assertFalse(user.hasChanges(Employee::skill))
            assertThat(user.getOldValue(Employee::skill), equalTo(5))
            user.skill = 6
            assertTrue(user.hasChanges(Employee::skill))
            assertThat(user.getOldValue(Employee::skill), equalTo(5))

            // a link property keeps track of old values until the flush
            assertFalse(user.hasChanges(Employee::supervisor))
            assertThat(user.getOldValue(Employee::supervisor), nullValue())
            user.supervisor = luckyGuy
            assertTrue(user.hasChanges(Employee::supervisor))
            assertThat(user.getOldValue(Employee::supervisor), equalTo(boss))

            // a DateTime property keeps track of old values
            val oldHireDate = user.hireDate
            val oldRegistered = user.registered
            assertFalse(user.hasChanges(Employee::hireDate))
            assertFalse(user.hasChanges(Employee::registered))
            assertThat(user.getOldValue(Employee::hireDate), equalTo(oldHireDate))
            assertThat(user.getOldValue(Employee::registered), equalTo(oldRegistered))
            user.hireDate = DateTime.now()
            user.registered = DateTime.now()
            assertTrue(user.hasChanges(Employee::hireDate))
            assertTrue(user.hasChanges(Employee::registered))
            assertThat(user.getOldValue(Employee::hireDate), equalTo(oldHireDate))
            assertThat(user.getOldValue(Employee::registered), equalTo(oldRegistered))

            // no changes for not affected properties
            assertFalse(user.hasChanges(Employee::login))
            assertFalse(user.hasChanges(Employee::contacts))

            it.flush()

            // a link property forgets about changes after the flush :(
            assertThat(user.getOldValue(Employee::supervisor), nullValue())
        }
    }

    @Test
    fun `access to not initialized required property should throw an exception`() {
        store.transactional { txn ->
            val user = User.new()
            val e = assertFailsWith<RequiredPropertyUndefinedException> {
                user.login
            }
            val pattern = "Required field login of User\\[\\d+-\\d+] is undefined"
            assertTrue(e.message!!.matches(Regex(pattern)), "Exception message \"${e.message}\" does not match pattern \"$pattern\"")
            txn.revert()
        }
    }

    @Test
    fun `query of nullable property should not throw on materialization`() {
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
        store.transactional {
            User.all().mapDistinct(User::supervisor).asNullSequence().forEach {
                println(it?.login ?: "null")
            }
        }

    }

}