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

import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.EntityId

class ListenerInvocations(private val replication: AsyncListenersReplication,
                          private val startHighAddress: Long,
                          private val endHighAddress: Long) {

    private val invocations: MutableList<ListenerInvocation> = arrayListOf()

    fun addInvocation(change: TransientEntityChange, listener: IEntityListener<*>) {
        if (!replication.shouldReplicate(change, listener)) {
            return
        }
        val entityId = change.transientEntity.id
        invocations.add(
                ListenerInvocation(
                        changeType = change.changeType,
                        entityId = entityId,
                        listenerKey = replication.listenersSerialization.getKey(listener)
                )
        )
    }

    fun send(store: TransientEntityStore) {
        if (invocations.isNotEmpty()) {
            val batch = ListenerInvocationsBatch(
                    startHighAddress = startHighAddress,
                    endHighAddress = endHighAddress,
                    invocations = invocations
            )
            replication.transport.send(store, batch)
        }
    }
}

data class ListenerInvocation(val listenerKey: String,
                              val changeType: EntityChangeType,
                              val entityId: EntityId)

data class ListenerInvocationsBatch(val startHighAddress: Long,
                                    val endHighAddress: Long,
                                    val invocations: List<ListenerInvocation>)