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
package kotlinx.dnq.events

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.EntityChangeType
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import org.junit.Test

open class AsyncListenersTest : AsyncListenersBaseTest() {

    @Test
    fun `updated invocation replicated`() {
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun updatedAsync(old: Bar, current: Bar) {
            }
        })

        val bar = transactional { ExtraBar.new() }
        transactional { bar.bar = "xxx" }

        asyncProcessor.waitForJobs(100)

        forInMemoryTransport { transport ->
            val batchList = transport.invocations[store.location] ?: throw NullPointerException()
            assertThat(batchList.size).isEqualTo(1)
            with((batchList).first { it.invocations.isNotEmpty() }) {
                assertThat(startHighAddress).isGreaterThan(0)
                assertThat(endHighAddress).isGreaterThan(0)
                assertThat(invocations.size).isEqualTo(1)
                with(invocations.first()) {
                    assertThat(changeType).isEqualTo(EntityChangeType.UPDATE)
                    assertThat(entityId.toString()).isEqualTo(bar.xdId)
                }
            }
        }
    }

    @Test
    fun `added invocation replicated`() {
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun addedAsync(added: Bar) {
            }
        })

        val bar = transactional { ExtraBar.new() }

        asyncProcessor.waitForJobs(100)

        forInMemoryTransport { transport ->
            val batchList = transport.invocations[store.location] ?: throw NullPointerException()
            assertThat(batchList.size).isEqualTo(1)
            with((batchList).first()) {
                assertThat(startHighAddress).isGreaterThan(0)
                assertThat(endHighAddress).isGreaterThan(0)
                assertThat(invocations.size).isEqualTo(1)
                with(invocations.first()) {
                    assertThat(changeType).isEqualTo(EntityChangeType.ADD)
                    assertThat(entityId.toString()).isEqualTo(bar.xdId)
                }
            }
        }
    }

    @Test
    fun `removed invocation replicated`() {
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun removedAsync(removed: Bar) {
            }
        })

        val bar = transactional { ExtraBar.new() }
        transactional { bar.delete() }

        asyncProcessor.waitForJobs(100)

        forInMemoryTransport { transport ->
            val batchList = transport.invocations[store.location] ?: throw NullPointerException()
            assertThat(batchList.size).isEqualTo(1)
            with((batchList).first { it.invocations.isNotEmpty() }) {
                assertThat(startHighAddress).isGreaterThan(0)
                assertThat(endHighAddress).isGreaterThan(0)
                assertThat(invocations.size).isEqualTo(1)
                with(invocations.first()) {
                    assertThat(changeType).isEqualTo(EntityChangeType.REMOVE)
                    assertThat(entityId.toString()).isEqualTo(bar.xdId)
                }
            }
        }
    }
}