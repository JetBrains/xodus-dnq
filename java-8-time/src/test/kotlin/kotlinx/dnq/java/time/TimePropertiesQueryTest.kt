/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.java.time

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.query.NodeBase
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.*
import kotlinx.dnq.util.getDBName
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TimePropertiesQueryTest<XD : XdEntity, V : Comparable<V>>(val data: TimePropertiesTestData<XD, V>) : DBTest() {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data() = timePropertiesTestData
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(data.entityType)
    }

    private val optionalPropertyName get() = data.optionalProperty.getDBName(data.entityType)
    private val requiredPropertyName get() = data.requiredProperty.getDBName(data.entityType)

    @Test
    fun `eq query should match same value`() {
        val value = data.someValue()
        val entity = newEntityWithPropertyValue(value)
        assertThatQuery(eq(optionalPropertyName, value)).containsExactly(entity)
        assertThatQuery(eq(requiredPropertyName, value)).containsExactly(entity)
    }

    @Test
    fun `eq query should not match different value`() {
        val value = data.someValue()
        val differentValue = data.greaterValue(value)
        newEntityWithPropertyValue(differentValue)
        assertThatQuery(eq(optionalPropertyName, value)).isEmpty()
        assertThatQuery(eq(requiredPropertyName, value)).isEmpty()
    }

    @Test
    fun `ne query should match different value`() {
        val value = data.someValue()
        val differentValue = data.greaterValue(value)
        val entity = newEntityWithPropertyValue(differentValue)
        assertThatQuery(ne(optionalPropertyName, value)).containsExactly(entity)
        assertThatQuery(ne(requiredPropertyName, value)).containsExactly(entity)
    }

    @Test
    fun `ne query should not match same value`() {
        val value = data.someValue()
        newEntityWithPropertyValue(value)
        assertThatQuery(ne(optionalPropertyName, value)).isEmpty()
        assertThatQuery(ne(requiredPropertyName, value)).isEmpty()
    }

    @Test
    fun `lt query should match less value`() {
        val value = data.someValue()
        val lessValue = data.lessValue(value)
        val entity = newEntityWithPropertyValue(lessValue)
        assertThatQuery(lt(optionalPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
        assertThatQuery(lt(requiredPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
    }

    @Test
    fun `lt query should not match same value`() {
        val value = data.someValue()
        newEntityWithPropertyValue(value)
        assertThatQuery(lt(optionalPropertyName, value, value.javaClass.kotlin)).isEmpty()
        assertThatQuery(lt(requiredPropertyName, value, value.javaClass.kotlin)).isEmpty()
    }

    @Test
    fun `lt query should not match greater value`() {
        val value = data.someValue()
        val greaterValue = data.greaterValue(value)
        newEntityWithPropertyValue(greaterValue)
        assertThatQuery(lt(optionalPropertyName, value, value.javaClass.kotlin)).isEmpty()
        assertThatQuery(lt(requiredPropertyName, value, value.javaClass.kotlin)).isEmpty()
    }

    @Test
    fun `gt query should match greater value`() {
        val value = data.someValue()
        val greaterValue = data.greaterValue(value)
        val entity = newEntityWithPropertyValue(greaterValue)
        assertThatQuery(gt(optionalPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
        assertThatQuery(gt(requiredPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
    }

    @Test
    fun `gt query should not match same value`() {
        val value = data.someValue()
        newEntityWithPropertyValue(value)
        assertThatQuery(gt(optionalPropertyName, value, value.javaClass.kotlin)).isEmpty()
        assertThatQuery(gt(requiredPropertyName, value, value.javaClass.kotlin)).isEmpty()
    }

    @Test
    fun `gt query should not match less value`() {
        val value = data.someValue()
        val lessValue = data.lessValue(value)
        newEntityWithPropertyValue(lessValue)
        assertThatQuery(gt(optionalPropertyName, value, lessValue.javaClass.kotlin)).isEmpty()
        assertThatQuery(gt(requiredPropertyName, value, lessValue.javaClass.kotlin)).isEmpty()
    }

    @Test
    fun `le query should match less value`() {
        val value = data.someValue()
        val lessValue = data.lessValue(value)
        val entity = newEntityWithPropertyValue(lessValue)
        assertThatQuery(le(optionalPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
        assertThatQuery(le(requiredPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
    }

    @Test
    fun `le query should match same value`() {
        val value = data.someValue()
        val entity = newEntityWithPropertyValue(value)
        assertThatQuery(le(optionalPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
        assertThatQuery(le(requiredPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
    }

    @Test
    fun `le query should not match greater value`() {
        val value = data.someValue()
        val greaterValue = data.greaterValue(value)
        newEntityWithPropertyValue(greaterValue)
        assertThatQuery(le(optionalPropertyName, value, value.javaClass.kotlin)).isEmpty()
        assertThatQuery(le(requiredPropertyName, value, value.javaClass.kotlin)).isEmpty()
    }

    @Test
    fun `ge query should match greater value`() {
        val value = data.someValue()
        val greaterValue = data.greaterValue(value)
        val entity = newEntityWithPropertyValue(greaterValue)
        assertThatQuery(ge(optionalPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
        assertThatQuery(ge(requiredPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
    }

    @Test
    fun `ge query should match same value`() {
        val value = data.someValue()
        val entity = newEntityWithPropertyValue(value)
        assertThatQuery(ge(optionalPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
        assertThatQuery(ge(requiredPropertyName, value, value.javaClass.kotlin)).containsExactly(entity)
    }

    @Test
    fun `ge query should not match less value`() {
        val value = data.someValue()
        val lessValue = data.lessValue(value)
        newEntityWithPropertyValue(lessValue)
        assertThatQuery(ge(optionalPropertyName, value, lessValue.javaClass.kotlin)).isEmpty()
        assertThatQuery(ge(requiredPropertyName, value, lessValue.javaClass.kotlin)).isEmpty()
    }

    private fun newEntityWithPropertyValue(value: V): XD {
        return store.transactional {
            data.entityType.new {
                data.optionalProperty.set(this, value)
                data.requiredProperty.set(this, value)
            }
        }
    }

    private fun assertThatQuery(node: NodeBase): IterableSubject {
        return store.transactional {
            assertThat(data.entityType.query(node).toList())
        }
    }
}