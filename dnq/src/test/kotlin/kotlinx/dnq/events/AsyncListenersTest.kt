/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package kotlinx.dnq.events

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.entitystore.TransientChangesMultiplexer
import jetbrains.exodus.entitystore.listeners.AsyncListenersReplication
import jetbrains.exodus.entitystore.listeners.InMemoryTransport
import jetbrains.exodus.entitystore.listeners.ListenerInvocationTransport
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.AsyncXdListenersReplication
import kotlinx.dnq.listener.ClassBasedXdListenersSerialization
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import org.junit.After
import org.junit.Before
import org.junit.Test

open class AsyncListenersTest : DBTest() {

    protected lateinit var transport: ListenerInvocationTransport
    protected lateinit var replication: AsyncListenersReplication

    @Before
    fun updateMultiplexer() {
        transport = getListenersInvocationTransport()
        store.changesMultiplexer = TransientChangesMultiplexer(
                asyncJobProcessor = createAsyncProcessor().apply(JobProcessor::start)
        ).also {
            replication = AsyncXdListenersReplication(it, ClassBasedXdListenersSerialization(), transport)
            it.asyncListenersReplication = replication
        }
    }

    protected fun getListenersInvocationTransport(): ListenerInvocationTransport = InMemoryTransport()

    override fun registerEntityTypes() {
        XdModel.registerNodes(Bar)
    }

    @Test
    fun `updated invocation replicated`() {
        Bar.addListener(store, object : XdEntityListener<Bar> {
            override fun updatedAsync(old: Bar, current: Bar) {
            }
        })

        val bar = transactional { Bar.new() }
        transactional { bar.bar = "xxx" }

        asyncProcessor.waitForJobs(100)

        forInMemoryTransport { transport ->
            val batchList = transport.invocations[store.location] ?: throw NullPointerException()
            assertThat(batchList.size).isEqualTo(2)
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

        val bar = transactional { Bar.new() }

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

        val bar = transactional { Bar.new() }
        transactional { bar.delete() }

        asyncProcessor.waitForJobs(100)

        forInMemoryTransport { transport ->
            val batchList = transport.invocations[store.location] ?: throw NullPointerException()
            assertThat(batchList.size).isEqualTo(2)
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

    @After
    fun cleanupTransport() {
        forInMemoryTransport { transport ->
            transport.cleanup()
        }
    }

    private fun forInMemoryTransport(action: (InMemoryTransport) -> Unit) {
        transport.let {
            if (it is InMemoryTransport) {
                action(it)
            }
        }
    }
}