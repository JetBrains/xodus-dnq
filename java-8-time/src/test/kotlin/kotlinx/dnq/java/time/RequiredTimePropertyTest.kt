/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import kotlinx.dnq.*
import kotlinx.dnq.util.isDefined
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class RequiredTimePropertyTest<XD : XdEntity, V : Comparable<V>>(val data: TimePropertiesTestData<XD, V>) : DBTest() {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data() = timePropertiesTestData
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(data.entityType)
    }

    @Test
    fun `is required`() {
        assertFailsWith<DataIntegrityViolationException> {
            store.transactional {
                data.entityType.new()
            }
        }
    }

    @Test
    fun `exception by default`() {
        store.transactional { txn ->
            val entity = data.entityType.new()
            assertFailsWith<RequiredPropertyUndefinedException> {
                data.requiredProperty.get(entity)
            }
            txn.revert()
        }
    }

    @Test
    fun `set and get`() {
        val value = data.someValue()

        val entity = store.transactional {
            data.entityType.new { data.requiredProperty.set(this, value) }
        }

        store.transactional {
            assertThat(data.requiredProperty.get(entity))
                    .isEqualTo(value)
        }
    }

    @Test
    fun `is defined`() {
        store.transactional {
            val entity = data.entityType.new { data.requiredProperty.set(this, data.someValue()) }
            assertThat(entity.isDefined(data.entityType, data.requiredProperty)).isTrue()
        }
    }

    @Test
    fun `is not defined`() {
        store.transactional { txn ->
            val entity = data.entityType.new()
            assertThat(entity.isDefined(data.entityType, data.requiredProperty)).isFalse()
            txn.revert()
        }
    }
}