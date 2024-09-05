/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.orientdb.OStoreTransaction
import jetbrains.exodus.entitystore.orientdb.OVertexEntity

class ReadOnlyTransientSession(
        private val store: TransientEntityStoreImpl,
        override val transactionInternal: OStoreTransaction) : TransientStoreSession, SessionQueryMixin {

    override val transientChangesTracker: TransientChangesTracker
        get() = ReadOnlyTransientChangesTrackerImpl()

    override val isOpened: Boolean
        get() = !transactionInternal.isFinished

    override val isCommitted: Boolean
        get() = transactionInternal.isFinished

    override val isAborted: Boolean
        get() = transactionInternal.isFinished

    override fun isFinished() = transactionInternal.isFinished

    override fun getStore() = store

    override fun createPersistentEntityIterableWrapper(wrappedIterable: EntityIterable): EntityIterable {
        // do not wrap twice
        return when (wrappedIterable) {
            is PersistentEntityIterableWrapper -> wrappedIterable
            else -> PersistentEntityIterableWrapper(store, wrappedIterable)
        }
    }

    override fun wrap(action: String, entityIterable: EntityIterable): EntityIterable = PersistentEntityIterableWrapper(store, entityIterable)
    
    override fun mergeSorted(
        sorted: MutableList<EntityIterable>,
        valueGetter: ComparableGetter,
        comparator: Comparator<Comparable<Any>>
    ): EntityIterable {
        return transactionInternal.mergeSorted(sorted, valueGetter, comparator)
    }

    override fun newEntity(entityType: String) = throw UnsupportedOperationException()

    override fun newEntity(creator: EntityCreator) = throw UnsupportedOperationException()

    override fun saveEntity(entity: Entity) = throw UnsupportedOperationException()

    override fun newLocalCopy(entity: TransientEntity): TransientEntity = entity

    override fun newEntity(persistentEntity: Entity): ReadonlyTransientEntityImpl {
        if (persistentEntity !is OVertexEntity)
            throw IllegalArgumentException("Cannot create transient entity wrapper for non persistent entity")

        return ReadonlyTransientEntityImpl(persistentEntity, store)
    }

    override fun getEntity(id: EntityId): Entity = newEntity(transactionInternal.getEntity(id))

    override fun hasChanges() = false

    override fun isIdempotent() = true

    override fun isReadonly() = true

    override fun getSnapshot() = transactionInternal

    override fun isRemoved(entity: Entity) = false

    override fun setUpgradeHook(hook: Runnable?) = throw UnsupportedOperationException()

    override fun flush() = throw UnsupportedOperationException()

    override fun revert() = throw UnsupportedOperationException()

    override fun commit(): Boolean {
        return true
    }

    override fun isCurrent(): Boolean {
        return transactionInternal.isCurrent
    }

    override fun abort() {
        store.unregisterStoreSession(this)
    }

    override fun getEntityTypes(): MutableList<String> {
        return transactionInternal.entityTypes
    }

    override fun findWithPropSortedByValue(entityType: String, propertyName: String): EntityIterable {
        return transactionInternal.findWithPropSortedByValue(entityType, propertyName)
    }

    override fun toEntityId(representation: String): EntityId {
        return transactionInternal.toEntityId(representation)
    }

    override fun getSequence(sequenceName: String): Sequence {
        return transactionInternal.getSequence(sequenceName)
    }

    override fun getSequence(sequenceName: String, initialValue: Long): Sequence {
        return transactionInternal.getSequence(sequenceName, initialValue)
    }

    override fun setQueryCancellingPolicy(policy: QueryCancellingPolicy?) {
        transactionInternal.queryCancellingPolicy = policy
    }

    override fun getQueryCancellingPolicy(): QueryCancellingPolicy? = transactionInternal.queryCancellingPolicy

    override val entitiesUpdater = ReadonlyTransientEntitiesUpdater()

    override fun <T>getListenerTransientData(listener: DNQListener<*>): DnqListenerTransientData<T> {
        return object :DnqListenerTransientData<T> {
            override fun <T> getValue(name: String, clazz: Class<T>) = null

            override fun <T> storeValue(name: String, value: T) = Unit

            override fun getRemoved(): T {
                throw IllegalStateException("")
            }

            override fun setRemoved(entity: Any) = Unit
        }
    }

    override val originalValuesProvider = TransientEntityOriginalValuesProviderImpl(this)
}


