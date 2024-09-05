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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.entitystore.orientdb.OEntity
import jetbrains.exodus.entitystore.orientdb.OEntityId
import jetbrains.exodus.entitystore.orientdb.OVertexEntity
import jetbrains.exodus.entitystore.orientdb.asReadonly
import java.io.File
import java.io.InputStream

class ReadonlyTransientEntityImpl(change: TransientEntityChange?, snapshot: OEntity, store: TransientEntityStore) : TransientEntityImpl(snapshot, store) {

    constructor(snapshot: OEntity, store: TransientEntityStore) : this(null, snapshot, store)

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

    override fun deleteLinks(linkName: String) {
        throwReadonlyException()
    }
    //endregion

    override fun getLink(linkName: String): Entity? {
        return originalValuesProvider.getOriginalLinkValue(this, linkName)
    }

    override fun getLink(linkName: String, session: TransientStoreSession?): Entity? {
        return getLink(linkName)
    }

    override fun getLinks(linkName: String): EntityIterable {
        //this will definitely fail in case of concurrent modification
        // we get the current state and revert changes that have happened during the transaction
        val oldLinksState = entity
            .getLinks(linkName)
            .map { ReadonlyTransientEntityImpl(null, it as OEntity ,store) }
            .toSet()
            .plus(getRemovedLinks(linkName))
            .minus(getAddedLinks(linkName))
        return (oldLinksState as Set<TransientEntity>).asEntityIterable()
    }

    override fun getProperty(propertyName: String): Comparable<*>? {
        return originalValuesProvider.getOriginalPropertyValue(this, propertyName)
    }

    override fun getBlobString(blobName: String): String? {
        return originalValuesProvider.getOriginalBlobStringValue(this, blobName)
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
            EntityIterableBase.EMPTY
        }
    }

    override fun getAddedLinks(linkNames: Set<String>): EntityIterable {
        return if (changedLinks.isNotEmpty()) {
            AddedOrRemovedLinksFromSetTransientEntityIterable.get(changedLinks, linkNames, removed = false)
        } else {
            UniversalEmptyEntityIterable
        }
    }

    override fun getRemovedLinks(linkNames: Set<String>): EntityIterable {
        return if (changedLinks.isNotEmpty()) {
            AddedOrRemovedLinksFromSetTransientEntityIterable.get(changedLinks, linkNames, removed = true)
        } else {
            UniversalEmptyEntityIterable
        }
    }

}

private fun throwReadonlyException(): Nothing = throw IllegalStateException("Entity is readonly")

