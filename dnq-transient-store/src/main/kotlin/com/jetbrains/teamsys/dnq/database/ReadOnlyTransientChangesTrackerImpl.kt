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
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import java.math.BigInteger

class ReadOnlyTransientChangesTrackerImpl(private var _snapshot: PersistentStoreTransaction?) : TransientChangesTracker {

    override val changesHash: BigInteger
        get() = BigInteger.ZERO

    override val changesDescription: Set<TransientEntityChange>
        get() = emptySet()

    override val changesDescriptionCount: Int
        get() = 0

    override val snapshot: PersistentStoreTransaction
        get() = _snapshot
                ?: throw IllegalStateException("Cannot get persistent store transaction because changes tracker is already disposed")

    override val changedEntities: Set<TransientEntity>
        get() = emptySet()

    override val affectedEntityTypes: Set<String>
        get() = emptySet()

    override fun getChangeDescription(transientEntity: TransientEntity): TransientEntityChange = throw UnsupportedOperationException()

    override fun getChangedLinksDetailed(transientEntity: TransientEntity): Map<String, LinkChange>? = emptyMap()

    override fun getChangedProperties(transientEntity: TransientEntity): Set<String>? = emptySet()

    override fun hasChanges(transientEntity: TransientEntity) = false

    override fun hasPropertyChanges(transientEntity: TransientEntity, propName: String) = false

    override fun hasLinkChanges(transientEntity: TransientEntity, linkName: String) = false

    override fun getPropertyOldValue(transientEntity: TransientEntity, propName: String): Comparable<*>? =
            transientEntity.persistentEntity.getSnapshot(snapshot).run {
                getProperty(propName) ?: getBlobString(propName)
            }

    override fun getSnapshotEntity(transientEntity: TransientEntity): TransientEntity = throw UnsupportedOperationException()

    override fun isNew(transientEntity: TransientEntity) = false

    override fun isSaved(transientEntity: TransientEntity) = true

    override fun isRemoved(transientEntity: TransientEntity) = false

    override fun linkChanged(
            source: TransientEntity,
            linkName: String,
            target: TransientEntity,
            oldTarget: TransientEntity?,
            add: Boolean
    ) = throw UnsupportedOperationException()

    override fun linksRemoved(source: TransientEntity, linkName: String, links: Iterable<Entity>): Unit =
            throw UnsupportedOperationException()

    override fun propertyChanged(e: TransientEntity, propertyName: String): Unit =
            throw UnsupportedOperationException()

    override fun removePropertyChanged(e: TransientEntity, propertyName: String): Unit =
            throw UnsupportedOperationException()

    override fun entityAdded(e: TransientEntity): Unit =
            throw UnsupportedOperationException()

    override fun entityRemoved(e: TransientEntity): Unit =
            throw UnsupportedOperationException()

    override fun upgrade(): TransientChangesTracker {
        return TransientChangesTrackerImpl(snapshot)
    }

    override fun dispose() {
        _snapshot?.let {
            it.abort()
            _snapshot = null
        }
    }
}
