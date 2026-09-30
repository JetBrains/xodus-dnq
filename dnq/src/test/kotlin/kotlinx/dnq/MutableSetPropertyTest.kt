/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.contains
import kotlinx.dnq.query.query
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.hasChanges
import kotlinx.dnq.util.isDefined
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class MutableSetPropertyTest : DBTest() {


    class Employee(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Employee>()

        val skills by xdMutableSetProp<Employee, String>()
        val levels by xdMutableSetProp<Employee, Int>()
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
    fun `committed string set membership ignores case and preserves stored spelling`() {
        val (mixed, lower) = transactional {
            val mixed = Employee.new { skills.add("MiXeD@Example") }
            val lower = Employee.new { skills.add("mixed@example") }
            Employee.new { skills.add("prefix-MiXeD@Example") }
            Employee.new()
            mixed to lower
        }

        transactional {
            assertCaseInsensitiveStringMembership(mixed, lower)
            assertThat(mixed.skills).containsExactly("MiXeD@Example")
            assertThat(lower.skills).containsExactly("mixed@example")
        }
    }

    @Test
    fun `string set membership ignores case before flushing new entities`() {
        transactional {
            val mixed = Employee.new { skills.add("MiXeD@Example") }
            val lower = Employee.new { skills.add("mixed@example") }
            Employee.new { skills.add("prefix-MiXeD@Example") }
            Employee.new()

            assertThat((mixed.entity as TransientEntity).isNew).isTrue()
            assertCaseInsensitiveStringMembership(mixed, lower)
        }
    }

    private fun assertCaseInsensitiveStringMembership(mixed: Employee, lower: Employee) {
        for (value in listOf("MiXeD@Example", "mixed@example", "MIXED@EXAMPLE")) {
            assertThat(Employee.query(Employee::skills contains value).toList())
                .containsExactly(mixed, lower)
        }
        assertThat(Employee.query(Employee::skills contains "not-present").toList()).isEmpty()
        assertThat(Employee.query(Employee::skills contains "mixed").toList()).isEmpty()
    }

    @Test
    fun `non string set membership retains exact element matching`() {
        val (match, other) = transactional {
            Employee.new { levels.add(7) } to Employee.new { levels.add(8) }
        }
        transactional {
            assertThat(Employee.query(Employee::levels contains 7).toList()).containsExactly(match)
            assertThat(Employee.query(Employee::levels contains 8).toList()).containsExactly(other)
            assertThat(Employee.query(Employee::levels contains 9).toList()).isEmpty()

            val fresh = Employee.new { levels.add(9) }
            assertThat(Employee.query(Employee::levels contains 9).toList()).containsExactly(fresh)
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
    fun `iterator removal is tracked and committed`() {
        createEmployee("Java", "Kotlin")
                .updateSkills {
                    val iterator = iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next() == "Java") iterator.remove()
                    }
                    assertThat(this).containsExactly("Kotlin")
                }
                .assertThatSkills()
                .containsExactly("Kotlin")
    }

    @Test
    fun `iterator can continue removing through the last element`() {
        createEmployee("Java", "Kotlin", "Scala")
                .updateSkills {
                    val visited = mutableSetOf<String>()
                    val iterator = iterator()
                    while (iterator.hasNext()) {
                        visited.add(iterator.next())
                        iterator.remove()
                    }
                    assertThat(visited).containsExactly("Java", "Kotlin", "Scala")
                    assertThat(this).isEmpty()
                    assertFailsWith<NoSuchElementException> { iterator.next() }
                }
                .assertThatSkills()
                .isEmpty()
    }

    @Test
    fun `iterator rejects removal without an unremoved current element`() {
        val employee = createEmployee("Java")
        transactional {
            val iterator = employee.skills.iterator()
            assertFailsWith<IllegalStateException> { iterator.remove() }
            assertThat(employee.hasChanges(Employee::skills)).isFalse()
            assertThat(iterator.next()).isEqualTo("Java")
            iterator.remove()
            assertFailsWith<IllegalStateException> { iterator.remove() }
            assertThat(employee.hasChanges(Employee::skills)).isTrue()
        }
        employee.assertThatSkills().isEmpty()
    }

    @Test
    fun `empty iterator does not define or change the property`() {
        val employee = transactional { Employee.new() }
        transactional {
            val iterator = employee.skills.iterator()
            assertThat(iterator.hasNext()).isFalse()
            assertFailsWith<NoSuchElementException> { iterator.next() }
            assertFailsWith<IllegalStateException> { iterator.remove() }
            assertThat(employee.hasChanges(Employee::skills)).isFalse()
            assertThat(employee.isDefined(Employee::skills)).isFalse()
        }
        transactional {
            assertThat(employee.skills).isEmpty()
            assertThat(employee.isDefined(Employee::skills)).isFalse()
        }
    }

    @Test
    fun `removeIf tracks and commits iterator removals`() {
        createEmployee("Java", "Kotlin", "Scala")
                .updateSkills {
                    assertThat(removeIf { it != "Kotlin" }).isTrue()
                }
                .assertThatSkills()
                .containsExactly("Kotlin")
    }

    @Test
    fun `iterator removal survives MVCC replay`() {
        assertRemovalSurvivesReplay { skills ->
            val iterator = skills.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() == "Java") iterator.remove()
            }
        }
    }

    @Test
    fun `removeIf removals survive MVCC replay`() {
        assertRemovalSurvivesReplay { skills ->
            assertThat(skills.removeIf { it == "Java" }).isTrue()
        }
    }

    private fun assertRemovalSurvivesReplay(remove: (MutableSet<String>) -> Unit) {
        val employee = createEmployee("Java", "Kotlin")
        val counter = transactional { User.new { login = "replay-counter"; skill = 0 } }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val tracked = transactional {
                counter.skill = 1
                remove(employee.skills)
                assertThat(employee.skills).containsExactly("Kotlin")
                val tracked = employee.hasChanges(Employee::skills)
                // The competing commit completes before the losing transaction can flush.
                executor.submit {
                    transactional { counter.skill = 2 }
                }.get(30, TimeUnit.SECONDS)
                tracked
            }
            transactional {
                // Both writes target the same record: the loser's commit must have replayed.
                assertThat(counter.skill).isEqualTo(1)
                assertThat(employee.skills).containsExactly("Kotlin")
            }
            assertThat(tracked).isTrue()
        } finally {
            executor.shutdownNow()
        }
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

    @Test
    fun `old value for mutableSetProperty should work`(){
        val oldSetValue = hashSetOf("Java", "Kotlin", "Xodus-DNQ")
        val employee = store.transactional {
            Employee.new {
                skills.addAll(oldSetValue)
            }
        }
        store.transactional {
            employee.skills.clear()
            employee.skills.add("How to write supportable tests")
            // Fix compilation
            val oldValue = (employee.entity as TransientEntity).getPropertyOldValue("skills") as Set<*>
            Assert.assertTrue(oldValue.containsAll(oldSetValue))
        }
    }


}
