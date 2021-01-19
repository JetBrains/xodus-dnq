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

import jetbrains.exodus.database.*
import java.util.concurrent.ConcurrentHashMap

abstract class AsyncListenersReplication(val listenersSerialization: TransientListenersSerialization, val transport: ListenerInvocationTransport) {

    protected open val listenersMetaData = ConcurrentHashMap<String, ListenerMataData>()

    fun newCollector(changesTracker: TransientChangesTracker, session: TransientStoreSession): ListenerInvocationsCollector {
        val currentHighAddress = session.transientChangesTracker.snapshot.environmentTransaction.highAddress
        return ListenerInvocationsCollector(
                replication = this,
                startHighAddress = changesTracker.snapshot.environmentTransaction.highAddress,
                endHighAddress = currentHighAddress
        )
    }

    open fun replay(batch: ListenerInvocationsBatch) {
        //TODO
    }

    open fun logNeed(change: TransientEntityChange, listener: IEntityListener<*>): Boolean {
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

data class ListenerMataData(
        val hasAsyncAdded: Boolean,
        val hasAsyncRemoved: Boolean,
        val hasAsyncUpdated: Boolean
)
