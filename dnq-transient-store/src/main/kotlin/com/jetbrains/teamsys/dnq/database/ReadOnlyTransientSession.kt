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

import jetbrains.exodus.database.EntityCreator
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.orientdb.OStoreTransaction
import jetbrains.exodus.entitystore.orientdb.OVertexEntity
import jetbrains.exodus.env.Transaction

class ReadOnlyTransientSession(
        private val store: TransientEntityStoreImpl,
        override val oStoreTransaction: OStoreTransaction) : TransientStoreSession, SessionQueryMixin {

    override val transactionInternal: StoreTransaction
        get() = oStoreTransaction

    override val transientChangesTracker: TransientChangesTracker
        get() = ReadOnlyTransientChangesTrackerImpl()

    override val isOpened: Boolean
        get() = !oStoreTransaction.isFinished

    override val isCommitted: Boolean
        get() = oStoreTransaction.isFinished

    override val isAborted: Boolean
        get() = oStoreTransaction.isFinished

    override fun isFinished() = oStoreTransaction.isFinished

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
        return oStoreTransaction.mergeSorted(sorted, valueGetter, comparator)
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

    override fun getSnapshot() = oStoreTransaction

    override fun isRemoved(entity: Entity) = false

    override fun setUpgradeHook(hook: Runnable?) = throw UnsupportedOperationException()

    override fun quietIntermediateCommit() = throw UnsupportedOperationException()

    override fun flush() = throw UnsupportedOperationException()

    override fun revert() = throw UnsupportedOperationException()

    override fun commit(): Boolean {
        return true
    }

    override fun isCurrent(): Boolean {
        return oStoreTransaction.isCurrent
    }

    override fun abort() {
        store.unregisterStoreSession(this)
    }

    override fun getEntityTypes(): MutableList<String> {
        return transactionInternal.entityTypes
    }

    override fun findWithPropSortedByValue(entityType: String, propertyName: String): EntityIterable {
        return oStoreTransaction.findWithPropSortedByValue(entityType, propertyName)
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

    override fun getEnvironmentTransaction(): Transaction {
        return oStoreTransaction.environmentTransaction
    }
}
