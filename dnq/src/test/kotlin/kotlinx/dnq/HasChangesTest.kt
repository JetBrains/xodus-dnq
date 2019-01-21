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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.util.hasChanges
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.concurrent.thread
import kotlin.reflect.KMutableProperty1

@RunWith(Parameterized::class)
class HasChangesTest(
        val property: KMutableProperty1<XdIssue, String?>,
        val initialValue: String?,
        val updateValue: String?) : DBTest() {

    class XdIssue(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<XdIssue>()

        var summary by xdStringProp()
        var description by xdBlobStringProp()
        var flagOfSchepotiev by xdNullableBooleanProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNode(XdIssue)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} {2}")
        fun data(): List<Array<Any?>> {
            val values = listOf(null, "", "not-empty")
            val properties = listOf(XdIssue::summary, XdIssue::description)
            return properties.flatMap { property ->
                values.flatMap { initialValue ->
                    values.map { updateValue ->
                        arrayOf(property, initialValue, updateValue)
                    }
                }
            }
        }
    }

    @Test
    fun `change boolean in two transactions JT-10878`() {
        // set boolean to true in one transaction, then do the same in another transaction
        val e = transactional {
            XdIssue.new()
        }

        // cache FlagOfSchepotiev null value in transient level
        transactional {
            assertThat(e.flagOfSchepotiev).isNull()

            thread {
                store.transactional {
                    // set flag to false and save to database
                    assertThat(e.flagOfSchepotiev).isNull()
                    e.flagOfSchepotiev = false
                }
            }.join()

            e.flagOfSchepotiev = false
        }
    }

    @Test
    fun checkStringValue1Value2Value1MakesNoChanges() {
        transactional { txn ->
            val e = XdIssue.new()
            property.set(e, initialValue)
            txn.flush()

            assertThat(property.get(e)).isEqualTo(initialValue)

            property.set(e, updateValue)
            property.set(e, initialValue)
            property.set(e, updateValue)
            property.set(e, initialValue)

            assertThat(e.hasChanges(property)).isFalse()
            assertThat(e.hasChanges()).isFalse()

            txn.flush()
            assertThat(property.get(e)).isEqualTo(initialValue)
        }
    }
}
