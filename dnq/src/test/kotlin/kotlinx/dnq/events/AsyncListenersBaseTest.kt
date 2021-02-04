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

import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.entitystore.TransientChangesMultiplexer
import jetbrains.exodus.entitystore.listeners.AsyncListenersReplication
import jetbrains.exodus.entitystore.listeners.InMemoryTransport
import jetbrains.exodus.entitystore.listeners.ListenerInvocationTransport
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.AsyncXdListenersReplication
import kotlinx.dnq.listener.ClassBasedXdListenersSerialization
import org.junit.After
import org.junit.Before

abstract class AsyncListenersBaseTest : DBTest() {

    protected lateinit var transport: ListenerInvocationTransport
    protected lateinit var replication: AsyncListenersReplication

    override fun registerEntityTypes() {
        XdModel.registerNodes(Bar, ExtraBar)
    }

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

    @After
    fun cleanupTransport() {
        forInMemoryTransport { transport ->
            transport.cleanup()
        }
    }

    protected fun forInMemoryTransport(action: (InMemoryTransport) -> Unit) {
        transport.let {
            if (it is InMemoryTransport) {
                action(it)
            }
        }
    }
}