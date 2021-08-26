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
package jetbrains.exodus.database

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.StoreTransaction

//TODO: rename to TransientStoreTransaction
interface TransientStoreSession : StoreTransaction {

    val persistentTransaction: StoreTransaction

    val transientChangesTracker: TransientChangesTracker

    /**
     * True if session is opened
     */
    val isOpened: Boolean

    /**
     * True if session is committed
     */
    val isCommitted: Boolean

    /**
     * True if session is aborted
     */
    val isAborted: Boolean

    override fun getStore(): TransientEntityStore

    /**
     * Creates wrapper for persistent iterable
     */
    fun createPersistentEntityIterableWrapper(wrappedIterable: EntityIterable): EntityIterable

    override fun newEntity(entityType: String): TransientEntity

    /**
     * Creates new wrapper for persistent entity
     */
    fun newEntity(persistentEntity: Entity): TransientEntity

    fun newEntity(creator: EntityCreator): TransientEntity

    /**
     * Used by dnq to create session local copies of transient entities that come from another session
     */
    fun newLocalCopy(entity: TransientEntity): TransientEntity

    fun hasChanges(): Boolean

    /**
     * Checks if entity entity was removed in this transaction or in database
     *
     * @return true if e was removed, false if it wasn't removed at all
     */
    fun isRemoved(entity: Entity): Boolean

    /**
     * Flushes transaction without checking any constraints, without saving history and without versions check.
     */
    fun quietIntermediateCommit()

    fun setUpgradeHook(hook: Runnable?)
}
