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
import jetbrains.exodus.database.LinkChangeType
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.entitystore.orientdb.OEntity
import java.io.File
import java.io.InputStream

open class RemovedTransientEntity(
    private val transientEntity: TransientEntity
) : TransientEntity {

    val originalValuesProvider get() = transientEntity.threadSessionOrThrow.originalValuesProvider

    private val _id = transientEntity.id

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
        get() = transientEntity.entity

    override val incomingLinks: List<Pair<String, EntityIterable>>
        get() = throw IllegalStateException("Entity is removed")

    override val debugPresentation: String
        get() = "Removed $id"

    override val parent: Entity?
        get() = null

    override fun getStore(): TransientEntityStore {
        return transientEntity.store
    }

    override fun resetIfNew() {
    }

    override fun generateIdIfNew() {
    }

    override fun getLinksSize(linkName: String): Long {
        throw IllegalStateException("Entity is removed")
    }

    //region illegal actions

    override fun hasChanges() = false

    override fun getRawProperty(propertyName: String): ByteIterable? {
        return null
    }

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

    override fun delete(): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun setProperty(propertyName: String, value: Comparable<Nothing>): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun deleteProperty(propertyName: String): Boolean {
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

    override fun addLink(linkName: String, target: Entity): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun addLink(linkName: String, targetId: EntityId): Boolean {
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

    override fun setLink(linkName: String, target: Entity?): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    override fun setLink(linkName: String, targetId: EntityId): Boolean {
        throw IllegalStateException("Entity is removed")
    }

    //endregion

    override fun compareTo(other: Entity): Int {
        return this.id.compareTo(other.id)
    }

    override fun getId(): EntityId {
        return _id
    }

    override fun toIdString(): String {
        return id.toString()
    }

    override fun getType() = transientEntity.type

    //region simple unwrapping


    //endregion

    override fun getProperty(propertyName: String): Comparable<*>? {
        return entity.getProperty(propertyName)
    }

    override fun getPropertyNames(): List<String> {
        return entity.propertyNames
    }

    override fun getBlob(blobName: String): InputStream? {
        return entity.getBlob(blobName)
    }

    override fun getBlobSize(blobName: String): Long {
        return entity.getBlobSize(blobName)
    }

    override fun getBlobString(blobName: String): String? {
        return entity.getBlobString(blobName)
    }

    override fun getBlobNames(): List<String> {
        return entity.blobNames
    }

    override fun getLink(linkName: String): Entity? {
        val linkValue = originalValuesProvider.getOriginalLinkValue(this, linkName)
        return linkValue
    }

    override fun getLinks(linkName: String): EntityIterable {
        val tx = threadSessionOrThrow as TransientSessionImpl
        val tracker = threadSessionOrThrow.transientChangesTracker
        val change = tracker.getChangedLinksDetailed(transientEntity)?.get(linkName)

        if (change != null && change.changeType in listOf(LinkChangeType.ADD_AND_REMOVE, LinkChangeType.REMOVE)) {
            val removedLinks = (change.removedEntities ?: setOf()).union(change.deletedEntities ?: setOf())
            return RemovedLinksEntityIterable(
                removedLinks,
                tx
            )
        }

        return EntityIterableBase.EMPTY
    }

    override fun getLinks(linkNames: MutableCollection<String>): EntityIterable {
        throw IllegalStateException("Entity is removed")
    }

    override fun getLinkNames(): MutableList<String> {
        throw IllegalStateException("Entity is removed")
    }

    override fun equals(other: Any?) = when {
        other === this -> true
        other !is TransientEntity -> false
        else -> id == other.id && store === other.store
    }

    override fun hashCode(): Int {
        return id.hashCode() + store.persistentStore.hashCode()
    }
}

internal class RemovedLinksEntityIterable(
    private val entities: Set<TransientEntity>,
    private val txn: StoreTransaction
) : EntityIterable {

    override fun iterator(): EntityIterator {
        val wrappedIterator = entities.iterator()
        return object : EntityIterator {
            override fun remove() {
                throw IllegalStateException("Must not be called")
            }

            override fun hasNext() = wrappedIterator.hasNext()

            override fun next() = wrappedIterator.next()

            override fun skip(number: Int): Boolean {
                repeat(number) {
                    if (wrappedIterator.hasNext()) {
                        wrappedIterator.next()
                    } else {
                        return false
                    }
                }
                return hasNext()
            }

            override fun nextId(): EntityId? {
                throw IllegalStateException("Must not be called")
            }

            override fun dispose(): Boolean {
                throw IllegalStateException("Must not be called")
            }

            override fun shouldBeDisposed(): Boolean {
                throw IllegalStateException("Must not be called")
            }

        }
    }

    override fun getTransaction(): StoreTransaction {
        return txn
    }

    override fun isEmpty() = entities.isEmpty()

    override fun size() = entities.size.toLong()
    override fun count() = size()

    override fun getRoughCount() = size()

    override fun getRoughSize() = size()

    override fun indexOf(entity: Entity): Int {
        throw IllegalStateException("Must not be called")
    }

    override fun contains(entity: Entity) = entities.any { it.id == entity.id }

    override fun intersect(right: EntityIterable): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun union(right: EntityIterable): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun minus(right: EntityIterable): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun concat(right: EntityIterable): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun skip(number: Int): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun take(number: Int): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun distinct(): EntityIterable {
        return this
    }

    override fun selectDistinct(linkName: String): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun selectManyDistinct(linkName: String): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun getFirst(): Entity {
        return entities.first()
    }

    override fun getLast(): Entity {
        return entities.last()
    }

    override fun reverse(): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun isSortResult(): Boolean {
        return false
    }

    override fun asSortResult(): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

    override fun unwrap(): EntityIterable {
        throw IllegalStateException("Must not be called")
    }

}

