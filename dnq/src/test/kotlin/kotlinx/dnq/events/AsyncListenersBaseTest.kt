/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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

import jetbrains.exodus.database.ListenerInvocationsBatch
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.TransientChangesMultiplexer
import jetbrains.exodus.entitystore.listeners.InMemoryTransport
import jetbrains.exodus.entitystore.listeners.TransientListenersSerialization
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.listener.AsyncXdListenersReplication
import kotlinx.dnq.listener.ClassBasedXdListenersSerialization
import org.junit.After
import org.junit.Before
import java.util.concurrent.ConcurrentHashMap

abstract class AsyncListenersBaseTest : DBTest() {

    private lateinit var multiplexer: TransientChangesMultiplexer
    private lateinit var replication: SavingStateAsyncListenersReplication

    override fun registerEntityTypes() {
        XdModel.registerNodes(Bar, ExtraBar, Goo, Foo)
    }

    @Before
    fun updateMultiplexer() {
        multiplexer = store.changesMultiplexer as TransientChangesMultiplexer
        replication = SavingStateAsyncListenersReplication(multiplexer, ClassBasedXdListenersSerialization)
        multiplexer.asyncListenersReplication = replication
        store.invocationTransport = InMemoryTransport(store, multiplexer)
        store.invocationTransport?.addReceiver(replication)
    }

    @After
    fun cleanupTransport() {
        replication.cleanup()
    }

    protected fun assertInvocations(action: (Collection<ListenerInvocationsBatch>) -> Unit) {
        store.invocationTransport?.waitForPendingInvocations()
        action(replication.invocations)
    }
}

class SavingStateAsyncListenersReplication(
    multiplexer: TransientChangesMultiplexer,
    listenersSerialization: TransientListenersSerialization
) : AsyncXdListenersReplication(multiplexer, listenersSerialization) {

    internal val invocations = ConcurrentHashMap.newKeySet<ListenerInvocationsBatch>()

    override fun receive(store: TransientEntityStore, batch: ListenerInvocationsBatch) {
        super.receive(store, batch)
        invocations.add(batch)
    }

    fun cleanup() {
        invocations.clear()
    }

}
