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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.LinkChange
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntity
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import java.math.BigInteger

class TxnDiffChangesTracker(override val snapshot: PersistentStoreTransaction,
                            private val current: PersistentStoreTransaction) : TransientChangesTracker {

    init {
        if (snapshot.store != current.store) {
            throw IllegalArgumentException("Both transaction should be created against single EntityStore")
        }
    }

    override val changesHash: BigInteger
        get() = throwUnsupported()
    override val changesDescription: Set<TransientEntityChange>
        get() = throwUnsupported()
    override val changesDescriptionCount: Int
        get() = throwUnsupported()
    override val changedEntities: Set<TransientEntity>
        get() = throwUnsupported()
    override val affectedEntityTypes: Set<String>
        get() = throwUnsupported()

    override fun getChangeDescription(transientEntity: TransientEntity): TransientEntityChange = throwUnsupported()

    override fun getChangedLinksDetailed(transientEntity: TransientEntity): Map<String, LinkChange>? = throwUnsupported()

    override fun getChangedProperties(transientEntity: TransientEntity): Set<String>? = throwUnsupported()

    override fun hasChanges(transientEntity: TransientEntity): Boolean = throwUnsupported()

    override fun hasPropertyChanges(transientEntity: TransientEntity, propName: String): Boolean {
        val oldValue = getPropertyOldValue(transientEntity, propName)
        val newValue = getSnapshotEntity(current, transientEntity).run {
            getProperty(propName) ?: getBlobString(propName)
        }
        if (oldValue === newValue) return false
        if (oldValue == null || newValue == null) return true
        return oldValue != newValue
    }

    override fun hasLinkChanges(transientEntity: TransientEntity, linkName: String): Boolean {
        val oldLinks = getLinksValues(snapshot, transientEntity, linkName).toList()
        val newLinks = getLinksValues(current, transientEntity, linkName).toList()
        return oldLinks.zip(newLinks).any { it.first != it.second }
    }

    override fun getPropertyOldValue(transientEntity: TransientEntity, propName: String): Comparable<*>? =
            getSnapshotEntity(snapshot, transientEntity).run {
                getProperty(propName) ?: getBlobString(propName)
            }

    override fun getSnapshotEntity(transientEntity: TransientEntity): TransientEntity =
            ReadonlyTransientEntityImpl(getSnapshotEntity(snapshot, transientEntity), transientEntity.store)

    override fun upgrade(): TransientChangesTracker = throwUnsupported()

    override fun dispose() = snapshot.abort()

    override fun isNew(transientEntity: TransientEntity) =
            getLastVersion(snapshot, transientEntity) < 0 && getLastVersion(current, transientEntity) >= 0

    override fun isSaved(transientEntity: TransientEntity) = getLastVersion(current, transientEntity) >= 0

    override fun isRemoved(transientEntity: TransientEntity) = !isSaved(transientEntity)

    override fun linkChanged(source: TransientEntity,
                             linkName: String,
                             target: TransientEntity,
                             oldTarget: TransientEntity?,
                             add: Boolean) = throwUnsupported<Unit>()

    override fun linksRemoved(source: TransientEntity,
                              linkName: String,
                              links: Iterable<Entity>) = throwUnsupported<Unit>()

    override fun propertyChanged(e: TransientEntity, propertyName: String) = throwUnsupported<Unit>()

    override fun removePropertyChanged(e: TransientEntity, propertyName: String) = throwUnsupported<Unit>()

    override fun entityAdded(e: TransientEntity) = throwUnsupported<Unit>()

    override fun entityRemoved(e: TransientEntity) = throwUnsupported<Unit>()

    companion object {

        private fun getLastVersion(snapshot: PersistentStoreTransaction, transientEntity: TransientEntity) =
                snapshot.store.getLastVersion(snapshot, transientEntity.id)

        private fun getLinksValues(snapshot: PersistentStoreTransaction, transientEntity: TransientEntity, linkName: String): EntityIterable =
                getSnapshotEntity(snapshot, transientEntity).getLinks(linkName)

        private fun getSnapshotEntity(snapshot: PersistentStoreTransaction, transientEntity: TransientEntity): PersistentEntity =
                transientEntity.persistentEntity.getSnapshot(snapshot)
    }
}

private inline fun <reified T> throwUnsupported(): T {
    throw UnsupportedOperationException("Not supported by TxnDiffChangesTracker")
}