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
package jetbrains.exodus.database

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import java.math.BigInteger

interface TransientChangesTracker {

    /**
     * Hash function of all changes.
     */
    val changesHash: BigInteger

    /**
     * Return description of all changes
     */
    val changesDescription: Set<TransientEntityChange>

    /**
     * Return size of the result of getChangesDescription
     */
    val changesDescriptionCount: Int

    val changedEntities: Set<TransientEntity>

    val affectedEntityTypes: Set<String>

    /**
     * Return change description for given entity
     */
    fun getChangeDescription(transientEntity: TransientEntity): TransientEntityChange

    /**
     * Returns set of changed links for given entity
     */
    fun getChangedLinksDetailed(transientEntity: TransientEntity): Map<String, LinkChange>?

    /**
     * Returns set of changed properties for given entity
     */
    fun getChangedProperties(transientEntity: TransientEntity): Set<String>?

    fun hasChanges(transientEntity: TransientEntity): Boolean

    fun hasPropertyChanges(transientEntity: TransientEntity, propName: String): Boolean

    fun hasLinkChanges(transientEntity: TransientEntity, linkName: String): Boolean

    fun getPropertyOldValue(transientEntity: TransientEntity, propName: String): Comparable<*>?

    fun getSnapshotEntity(transientEntity: TransientEntity): TransientEntity

    fun upgrade(): TransientChangesTracker

    fun dispose()

    fun isNew(transientEntity: TransientEntity): Boolean

    fun isSaved(transientEntity: TransientEntity): Boolean

    fun isRemoved(transientEntity: TransientEntity): Boolean

    fun linkChanged(source: TransientEntity, linkName: String, target: TransientEntity, oldTarget: TransientEntity?, add: Boolean)

    fun linksRemoved(source: TransientEntity, linkName: String, links: @JvmSuppressWildcards Iterable<Entity>)

    fun propertyChanged(e: TransientEntity, propertyName: String)

    fun removePropertyChanged(e: TransientEntity, propertyName: String)

    fun entityAdded(e: TransientEntity)

    fun entityRemoved(e: TransientEntity)

    fun getRemovedEntitiesIds(): Collection<EntityId>
}
