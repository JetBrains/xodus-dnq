/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.database.exceptions.NullPropertyException
import jetbrains.exodus.database.exceptions.SimplePropertyValidationException
import jetbrains.exodus.database.exceptions.UniqueIndexViolationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.*
import kotlinx.dnq.simple.email
import kotlinx.dnq.simple.regex
import kotlinx.dnq.simple.requireIf
import kotlinx.dnq.util.getOldValue
import kotlinx.dnq.util.hasChanges
import org.joda.time.DateTime
import org.junit.Test
import kotlin.test.assertFailsWith

var PropertiesTest.Employee.b by xdByteProp()

class PropertiesTest : DBTest() {
    abstract class Base(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Base>()

        var enabled by xdBooleanProp()

        var requiredBaseProp by xdRequiredStringProp()
        var requiredIfBaseProp by xdStringProp { requireIf { enabled } }
        var regexBaseProp by xdStringProp { regex(Regex("good")) }
        var regexWrappedBaseProp by xdStringProp(trimmed = true) { regex(Regex("good")) }
    }

    class Derived(entity: Entity) : Base(entity) {
        companion object : XdNaturalEntityType<Derived>()

        var requiredIfDerivedProp by xdStringProp { requireIf { enabled } }
        var regexDerivedProp by xdStringProp { regex(Regex("good")) }
        var regexWrappedDerivedProp by xdStringProp(trimmed = true) { regex(Regex("good")) }
    }

    class Employee(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Employee>()

        var login by xdRequiredStringProp(trimmed = true, unique = true)
        var skill by xdRequiredIntProp()
        var registered by xdDateTimeProp()
        val contacts by xdLink0_N(EmployeeContact::employee)
        var supervisor by xdLink0_1(Employee, "boss")
        var hireDate by xdRequiredDateTimeProp()
        var iq by xdByteProp()
    }

    class EmployeeContact(entity: Entity) : XdEntity(entity) {

        companion object : XdNaturalEntityType<EmployeeContact>()

        var employee: Employee by xdLink1(Employee::contacts)

        var email by xdRequiredStringProp() { email() }
    }


    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Derived, Employee, EmployeeContact)
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
            assertThat(user.login).isEqualTo("test")
            assertThat(user.name).isEqualTo("Test Name")
            assertThat(user.age).isEqualTo(25)
            assertThat(user.skill).isEqualTo(1)
            assertThat(user.salary).isEqualTo(100)
            assertThat(user.isGuest).isEqualTo(true)
            assertThat(user.registered).isEqualTo(now)
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
            assertThat(user.login).isEqualTo("test")
            assertThat(user.name).isEqualTo("  test  ")
            assertThat(user.entity.getProperty("visibleName")).isEqualTo(user.name)
        }
    }

    @Test
    fun required() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                User.new()
            }
        }
        assertThat(e.causes.filterIsInstance<NullPropertyException>().map { it.propertyName })
                .containsExactly(User::login.name, User::skill.name)
    }

    @Test
    fun required_text() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Image.new()
            }
        }
        assertThat(e.causes.filterIsInstance<NullPropertyException>().map { it.propertyName })
                .containsExactly(Image::content.name)
    }

    @Test
    fun required_in_base() {
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                Derived.new()
            }
        }
        assertThat(e.causes.filterIsInstance<NullPropertyException>().map { it.propertyName })
                .containsExactly(Derived::requiredBaseProp.name)
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
        assertThat(e.causes.filterIsInstance<NullPropertyException>().map { it.propertyName })
                .containsExactly(Base::requiredIfBaseProp.name, Derived::requiredIfDerivedProp.name)
    }

    @Test
    fun required_if_condition_changed() {
        val derived = store.transactional {
            Derived.new().apply {
                requiredBaseProp = "test"
            }
        }
        val e = assertFailsWith<ConstraintsValidationException> {
            store.transactional {
                derived.enabled = true
            }
        }

        assertThat(e.causes.filterIsInstance<NullPropertyException>().map { it.propertyName })
                .containsExactly(Base::requiredIfBaseProp.name, Derived::requiredIfDerivedProp.name)
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
        assertThat(e.causes.filterIsInstance<SimplePropertyValidationException>().map { it.propertyName })
                .containsExactly(
                        Base::regexBaseProp.name,
                        Base::regexWrappedBaseProp.name,
                        Derived::regexDerivedProp.name,
                        Derived::regexWrappedDerivedProp.name
                )
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
        assertThat(e.causes.filterIsInstance<UniqueIndexViolationException>().map { it.propertyName })
                .containsExactly(User::login.name)
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
                iq = 80
            }.apply {
                contacts.add(EmployeeContact.new { email = "some@mail.com" })
            }

            // has changes before save
            assertThat(user.hasChanges(Employee::skill)).isTrue()
            assertThat(user.hasChanges(Employee::supervisor)).isTrue()
            assertThat(user.hasChanges(Employee::contacts)).isTrue()
            assertThat(user.hasChanges(Employee::hireDate)).isTrue()
            assertThat(user.hasChanges(Employee::registered)).isFalse()
            assertThat(user.hasChanges(Employee::iq)).isTrue()
            // old values are null
            assertThat(user.getOldValue(Employee::skill)).isNull()
            assertThat(user.getOldValue(Employee::supervisor)).isNull()
            assertThat(user.getOldValue(Employee::hireDate)).isNull()
            assertThat(user.getOldValue(Employee::registered)).isNull()
            assertThat(user.getOldValue(Employee::iq)).isNull()

            it.flush()

            // a primitive property keeps track of old values
            assertThat(user.hasChanges(Employee::skill)).isFalse()
            assertThat(user.getOldValue(Employee::skill)).isEqualTo(5)
            assertThat(user.hasChanges(Employee::iq)).isFalse()
            assertThat(user.getOldValue(Employee::iq)).isEqualTo(80.toByte())
            user.skill = 6
            assertThat(user.hasChanges(Employee::skill)).isTrue()
            assertThat(user.getOldValue(Employee::skill)).isEqualTo(5)

            // a link property keeps track of old values until the flush
            assertThat(user.hasChanges(Employee::supervisor)).isFalse()
            assertThat(user.getOldValue(Employee::supervisor)).isEqualTo(boss)
            user.supervisor = luckyGuy
            assertThat(user.hasChanges(Employee::supervisor)).isTrue()
            assertThat(user.getOldValue(Employee::supervisor)).isEqualTo(boss)

            // a DateTime property keeps track of old values
            val oldHireDate = user.hireDate
            val oldRegistered = user.registered
            assertThat(user.hasChanges(Employee::hireDate)).isFalse()
            assertThat(user.hasChanges(Employee::registered)).isFalse()
            assertThat(user.getOldValue(Employee::hireDate)).isEqualTo(oldHireDate)
            assertThat(user.getOldValue(Employee::registered)).isEqualTo(oldRegistered)
            user.hireDate = DateTime.now()
            user.registered = DateTime.now()
            assertThat(user.hasChanges(Employee::hireDate)).isTrue()
            assertThat(user.hasChanges(Employee::registered)).isTrue()
            assertThat(user.getOldValue(Employee::hireDate)).isEqualTo(oldHireDate)
            assertThat(user.getOldValue(Employee::registered)).isEqualTo(oldRegistered)

            // no changes for not affected properties
            assertThat(user.hasChanges(Employee::login)).isFalse()
            assertThat(user.hasChanges(Employee::contacts)).isFalse()

            it.flush()

            // a link property forgets about changes after the flush :(
            assertThat(user.getOldValue(Employee::supervisor)).isEqualTo(luckyGuy)
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
            assertWithMessage("exception message").that(e.message)
                    .matches(pattern)
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
            assertThat(User.all().mapDistinct(User::supervisor).toList())
                    .containsExactly(boss)
        }

    }

}
