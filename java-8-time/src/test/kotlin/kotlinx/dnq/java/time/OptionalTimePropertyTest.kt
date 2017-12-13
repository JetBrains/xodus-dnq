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
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.transactional
import kotlinx.dnq.util.isDefined
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OptionalTimePropertyTest<XD : XdEntity, V : Comparable<V>>(val data: TimePropertiesTestData<XD, V>) : DBTest() {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data() = timePropertiesTestData
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(data.entityType)
    }

    @Test
    fun `null by default`() {
        val entity = new()

        store.transactional {
            assertThat(data.optionalProperty.get(entity)).isNull()
        }
    }

    @Test
    fun `set and get`() {
        val value = data.someValue()

        val entity = new(value)

        store.transactional {
            assertThat(data.optionalProperty.get(entity))
                    .isEqualTo(value)
        }
    }

    @Test
    fun `null value`() {
        val entity = new(data.someValue())

        store.transactional {
            data.optionalProperty.set(entity, null)
        }

        store.transactional {
            assertThat(data.optionalProperty.get(entity)).isNull()
        }
    }


    @Test
    fun `is defined`() {
        val entity = new(data.someValue())

        store.transactional {
            assertThat(entity.isDefined(data.entityType, data.optionalProperty)).isTrue()
        }
    }

    @Test
    fun `is not defined`() {
        val entity = new()

        store.transactional {
            assertThat(entity.isDefined(data.entityType, data.optionalProperty)).isFalse()
        }
    }

    private fun new(value: V? = null) = store.transactional {
        data.entityType.new {
            data.requiredProperty.set(this, data.someValue())
            if (value != null) {
                data.optionalProperty.set(this, value)
            }
        }
    }
}