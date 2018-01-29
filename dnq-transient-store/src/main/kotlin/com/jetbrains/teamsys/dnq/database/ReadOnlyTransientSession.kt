/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.EntityCreator
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.*

class ReadOnlyTransientSession(
        private val store: TransientEntityStoreImpl,
        override val persistentTransaction: PersistentStoreTransaction) : TransientStoreSession, SessionQueryMixin {

    override val persistentTransactionInternal: PersistentStoreTransaction
        get() = persistentTransaction

    override val transientChangesTracker: TransientChangesTracker
        get() = ReadOnlyTransientChangesTrackerImpl(persistentTransaction)

    override val isOpened: Boolean
        get() = !persistentTransaction.isFinished

    override val isCommitted: Boolean
        get() = persistentTransaction.isFinished

    override val isAborted: Boolean
        get() = persistentTransaction.isFinished

    override fun isFinished() = persistentTransaction.isFinished

    override fun getStore() = store

    override fun createPersistentEntityIterableWrapper(wrappedIterable: EntityIterable): EntityIterable {
        // do not wrap twice
        return when (wrappedIterable) {
            is PersistentEntityIterableWrapper -> wrappedIterable
            else -> PersistentEntityIterableWrapper(store, wrappedIterable)
        }
    }

    override fun wrap(action: String, entityIterable: EntityIterable): EntityIterable = PersistentEntityIterableWrapper(store, entityIterable)

    override fun newEntity(entityType: String) = throw UnsupportedOperationException()

    override fun newEntity(creator: EntityCreator) = throw UnsupportedOperationException()

    override fun saveEntity(entity: Entity) = throw UnsupportedOperationException()

    override fun newLocalCopy(entity: TransientEntity): TransientEntity = entity

    override fun newEntity(persistentEntity: Entity): ReadonlyTransientEntityImpl {
        if (persistentEntity !is PersistentEntity)
            throw IllegalArgumentException("Cannot create transient entity wrapper for non persistent entity")

        return ReadonlyTransientEntityImpl(persistentEntity, store)
    }

    override fun getEntity(id: EntityId): Entity = newEntity(persistentTransactionInternal.getEntity(id))

    override fun hasChanges() = false

    override fun isIdempotent() = true

    override fun isReadonly() = true

    override fun getSnapshot() = persistentTransaction

    override fun isRemoved(entity: Entity) = false

    override fun setUpgradeHook(hook: Runnable?) = throw UnsupportedOperationException()

    override fun quietIntermediateCommit() = throw UnsupportedOperationException()

    override fun flush() = throw UnsupportedOperationException()

    override fun revert() = throw UnsupportedOperationException()

    override fun commit(): Boolean {
        store.unregisterStoreSession(this)
        return true
    }

    override fun abort() {
        store.unregisterStoreSession(this)
    }

    override fun getEntityTypes(): MutableList<String> {
        return persistentTransactionInternal.entityTypes
    }

    override fun toEntityId(representation: String): EntityId {
        return persistentTransactionInternal.toEntityId(representation)
    }

    override fun getSequence(sequenceName: String): Sequence {
        return persistentTransactionInternal.getSequence(sequenceName)
    }

    override fun setQueryCancellingPolicy(policy: QueryCancellingPolicy?) {
        persistentTransactionInternal.queryCancellingPolicy = policy
    }

    override fun getQueryCancellingPolicy(): QueryCancellingPolicy? = persistentTransactionInternal.queryCancellingPolicy
}
