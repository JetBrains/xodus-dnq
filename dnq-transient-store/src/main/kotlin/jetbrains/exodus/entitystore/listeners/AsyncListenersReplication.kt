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

import jetbrains.exodus.database.DNQListener
import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.TransientEntityChange
import java.util.concurrent.ConcurrentHashMap

abstract class AsyncListenersReplication(protected val listenersSerialization: TransientListenersSerialization, protected val transport: ListenerInvocationTransport) {

    protected open val listenersMetaData = ConcurrentHashMap<String, ListenerMataData>()

    open fun replicate(change: TransientEntityChange, listener: IEntityListener<*>) {
        if (!logNeed(change, listener)) {
            return
        }
        val entityType = change.transientEntity.type
        val highAddress = change.changesTracker.snapshot.environmentTransaction.highAddress
        val entityId = change.transientEntity.id.toString()
        transport.send(
                ListenerInvocation(
                        highAddress = highAddress,
                        changeType = change.changeType,
                        entityType = entityType,
                        listenerKey = listenersSerialization.getKey(listener),
                        params = listOf(entityId)
                )
        )
    }

    open fun replay(invocation: ListenerInvocation) {
    }

    protected open fun logNeed(change: TransientEntityChange, listener: IEntityListener<*>): Boolean {
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

    inner class ListenerMataData(
            val hasAsyncAdded: Boolean,
            val hasAsyncRemoved: Boolean,
            val hasAsyncUpdated: Boolean
    )
}