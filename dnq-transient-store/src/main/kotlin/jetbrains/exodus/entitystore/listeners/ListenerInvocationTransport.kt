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
package jetbrains.exodus.entitystore.listeners

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.database.TransientEntityStore
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

interface ListenerInvocationTransport {

    fun send(store: TransientEntityStore, invocations: ListenerInvocationsBatch)

    fun addReceiver(store: TransientEntityStore, replication: AsyncListenersReplication)

    fun waitForPendingInvocations(store: TransientEntityStore)
}

class InMemoryTransport : ListenerInvocationTransport {

    val invocations = ConcurrentHashMap<String, MutableList<ListenerInvocationsBatch>>()
    val receivers = ConcurrentHashMap<String, Pair<AsyncListenersReplication, Thread>>()

    override fun send(store: TransientEntityStore, invocations: ListenerInvocationsBatch) {
        this.invocations.getOrPut(store.location) {
            Collections.synchronizedList(arrayListOf())
        }.add(invocations)
    }

    override fun addReceiver(store: TransientEntityStore, replication: AsyncListenersReplication) {
        val location = store.location
        receivers[location] = replication to thread {
            while (receivers[location] != null) {
                invocations[location]?.toTypedArray()?.forEach {
                    (store as TransientEntityStoreImpl).waitForPendingChanges(it.endHighAddress)
                    replication.receive(store, it)
                    invocations[location]?.run {
                        remove(it)
                        if (isEmpty()) {
                            invocations.remove(location)
                        }
                    }
                }
            }
        }
    }

    override fun waitForPendingInvocations(store: TransientEntityStore) {
        val location = store.location
        while (invocations[location] != null) {
            Thread.sleep(100)
        }
    }

    fun cleanup() {
        invocations.clear()
        val pairs = receivers.values.toList()
        receivers.clear()
        pairs.forEach { it.second.join() }
    }
}