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
package kotlinx.dnq.java.time

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.ne
import kotlinx.dnq.query.query
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.isDefined
import org.junit.Before
import org.junit.Test
import java.time.Instant

class InstantPropertyTest : DBTest() {

    class Employee(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Employee>()

        var hireMoment by xdInstantProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Employee)
    }

    @Before
    fun initPropertyValueSerializer() {
        InstantSerializer.register(store)
    }

    @Test
    fun `null by default`() {
        val employee = store.transactional {
            Employee.new()
        }

        store.transactional {
            assertThat(employee.hireMoment).isNull()
        }
    }

    @Test
    fun `set and get`() {
        val now = Instant.now()

        val employee = store.transactional {
            Employee.new { hireMoment = now }
        }

        store.transactional {
            assertThat(employee.hireMoment)
                    .isEqualTo(now)
        }
    }

    @Test
    fun `null value`() {
        val employee = store.transactional {
            Employee.new { hireMoment = Instant.now() }
        }

        store.transactional {
            employee.hireMoment = null
        }

        store.transactional {
            assertThat(employee.hireMoment).isNull()
        }
    }


    @Test
    fun `is defined`() {
        val employee = store.transactional {
            Employee.new { hireMoment = Instant.now() }
        }

        store.transactional {
            assertThat(employee.isDefined(Employee::hireMoment)).isTrue()
        }
    }

    @Test
    fun `is not defined`() {
        val employee = store.transactional {
            Employee.new()
        }

        store.transactional {
            assertThat(employee.isDefined(Employee::hireMoment)).isFalse()
        }
    }

    @Test
    fun `eq query`() {
        val moment = Instant.now()

        val employee = store.transactional {
            Employee.new { hireMoment = moment }
        }

        store.transactional {
            assertThat(Employee.query(Employee::hireMoment eq moment).toList())
                    .containsExactly(employee)

            assertThat(Employee.query(Employee::hireMoment eq moment.plusSeconds(10)).toList())
                    .isEmpty()
        }
    }

    @Test
    fun `ne query`() {
        val moment = Instant.now()

        val employee = store.transactional {
            Employee.new { hireMoment = moment }
        }

        store.transactional {
            assertThat(Employee.query(Employee::hireMoment ne moment.plusSeconds(10)).toList())
                    .containsExactly(employee)

            assertThat(Employee.query(Employee::hireMoment ne moment).toList())
                    .isEmpty()
        }
    }
}