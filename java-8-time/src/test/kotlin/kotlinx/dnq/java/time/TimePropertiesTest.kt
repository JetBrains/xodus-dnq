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
import kotlinx.dnq.simple.custom.type.XdComparableBinding
import kotlinx.dnq.util.getDBName
import kotlinx.dnq.util.isDefined
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.reflect.KMutableProperty1

@RunWith(Parameterized::class)
class TimePropertiesTest<XD : XdEntity, V : Comparable<V>>(
        val binding: XdComparableBinding<V>,
        val entityType: XdEntityType<XD>,
        val property: KMutableProperty1<XD, V?>,
        val someValue: () -> V,
        val greaterValue: () -> V,
        val lessValue: () -> V
) : DBTest() {

    class Employee(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Employee>()

        var instant by xdInstantProp()
        var localDate by xdLocalDateProp()
        var localTime by xdLocalTimeProp()
    }


    companion object {
        @Parameterized.Parameters(name = "{1}")
        @JvmStatic
        fun data() = listOf(
                line(InstantBinding, Employee, Employee::instant,
                        { Instant.now() }, { Instant.now().plusSeconds(10) }, { Instant.now().minusSeconds(10) }),
                line(LocalDateBinding, Employee, Employee::localDate,
                        { LocalDate.now() }, { LocalDate.now().plusDays(10) }, { LocalDate.now().minusDays(10) }),
                line(LocalTimeBinding, Employee, Employee::localTime,
                        { LocalTime.now() }, { LocalTime.now().plusMinutes(10) }, { LocalTime.now().minusMinutes(10) })
        )

        private fun <XD : XdEntity, V : Comparable<V>> line(
                binding: XdComparableBinding<V>,
                entityType: XdEntityType<XD>,
                property: KMutableProperty1<XD, V?>,
                someValue: () -> V,
                greaterValue: () -> V,
                lessValue: () -> V
        ) = arrayOf(binding, entityType, property, someValue, greaterValue, lessValue)

    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(entityType)
    }

    @Before
    fun initPropertyValueSerializer() {
        // TODO: move to initMetaData()
        binding.register(store)
    }


    @Test
    fun `null by default`() {
        val entity = store.transactional {
            entityType.new()
        }

        store.transactional {
            assertThat(property.get(entity)).isNull()
        }
    }

    @Test
    fun `set and get`() {
        val value = someValue()

        val entity = store.transactional {
            entityType.new { property.set(this, value) }
        }

        store.transactional {
            assertThat(property.get(entity))
                    .isEqualTo(value)
        }
    }

    @Test
    fun `null value`() {
        val entity = store.transactional {
            entityType.new { property.set(this, someValue()) }
        }

        store.transactional {
            property.set(entity, null)
        }

        store.transactional {
            assertThat(property.get(entity)).isNull()
        }
    }


    @Test
    fun `is defined`() {
        val employee = store.transactional {
            entityType.new { property.set(this, someValue()) }
        }

        store.transactional {
            assertThat(employee.isDefined(entityType, property)).isTrue()
        }
    }

    @Test
    fun `is not defined`() {
        val employee = store.transactional {
            entityType.new()
        }

        store.transactional {
            assertThat(employee.isDefined(entityType, property)).isFalse()
        }
    }

    @Test
    fun `eq query match`() {
        val value = someValue()

        val employee = store.transactional {
            entityType.new { property.set(this, value) }
        }

        store.transactional {
            assertThat(entityType.query(eq(property.getDBName(entityType), value)).toList())
                    .containsExactly(employee)
        }
    }

    @Test
    fun `eq query no match`() {
        store.transactional {
            entityType.new { property.set(this, someValue()) }
        }

        store.transactional {
            assertThat(entityType.query(eq(property.getDBName(entityType), greaterValue())).toList())
                    .isEmpty()
        }
    }

    @Test
    fun `ne query match`() {
        val value = someValue()

        val employee = store.transactional {
            entityType.new { property.set(this, value) }
        }

        store.transactional {
            assertThat(entityType.query(ne(property.getDBName(entityType), greaterValue())).toList())
                    .containsExactly(employee)
        }
    }

    @Test
    fun `ne query no match`() {
        val value = someValue()

        store.transactional {
            entityType.new { property.set(this, value) }
        }

        store.transactional {
            assertThat(entityType.query(ne(property.getDBName(entityType), value)).toList())
                    .isEmpty()
        }
    }
}