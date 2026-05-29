/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntity
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import java.io.File
import java.io.InputStream

class ReadonlyTransientEntity(change: TransientEntityChange?, snapshot: YTDBEntity, store: TransientEntityStore) :
    TransientEntityImpl(snapshot, store) {

    constructor(snapshot: YTDBEntity, store: TransientEntityStore) : this(null, snapshot, store)

    private val originalValuesProvider get() = threadSessionOrThrow.originalValuesProvider

    private val hasChanges by lazy {
        changedProperties.isNotEmpty() || changedLinks.values.any { it.isNotEmpty() }
    }

    private val changedLinks = change?.changedLinksDetailed.orEmpty()
    private val changedProperties = change?.changedProperties.orEmpty()

    override val isReadonly: Boolean
        get() = true

    //region readonly throw

    override fun delete(): Boolean {
        throwReadonlyException()
    }

    override fun setProperty(propertyName: String, value: Comparable<*>): Boolean {
        throwReadonlyException()
    }

    override fun setBlob(blobName: String, blob: InputStream) {
        throwReadonlyException()
    }

    override fun setBlob(blobName: String, file: File) {
        throwReadonlyException()
    }

    override fun setBlobString(blobName: String, blobString: String): Boolean {
        throwReadonlyException()
    }

    override fun setLink(linkName: String, target: Entity?): Boolean {
        throwReadonlyException()
    }

    override fun addLink(linkName: String, target: Entity): Boolean {
        throwReadonlyException()
    }

    override fun deleteProperty(propertyName: String): Boolean {
        throwReadonlyException()
    }

    override fun deleteBlob(blobName: String): Boolean {
        throwReadonlyException()
    }

    override fun deleteLink(linkName: String, target: Entity): Boolean {
        throwReadonlyException()
    }

    override fun deleteStringBlob(blobName: String): Boolean {
        throwReadonlyException()
    }

    override fun deleteLinks(linkName: String) {
        throwReadonlyException()
    }
    //endregion

    override fun getLink(linkName: String): Entity? {
        val change = changedLinks[linkName]
        if (change != null) {
            change.removedEntities?.firstOrNull()
                ?.let { return SnapshotEntityIterator.wrapLinkTarget(it, store) }
            change.deletedEntitiesSnapshots?.firstOrNull()
                ?.let { return it }
            return null
        }
        return entity.getLink(linkName)?.let { SnapshotEntityIterator.wrapLinkTarget(it, store) }
    }

    override fun getLink(linkName: String, session: TransientStoreSession?): Entity? {
        return getLink(linkName)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getLinks(linkName: String): EntityIterable {
        //this will definitely fail in case of concurrent modification
        // we get the current state and revert changes that have happened during the transaction
        // Wrap each linked entity as a *live* (mutable) transient entity rather than a read-only
        // one: the read-only contract protects the snapshot entity itself, not the current
        // entities reached by navigating its links. Upstream Xodus DNQ returns live, deletable
        // entities here (a read-only iterable of live elements), and callers rely on this to
        // modify or delete entities reached by navigating a snapshot's links. Wrapping them
        // read-only broke that with "Entity is readonly".
        val session = threadSessionOrThrow
        val oldLinksState = entity
            .getLinks(linkName)
            .map { session.newEntity(it) }
            .toSet()
            .plus(changedLinks[linkName]?.deletedEntitiesSnapshots ?: setOf())
            .plus(getRemovedLinks(linkName))
            .minus(getAddedLinks(linkName))
        return (oldLinksState as Set<TransientEntity>).asEntityIterable()
    }

    override fun getProperty(propertyName: String): Comparable<*>? {
        return if (changedProperties.containsKey(propertyName)) {
            val oldValue = changedProperties[propertyName]
            // A blob change is recorded with an internal marker (NULL_BLOB / NOT_NULL_BLOB), not a
            // scalar value. The marker must never escape as a property value: a blob is not a scalar
            // property, so its scalar old-value is null. This matches Xodus DNQ, where the snapshot
            // entity reads real persistent values and never yields a blob marker from getProperty.
            if (oldValue.isBlobMarker()) null else oldValue
        } else {
            entity.getProperty(propertyName)
        }
    }

    override fun getBlobString(blobName: String): String? {
        return if (changedProperties.containsKey(blobName)) {
            val oldValue = changedProperties[blobName]
            // For a blob recorded via a marker the real pre-transaction content is not stored inline;
            // recover it from the on-load value instead of returning the marker's toString() (which
            // would be the debug text "Empty Binary Data" / "Binary Data"). For the setBlobString
            // path the recorded value is the actual old string, so return it directly.
            if (oldValue.isBlobMarker()) {
                originalValuesProvider.getOriginalBlobStringValue(this, blobName)
            } else {
                oldValue?.toString()
            }
        } else {
            entity.getBlobString(blobName)
        }
    }

    // The blob-change markers are an internal representation and must not leak through the read API.
    private fun Comparable<*>?.isBlobMarker(): Boolean =
        this === TransientEntitiesUpdaterImpl.NULL_BLOB || this === TransientEntitiesUpdaterImpl.NOT_NULL_BLOB

    override fun getPropertyOldValue(propertyName: String): Comparable<*>? {
        return getProperty(propertyName)
    }

    override fun getBlob(blobName: String): InputStream? {
        return originalValuesProvider.getOriginalBlobValue(this, blobName)
    }

    override fun getLinks(linkNames: Collection<String>): EntityIterable {
        throw UnsupportedOperationException()
    }

    override fun hasChanges() = hasChanges

    override fun hasChanges(property: String): Boolean {
        return super.hasChanges(property)
                || (changedLinks[property]?.isNotEmpty() ?: false)
                || (property in changedProperties)
    }

    override fun hasChangesExcepting(properties: Array<String>): Boolean {
        return super.hasChangesExcepting(properties)
                || changedLinks.size > properties.size // by Dirichlet principle, even if 'properties' param is malformed
                || (changedLinks.keys - properties).isNotEmpty()
                || changedProperties.size > properties.size // by Dirichlet principle, even if 'properties' param is malformed
                || (changedProperties - properties).isNotEmpty()
    }

    override fun getAddedLinks(name: String): EntityIterable {
        return changedLinks[name]?.addedEntities.asEntityIterable()
    }

    override fun getRemovedLinks(name: String): EntityIterable {
        return changedLinks[name]?.removedEntities.asEntityIterable()
    }

    private fun Set<TransientEntity>?.asEntityIterable(): EntityIterable {
        return if (this != null && this.isNotEmpty()) {
            object : TransientEntityIterable(this@asEntityIterable) {
                override fun size() = this@asEntityIterable.size.toLong()
                override fun count() = this@asEntityIterable.size.toLong()
            }
        } else {
            YTDBEntityIterable.EMPTY
        }
    }

    override fun getAddedLinks(linkNames: Set<String>): EntityIterable {
        return if (changedLinks.isNotEmpty()) {
            AddedOrRemovedLinksFromSetTransientEntityIterable.get(changedLinks, linkNames, removed = false)
        } else {
            YTDBEntityIterable.EMPTY
        }
    }

    override fun getRemovedLinks(linkNames: Set<String>): EntityIterable {
        return if (changedLinks.isNotEmpty()) {
            AddedOrRemovedLinksFromSetTransientEntityIterable.get(changedLinks, linkNames, removed = true)
        } else {
            YTDBEntityIterable.EMPTY
        }
    }

}

private fun throwReadonlyException(): Nothing = throw IllegalStateException("Entity is readonly")

