/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.*
import kotlinx.dnq.util.isDefined
import org.junit.Test

class SetPropertyTest : DBTest() {


    class Employee(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Employee>()

        var skills by xdSetProp<Employee, String>()
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
            Employee.new { skills = setOf("Java", "Kotlin", "Xodus-DNQ") }
        }

        store.transactional {
            assertThat(employee.skills)
                    .containsExactly("Java", "Kotlin", "Xodus-DNQ")
        }
    }

    @Test
    fun `empty list`() {
        val employee = store.transactional {
            Employee.new { skills = setOf("Java", "Kotlin", "Xodus-DNQ") }
        }

        store.transactional {
            employee.skills = emptySet()
        }

        store.transactional {
            assertThat(employee.skills).isEmpty()
        }
    }


    @Test
    fun `is defined`() {
        val employee = store.transactional {
            Employee.new { skills = setOf("Java", "Kotlin", "Xodus-DNQ") }
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
            Employee.new { skills = setOf("Java", "Kotlin", "Xodus-DNQ") }
        }

        store.transactional {
            assertThat(Employee.query(Employee::skills contains "Kotlin").toList())
                    .containsExactly(employee)

            assertThat(Employee.query(Employee::skills contains "Scala").toList())
                    .isEmpty()
        }
    }

    @Test
    fun `anyStartsWith query`() {
        val employee = store.transactional {
            Employee.new { skills = setOf("Java", "Kotlin", "Xodus-DNQ") }
        }

        store.transactional {
            assertThat(Employee.query(Employee::skills anyStartsWith "Kot").toList())
                    .containsExactly(employee)

            assertThat(Employee.query(Employee::skills anyStartsWith "Sc").toList())
                    .isEmpty()
        }
    }
}