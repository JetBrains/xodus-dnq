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
import com.jetbrains.teamsys.dnq.database.TransientSessionImpl
import com.jetbrains.teamsys.dnq.database.highAddress
import jetbrains.exodus.core.execution.Job
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.listeners.ListenerInvocationsImpl
import jetbrains.exodus.env.EnvironmentImpl

internal class FullEntityId(store: EntityStore, id: EntityId) {

    private val storeHashCode: Int = System.identityHashCode(store)
    private val entityTypeId: Int = id.typeId
    private val entityLocalId: Long = id.localId

    override fun equals(other: Any?) = (this === other) ||
            (other is FullEntityId && storeHashCode == other.storeHashCode &&
                    entityLocalId == other.entityLocalId && entityTypeId == other.entityTypeId)

    override fun hashCode(): Int {
        var result = storeHashCode
        result = 31 * result + entityTypeId + 1
        result = 31 * result + ((entityLocalId + 1) xor (entityLocalId shr 32)).toInt()
        return result
    }

    override fun toString() = buildString(20) {
        toString(this)
    }

    fun toString(builder: StringBuilder) =
            builder.append(entityTypeId).append('-').append(entityLocalId).append('@').append(storeHashCode)
}

internal class TransientChangesMultiplexerJob(private val store: TransientEntityStore,
                                              private val transientChangesMultiplexer: TransientChangesMultiplexer,
                                              private val changes: Set<TransientEntityChange>,
                                              private val changesTracker: TransientChangesTracker) : Job() {

    public override fun execute() {
        try {
            (store as TransientEntityStoreImpl).run {
                val isPrimary = (persistentStore.environment as EnvironmentImpl).log.config.readerWriterProvider?.isReadonly != true
                val snapshotAddress = changesTracker.snapshot.highAddress
                val currentAddress = persistentStore.computeInReadonlyTransaction { it.highAddress }
                val txn = TransientSessionImpl(
                        store = this,
                        readonly = true,
                        snapshotAddress = snapshotAddress,
                        currentAddress = currentAddress)
                registerStoreSession(txn)
                try {
                    transientChangesMultiplexer.run {
                        var invocations: ListenerInvocationsImpl? = null
                        val transport = store.invocationTransport
                        if (isPrimary && transport != null) {
                            invocations = asyncListenersReplication?.newInvocations(transport, snapshotAddress, currentAddress)
                        }
                        fire(store, Where.ASYNC_AFTER_FLUSH, changes, invocations)
                    }
                } finally {
                    txn.abort()
                }
            }
        } finally {
            changesTracker.dispose()
        }
    }

    override fun getName() = "Async events from EventMultiplexer"

    override fun getGroup() = store.location
}
