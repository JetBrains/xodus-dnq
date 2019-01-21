/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore

import jetbrains.exodus.core.execution.Job
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore

internal class FullEntityId(store: EntityStore, id: EntityId) {
    private val storeHashCode: Int = System.identityHashCode(store)
    private val entityTypeId: Int = id.typeId
    private val entityLocalId: Long = id.localId

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FullEntityId) {
            return false
        }
        if (storeHashCode != other.storeHashCode) {
            return false
        }
        return if (entityLocalId != other.entityLocalId) {
            false
        } else entityTypeId == other.entityTypeId
    }

    override fun hashCode(): Int {
        var result = storeHashCode
        result = 31 * result + entityTypeId
        result = 31 * result + (entityLocalId xor (entityLocalId shr 32)).toInt()
        return result
    }

    override fun toString(): String {
        val builder = StringBuilder(10)
        toString(builder)
        return builder.toString()
    }

    fun toString(builder: StringBuilder) {
        builder.append(entityTypeId)
        builder.append('-')
        builder.append(entityLocalId)
        builder.append('@')
        builder.append(storeHashCode)
    }
}

internal class EventsMultiplexerJob(
        private val store: TransientEntityStore,
        private val eventsMultiplexer: EventsMultiplexer,
        private val changes: Set<TransientEntityChange>,
        private val changesTracker: TransientChangesTracker
) : Job() {

    @Throws(Throwable::class)
    public override fun execute() {
        try {
            store.transactional {
                eventsMultiplexer.fire(store, Where.ASYNC_AFTER_FLUSH, this@EventsMultiplexerJob.changes)
            }
        } finally {
            changesTracker.dispose()
        }
    }

    override fun getName() = "Async events from EventMultiplexer"

    override fun getGroup(): String {
        return changesTracker.snapshot.getStore().location
    }
}
