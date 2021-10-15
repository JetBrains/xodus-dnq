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
package jetbrains.exodus.entitystore

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.core.execution.JobProcessor
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.listeners.AsyncListenersReplication
import jetbrains.exodus.entitystore.listeners.ListenerInvocationTransport


class MultiplexerBuilder private constructor(private val store: TransientEntityStore){

    companion object {
        fun new(store: TransientEntityStore) = MultiplexerBuilder(store)
    }

    private var jobProcessor: JobProcessor? = null
    private var transportFactory: ((TransientEntityStore, TransientChangesMultiplexer) -> ListenerInvocationTransport)? = null
    private var replicationFactory: ((TransientEntityStore, TransientChangesMultiplexer) -> AsyncListenersReplication)? = null

    fun jobProcessor(jobProcessor: JobProcessor): MultiplexerBuilder {
        this.jobProcessor = jobProcessor
        return this
    }

    fun replication(factory: (TransientEntityStore, TransientChangesMultiplexer) -> AsyncListenersReplication): MultiplexerBuilder {
        this.replicationFactory = factory
        return this
    }

    fun transport(factory: (TransientEntityStore, TransientChangesMultiplexer) -> ListenerInvocationTransport): MultiplexerBuilder {
        this.transportFactory = factory
        return this
    }

    fun build(): TransientChangesMultiplexer {
        val result = TransientChangesMultiplexer(asyncJobProcessor = jobProcessor)
        if (store is TransientEntityStoreImpl) {
            store.changesMultiplexer = result
        }
        val replication = replicationFactory?.invoke(store, result)
        val transport = transportFactory?.invoke(store, result)
        if (replication != null && transport != null) {
            transport.addReceiver(replication)
            result.asyncListenersReplication = replication
            result.transport = transport
        }
        return result
    }
}
