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

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.anyStartsWith
import kotlinx.dnq.query.contains
import kotlinx.dnq.query.query
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.hasChanges
import kotlinx.dnq.util.isDefined
import org.junit.Ignore
import org.junit.Test

class MutableSetPropertyTest : DBTest() {


    class Employee(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Employee>()

        val skills by xdMutableSetProp<Employee, String>()
    }

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Employee)
    }

    @Test
    fun `empty list by default`() {
        val employee = store.transactional {
            Employee.new()
        }

        store.transactional {
            assertThat(employee.skills).isEmpty()
        }
    }

    @Test
    fun `set and get`() {
        val employee = store.transactional {
            Employee.new { skills.addAll(listOf("Java", "Kotlin", "Xodus-DNQ")) }
        }

        store.transactional {
            assertThat(employee.skills)
                    .containsExactly("Java", "Kotlin", "Xodus-DNQ")
        }
    }

    @Test
    fun `is defined`() {
        val employee = store.transactional {
            Employee.new { skills.addAll(listOf("Java", "Kotlin", "Xodus-DNQ")) }
        }

        store.transactional {
            assertThat(employee.isDefined(Employee::skills)).isTrue()
        }
    }

    @Test
    fun `is not defined`() {
        val employee = store.transactional {
            Employee.new()
        }

        store.transactional {
            assertThat(employee.isDefined(Employee::skills)).isFalse()
        }
    }

    @Test
    fun `contains query`() {
        val employee = store.transactional {
            Employee.new { skills.addAll(listOf("Java", "Kotlin", "Xodus-DNQ")) }
        }

        store.transactional {
            assertThat(Employee.query(Employee::skills contains "Kotlin").toList())
                    .containsExactly(employee)

            assertThat(Employee.query(Employee::skills contains "Scala").toList())
                    .isEmpty()
        }
    }

    @Test
    @Ignore
    fun `anyStartsWith query`() {
        val employee = store.transactional {
            Employee.new { skills.addAll(listOf("Java", "Kotlin", "Xodus-DNQ")) }
        }

        store.transactional {
            assertThat(Employee.query(Employee::skills anyStartsWith "Kot").toList())
                    .containsExactly(employee)

            assertThat(Employee.query(Employee::skills anyStartsWith "Sc").toList())
                    .isEmpty()
        }
    }

    private fun createEmployee(vararg skills: String): Employee {
        return transactional {
            Employee.new { this.skills.addAll(skills) }
        }
    }

    private fun Employee.updateSkills(expectModification: Boolean = true, operation: MutableSet<String>.() -> Unit) = apply {
        transactional {
            this.skills.operation()
            assertWithMessage("skills are updated").that(this.hasChanges(Employee::skills))
                    .isEqualTo(expectModification)
        }
    }

    private fun Employee.assertThatSkills(): IterableSubject {
        return transactional {
            assertThat(skills.toList())
        }
    }

    @Test
    fun `add element to non empty set`() {
        createEmployee("Java")
                .updateSkills { add("Kotlin") }
                .assertThatSkills()
                .containsExactly("Java", "Kotlin")
    }

    @Test
    fun `add element to empty set`() {
        createEmployee()
                .updateSkills { add("Kotlin") }
                .assertThatSkills()
                .containsExactly("Kotlin")
    }

    @Test
    fun `add existing element`() {
        createEmployee("Java")
                .updateSkills(expectModification = false) { add("Java") }
                .assertThatSkills()
                .containsExactly("Java")
    }

    @Test
    fun `remove last element from non empty set`() {
        createEmployee("Java")
                .updateSkills { remove("Java") }
                .assertThatSkills()
                .isEmpty()
    }

    @Test
    fun `remove element from non empty set`() {
        createEmployee("Java", "Kotlin")
                .updateSkills { remove("Java") }
                .assertThatSkills()
                .containsExactly("Kotlin")
    }

    @Test
    fun `remove element from empty set`() {
        createEmployee()
                .updateSkills(expectModification = false) { remove("Java") }
                .assertThatSkills()
                .isEmpty()
    }

    @Test
    fun `remove non-existing element`() {
        createEmployee("Java")
                .updateSkills(expectModification = false) { remove("Kotlin") }
                .assertThatSkills()
                .containsExactly("Java")
    }

    @Test
    fun `clear empty`() {
        createEmployee()
                .updateSkills(expectModification = false) { clear() }
                .assertThatSkills()
                .isEmpty()
    }

    @Test
    fun `clear non empty`() {
        createEmployee("Kotlin", "Java")
                .updateSkills { clear() }
                .assertThatSkills()
                .isEmpty()
    }

}
