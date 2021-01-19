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
import jetbrains.exodus.entitystore.listeners.ListenerInvocationTransport
import jetbrains.exodus.entitystore.listeners.ListenerInvocationsBatch
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.AsyncXdListenersReplication
import kotlinx.dnq.listener.ClassBasedXdListenersSerialization
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*

class AsyncListenersTest : DBTest() {

    private val transport = InMemoryTransport()

    @Before
    fun updateMultiplexer() {
        store.changesMultiplexer = TransientChangesMultiplexer(
                asyncJobProcessor = createAsyncProcessor().apply(JobProcessor::start)
        ).also {
            it.asyncListenersReplication = AsyncXdListenersReplication(ClassBasedXdListenersSerialization(), transport)
        }
    }

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

        assertThat(transport.invocations.size).isEqualTo(2)
        with(transport.invocations.first { it.invocations.isNotEmpty() }) {
            assertThat(startHighAddress).isGreaterThan(0)
            assertThat(endHighAddress).isGreaterThan(0)
            assertThat(invocations.size).isEqualTo(1)
            with(invocations.first()) {
                assertThat(changeType).isEqualTo(EntityChangeType.UPDATE)
                assertThat(entityId.toString()).isEqualTo(bar.xdId)
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

        assertThat(transport.invocations.size).isEqualTo(1)
        with(transport.invocations.first()) {
            assertThat(startHighAddress).isGreaterThan(0)
            assertThat(endHighAddress).isGreaterThan(0)
            assertThat(invocations.size).isEqualTo(1)
            with(invocations.first()) {
                assertThat(changeType).isEqualTo(EntityChangeType.ADD)
                assertThat(entityId.toString()).isEqualTo(bar.xdId)
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

        assertThat(transport.invocations.size).isEqualTo(2)
        with(transport.invocations.first { it.invocations.isNotEmpty() }) {
            assertThat(startHighAddress).isGreaterThan(0)
            assertThat(endHighAddress).isGreaterThan(0)
            assertThat(invocations.size).isEqualTo(1)
            with(invocations.first()) {
                assertThat(changeType).isEqualTo(EntityChangeType.REMOVE)
                assertThat(entityId.toString()).isEqualTo(bar.xdId)
            }
        }
    }

    @After
    fun cleanupTransport() {
        transport.cleanup()
    }

}

private class InMemoryTransport : ListenerInvocationTransport {

    internal val invocations = Collections.synchronizedList(arrayListOf<ListenerInvocationsBatch>())

    override fun send(invocation: ListenerInvocationsBatch) {
        invocations.add(invocation)
    }

    fun cleanup() {
        invocations.clear()
    }

}