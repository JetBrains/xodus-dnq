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

import jetbrains.exodus.ByteIterable
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.entitystore.orientdb.*
import java.io.File
import java.io.InputStream

class ReadonlyTransientEntityImpl(change: TransientEntityChange?, snapshot: OEntity, store: TransientEntityStore) :
    TransientEntityImpl(snapshot, store) {

    constructor(snapshot: OEntity, store: TransientEntityStore) : this(null, snapshot, store)

    private val hasChanges by lazy {
        changedProperties.isNotEmpty() || changedLinks.values.any { it.isNotEmpty() }
    }

    private val changedLinks = change?.changedLinksDetailed.orEmpty()
    private val changedProperties = change?.changedProperties.orEmpty()

    override val isReadonly: Boolean
        get() = true

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

    override fun getLink(linkName: String): Entity? {
        return (entity.getLink(linkName) as OVertexEntity?)
            ?.let { linkTarget ->
                ReadonlyTransientEntityImpl(linkTarget.asReadonly(), store)
            }
    }

    override fun getLink(linkName: String, session: TransientStoreSession?): Entity? {
        return getLink(linkName)
    }

    override fun getLinks(linkName: String): EntityIterable {
        return PersistentEntityIterableWrapper(store, entity.getLinks(linkName))
    }

    override fun getLinks(linkNames: Collection<String>): EntityIterable {
        throw UnsupportedOperationException()
    }

    override fun delete(): Boolean {
        throwReadonlyException()
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

open class RemovedTransientEntity(
    private val id: OEntityId,
    private val store: TransientEntityStore,
    private val type: String
) : TransientEntity {
    override val isNew: Boolean
        get() = false
    override val isSaved: Boolean
        get() = true
    override val isRemoved: Boolean
        get() = true
    override val isReadonly: Boolean
        get() = true
    override val isWrapper: Boolean
        get() = true
    override val entity: OEntity
        get() = throw IllegalStateException("Entity is removed")
    override val incomingLinks: List<Pair<String, EntityIterable>>
        get() = throw IllegalStateException("Entity is removed")
    override val debugPresentation: String
        get() = "Removed $id"
    override val parent: Entity?
        get() = null

    override fun getStore(): TransientEntityStore {
        return store
    }

    override fun getLinksSize(linkName: String): Long {
        throw IllegalStateException("Entity is removed")
    }

    override fun hasChanges() = false

    override fun hasChanges(property: String) = false

    override fun hasChangesExcepting(properties: Array<String>) = false

    override fun getAddedLinks(name: String): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun getAddedLinks(linkNames: Set<String>): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun getRemovedLinks(name: String): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun getRemovedLinks(linkNames: Set<String>): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun getPropertyOldValue(propertyName: String): Comparable<*>? {
        throw IllegalStateException("Entity is removed")
    }

    override fun setToOne(linkName: String, target: Entity?) {
        throw IllegalStateException("Entity is removed")
    }

    override fun setManyToOne(manyToOneLinkName: String, oneToManyLinkName: String, one: Entity?) {
        throw IllegalStateException("Entity is removed")
    }

    override fun clearOneToMany(manyToOneLinkName: String, oneToManyLinkName: String) {
        throw IllegalStateException("Entity is removed")
    }

    override fun createManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity) {
        throw IllegalStateException("Entity is removed")
    }

    override fun clearManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String) {
        throw IllegalStateException("Entity is removed")
    }

    override fun setOneToOne(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity?) {
        throw IllegalStateException("Entity is removed")
    }

    override fun removeOneToMany(manyToOneLinkName: String, oneToManyLinkName: String, many: Entity) {
        throw IllegalStateException("Entity is removed")
    }

    override fun removeFromParent(parentToChildLinkName: String, childToParentLinkName: String) {
        throw IllegalStateException("Entity is removed")
    }

    override fun removeChild(parentToChildLinkName: String, childToParentLinkName: String) {
        throw IllegalStateException("Entity is removed")
    }

    override fun setChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw IllegalStateException("Entity is removed")
    }

    override fun clearChildren(parentToChildLinkName: String) {
        throw IllegalStateException("Entity is removed")
    }

    override fun addChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        throw IllegalStateException("Entity is removed")
    }

    override fun compareTo(other: Entity?): Int {
        throw IllegalStateException("Entity is removed")
    }

    override fun getId(): EntityId {
        return id
    }

    override fun toIdString(): String {
        return id.toString()
    }

    override fun getType() = type


    override fun delete(): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun getRawProperty(propertyName: String): ByteIterable? {
        throw IllegalStateException("Entity is removed")
    }

    override fun getProperty(propertyName: String): Comparable<Nothing> {
        throw IllegalStateException("Entity is removed")
    }

    override fun setProperty(propertyName: String, value: Comparable<Nothing>): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun deleteProperty(propertyName: String): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun getPropertyNames(): MutableList<String> {
        throw IllegalStateException("Entity is removed")
    }

    override fun getBlob(blobName: String): InputStream? {
        throw IllegalStateException("Entity is removed")
    }

    override fun getBlobSize(blobName: String): Long {
        throw IllegalStateException("Entity is removed")
    }

    override fun getBlobString(blobName: String): String? {
        throw IllegalStateException("Entity is removed")
    }

    override fun setBlob(blobName: String, blob: InputStream) {
        throw IllegalStateException("Entity is removed")
    }

    override fun setBlob(blobName: String, file: File) {
        throw IllegalStateException("Entity is removed")
    }

    override fun setBlobString(blobName: String, blobString: String): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun deleteBlob(blobName: String): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun getBlobNames(): MutableList<String> {
        throw IllegalStateException("Entity is removed")
    }

    override fun addLink(linkName: String, target: Entity): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun addLink(linkName: String, targetId: EntityId): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun getLink(linkName: String): Entity? {
        throw IllegalStateException("Entity is removed")
    }

    override fun setLink(linkName: String, target: Entity?): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun setLink(linkName: String, targetId: EntityId): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun getLinks(linkName: String): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun getLinks(linkNames: MutableCollection<String>): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun deleteLink(linkName: String, target: Entity): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun deleteLink(linkName: String, targetId: EntityId): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun deleteLinks(linkName: String) {
        throw IllegalStateException("Entity is removed")
    }

    override fun getLinkNames(): MutableList<String> {
        throw IllegalStateException("Entity is removed")
    }

}
