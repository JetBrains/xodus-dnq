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
package jetbrains.exodus.entitystore.listeners

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import com.jetbrains.teamsys.dnq.database.TransientSessionImpl
import com.jetbrains.teamsys.dnq.database.highAddress
import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentEntity
import jetbrains.exodus.entitystore.TransientChangesMultiplexer
import jetbrains.exodus.env.EnvironmentImpl
import java.util.concurrent.ConcurrentHashMap

abstract class AsyncListenersReplication(private val multiplexer: TransientChangesMultiplexer,
                                         val listenersSerialization: TransientListenersSerialization,
                                         val transport: ListenerInvocationTransport) {

    protected open val listenersMetaData = ConcurrentHashMap<String, ListenerMataData>()

    fun newInvocations(changesTracker: TransientChangesTracker, session: TransientSessionImpl): ListenerInvocations? {
        if ((session.store.persistentStore.environment as EnvironmentImpl).log.config.readerWriterProvider?.isReadonly == true) {
            return null
        }
        val currentHighAddress = session.highAddress
        return ListenerInvocations(
                replication = this,
                startHighAddress = changesTracker.snapshot.highAddress,
                endHighAddress = currentHighAddress
        )
    }

    fun receive(store: TransientEntityStore, batch: ListenerInvocationsBatch) {
        store as TransientEntityStoreImpl
        val txn = TransientSessionImpl(store,
                readonly = true,
                snapshotAddress = batch.startHighAddress,
                currentAddress = batch.endHighAddress)
        store.registerStoreSession(txn)
        try {
            batch.invocations.forEach {
                val listener = listenersSerialization.getListener(it, multiplexer, txn)
                (listener as? IEntityListener<Entity>)?.run {
                    val currentEntity = txn.newEntity(store.persistentStore.getEntity(it.entityId) as PersistentEntity)
                    when (it.changeType) {
                        EntityChangeType.ADD -> addedAsync(currentEntity)
                        EntityChangeType.REMOVE -> removedAsync(currentEntity)
                        else -> {
                            val snapshotEntity = txn.transientChangesTracker.getSnapshotEntity(currentEntity)
                            updatedAsync(snapshotEntity, currentEntity)
                        }
                    }

                } ?: throw IllegalStateException("Can't find listener for listenerKey=${it.listenerKey}")
            }
        } finally {
            txn.abort()
        }
    }

    open fun shouldReplicate(change: TransientEntityChange, listener: IEntityListener<*>): Boolean {
        val cachedMetadata = listenersMetaData.getOrPut(listener.metadataKey) {
            listener.metadata
        }
        return when (change.changeType) {
            EntityChangeType.ADD -> cachedMetadata.hasAsyncAdded
            EntityChangeType.UPDATE -> cachedMetadata.hasAsyncUpdated
            EntityChangeType.REMOVE -> cachedMetadata.hasAsyncRemoved
        }
    }

    abstract val DNQListener<*>.metadataKey: String
    abstract val DNQListener<*>.metadata: ListenerMataData

}

data class ListenerMataData(val hasAsyncAdded: Boolean,
                            val hasAsyncRemoved: Boolean,
                            val hasAsyncUpdated: Boolean)