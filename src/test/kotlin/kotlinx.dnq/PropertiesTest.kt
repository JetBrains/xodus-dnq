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
import org.joda.time.DateTime
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

}