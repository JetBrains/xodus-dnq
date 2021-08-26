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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.ByteIterable
import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId
import java.io.File
import java.io.InputStream

/**
 * @author Vadim.Gurov
 */
open class TransientEntityImpl : TransientEntity {
    private val store: TransientEntityStore
    private var persistentEntityId: PersistentEntityId? = null
    private var readOnlyPersistentEntity: ReadOnlyPersistentEntity? = null
    private val entityType: String by lazy(LazyThreadSafetyMode.NONE) { persistentEntity.type }

    override var persistentEntity: PersistentEntity
        get() = readOnlyPersistentEntity
                ?: persistentEntityId?.let { (persistentStore as PersistentEntityStoreImpl).getEntity(it) }
                ?: throwWrappedPersistentEntityUndefined()
        set(persistentEntity) {
            if (persistentEntity is ReadOnlyPersistentEntity) {
                persistentEntityId = null
                readOnlyPersistentEntity = persistentEntity
            } else {
                persistentEntityId = persistentEntity.id
                readOnlyPersistentEntity = null
            }
        }

    private val threadSessionOrThrow: TransientSessionImpl
        get() = store.threadSessionOrThrow as TransientSessionImpl

    override val isNew: Boolean
        get() = threadSessionOrThrow.transientChangesTracker.isNew(this)

    override val isSaved: Boolean
        get() = threadSessionOrThrow.transientChangesTracker.isSaved(this)

    override val isRemoved: Boolean
        get() = threadSessionOrThrow.transientChangesTracker.isRemoved(this)

    override val isReadonly: Boolean
        get() = false

    override val isWrapper: Boolean
        get() = false

    /**
     * Called by BasePersistentClassImpl by default
     *
     * @return debug presentation
     */
    override val debugPresentation: String
        get() = persistentEntity.toString()

    override val incomingLinks: List<Pair<String, EntityIterable>>
        get() {
            val session = threadSessionOrThrow
            return session.store.modelMetaData?.let { modelMetaData ->
                modelMetaData.getEntityMetaData(type)
                        ?.getIncomingAssociations(modelMetaData)
                        ?.asSequence()
                        ?.flatMap { (entityType, linkNames) ->
                            linkNames.asSequence().map { linkName ->
                                linkName to session.findLinks(entityType, this, linkName)
                            }
                        }
            }?.toList().orEmpty()
        }

    override val parent: Entity?
        get() = threadSessionOrThrow.getParent(this)

    private val persistentStore: EntityStore
        get() = store.persistentStore

    internal constructor(type: String, store: TransientEntityStore) {
        this.store = store
        threadSessionOrThrow.createEntity(this, type)
    }

    internal constructor(creator: EntityCreator, store: TransientEntityStore) {
        this.store = store
        threadSessionOrThrow.createEntity(this, creator)
    }

    internal constructor(persistentEntity: PersistentEntity, store: TransientEntityStore) {
        this.store = store
        this.persistentEntity = persistentEntity
    }

    override fun getStore() = store

    override fun getType() = entityType

    override fun getId(): EntityId {
        return readOnlyPersistentEntity?.id
                ?: persistentEntityId
                ?: throwWrappedPersistentEntityUndefined()
    }

    override fun toIdString() = id.toString()

    override fun getPropertyNames(): List<String> = persistentEntity.propertyNames

    override fun getBlobNames(): List<String> = persistentEntity.blobNames

    override fun getLinkNames(): List<String> = persistentEntity.linkNames

    override fun compareTo(other: Entity): Int {
        return persistentEntity.compareTo(other)
    }

    override fun toString() = debugPresentation

    override fun equals(other: Any?) = when {
        other === this -> true
        other !is TransientEntity -> false
        else -> id == other.id && store === other.store
    }

    override fun hashCode() = id.hashCode() + persistentStore.hashCode()

    override fun getProperty(propertyName: String): Comparable<*>? {
        return persistentEntity.getProperty(propertyName)
    }

    override fun getRawProperty(propertyName: String): ByteIterable? {
        return persistentEntity.getRawProperty(propertyName)
    }

    override fun getPropertyOldValue(propertyName: String): Comparable<*>? {
        return threadSessionOrThrow.transientChangesTracker.getPropertyOldValue(this, propertyName)
    }

    override fun setProperty(propertyName: String, value: Comparable<*>): Boolean {
        return threadSessionOrThrow.setProperty(this, propertyName, value)
    }

    override fun deleteProperty(propertyName: String): Boolean {
        return threadSessionOrThrow.deleteProperty(this, propertyName)
    }

    override fun getBlob(blobName: String): InputStream? {
        return persistentEntity.getBlob(blobName)
    }

    override fun getBlobSize(blobName: String): Long {
        return persistentEntity.getBlobSize(blobName)
    }

    override fun setBlob(blobName: String, blob: InputStream) {
        threadSessionOrThrow.setBlob(this, blobName, blob)
    }

    override fun setBlob(blobName: String, file: File) {
        threadSessionOrThrow.setBlob(this, blobName, file)
    }

    override fun setBlobString(blobName: String, blobString: String): Boolean {
        return threadSessionOrThrow.setBlobString(this, blobName, blobString)
    }

    override fun deleteBlob(blobName: String): Boolean {
        return threadSessionOrThrow.deleteBlob(this, blobName)
    }

    override fun getBlobString(blobName: String): String? {
        return persistentEntity.getBlobString(blobName)
    }

    override fun setLink(linkName: String, target: Entity?): Boolean {
        if (target == null) return false
        return threadSessionOrThrow.setLink(this, linkName, target as TransientEntity)
    }

    override fun setLink(linkName: String, targetId: EntityId): Boolean {
        return threadSessionOrThrow.run { setLink(this@TransientEntityImpl, linkName, getEntity(targetId) as TransientEntity) }
    }

    private fun assertIsMultipleLink(entity: Entity, linkName: String) {
        val associationEndMetaData = store.modelMetaData
                ?.getEntityMetaData(entity.type)
                ?.getAssociationEndMetaData(linkName)
        if (associationEndMetaData != null && !associationEndMetaData.cardinality.isMultiple) {
            throw IllegalArgumentException("Can not call this operation for non-multiple association [$linkName] of [$entity]")
        }
    }

    override fun addLink(linkName: String, target: Entity): Boolean {
        assertIsMultipleLink(this, linkName)
        return threadSessionOrThrow.addLink(this, linkName, target as TransientEntity)
    }

    override fun addLink(linkName: String, targetId: EntityId): Boolean {
        assertIsMultipleLink(this, linkName)
        return threadSessionOrThrow.run { addLink(this@TransientEntityImpl, linkName, getEntity(targetId) as TransientEntity) }
    }

    override fun deleteLink(linkName: String, target: Entity): Boolean {
        return threadSessionOrThrow.deleteLink(this, linkName, target as TransientEntity)
    }

    override fun deleteLink(linkName: String, targetId: EntityId): Boolean {
        return threadSessionOrThrow.run { deleteLink(this@TransientEntityImpl, linkName, getEntity(targetId) as TransientEntity) }
    }

    override fun deleteLinks(linkName: String) {
        threadSessionOrThrow.deleteLinks(this, linkName)
    }

    override fun getLinks(linkName: String): EntityIterable {
        return PersistentEntityIterableWrapper(store, persistentEntity.getLinks(linkName))
    }

    override fun getLink(linkName: String): Entity? = getLink(linkName, null)

    open fun getLink(linkName: String, session: TransientStoreSession? = null): Entity? {
        val link = this.persistentEntity.getLink(linkName) ?: return null
        val s = session ?: store.threadSessionOrThrow
        return s.newEntity(link).takeUnless { s.transientChangesTracker.isRemoved(it) }
    }

    override fun getLinks(linkNames: Collection<String>): EntityIterable {
        return object : PersistentEntityIterableWrapper(store, persistentEntity.getLinks(linkNames)) {
            override fun iterator() = PersistentEntityIteratorWithPropIdWrapper(
                    wrappedIterable.iterator() as EntityIteratorWithPropId,
                    store.threadSessionOrThrow
            )
        }
    }

    override fun getLinksSize(linkName: String): Long {
        // TODO: slow method
        return persistentEntity.getLinks(linkName).size()
    }

    override fun delete(): Boolean {
        threadSessionOrThrow.deleteEntity(this)
        return true
    }

    override fun hasChanges(): Boolean {
        if (isNew) return true
        return threadSessionOrThrow.transientChangesTracker.hasChanges(this)
    }

    override fun hasChanges(property: String): Boolean {
        return threadSessionOrThrow.transientChangesTracker.run {
            hasPropertyChanges(this@TransientEntityImpl, property) ||
                    hasLinkChanges(this@TransientEntityImpl, property)
        }
    }

    override fun hasChangesExcepting(properties: Array<String>): Boolean {
        val session = threadSessionOrThrow
        val changedLinks = session.transientChangesTracker.getChangedLinksDetailed(this).orEmpty()
        val changedProperties = session.transientChangesTracker.getChangedProperties(this).orEmpty()

        return if (changedLinks.isEmpty() && changedProperties.isEmpty()) {
            false
        } else {
            // all properties have to be changed
            val exceptedChanges = properties.count { it in changedLinks || it in changedProperties }
            val totalChanges = changedLinks.size + changedProperties.size
            totalChanges > exceptedChanges
        }
    }

    private fun getAddedRemovedLinks(name: String, removed: Boolean): EntityIterable {
        if (isNew) return EntityIterableBase.EMPTY

        return threadSessionOrThrow.transientChangesTracker
                .getChangedLinksDetailed(this)
                ?.get(name)
                ?.let { linkChange ->
                    if (removed) {
                        concat(getRemovedWrapper(linkChange), getDeletedWrapper(linkChange))
                    } else {
                        getAddedWrapper(linkChange)
                    }
                }
                ?: EntityIterableBase.EMPTY
    }

    private fun concat(left: TransientEntityIterable?, right: TransientEntityIterable?) =
            when {
                left != null && right != null -> left.concat(right)
                left != null && right == null -> left
                left == null && right != null -> right
                else -> null
            }

    private fun getAddedWrapper(change: LinkChange): TransientEntityIterable? {
        val addedEntities = change.addedEntities ?: return null
        return object : TransientEntityIterable(addedEntities) {
            override fun size() = change.addedEntitiesSize.toLong()
            override fun count() = change.addedEntitiesSize.toLong()
        }
    }

    private fun getRemovedWrapper(change: LinkChange): TransientEntityIterable? {
        val removedEntities = change.removedEntities ?: return null
        return object : TransientEntityIterable(removedEntities) {
            override fun size() = change.removedEntitiesSize.toLong()
            override fun count() = change.removedEntitiesSize.toLong()
        }
    }

    private fun getDeletedWrapper(change: LinkChange): TransientEntityIterable? {
        val deletedEntities = change.deletedEntities ?: return null
        return object : TransientEntityIterable(deletedEntities) {
            override fun size() = change.deletedEntitiesSize.toLong()
            override fun count() = change.deletedEntitiesSize.toLong()
        }
    }

    override fun getAddedLinks(name: String): EntityIterable {
        return getAddedRemovedLinks(name, removed = false)
    }

    override fun getRemovedLinks(name: String): EntityIterable {
        return getAddedRemovedLinks(name, removed = true)
    }

    private fun getAddedRemovedLinks(linkNames: Set<String>, removed: Boolean): EntityIterable {
        if (isNew) return UniversalEmptyEntityIterable

        val changedLinksDetailed = threadSessionOrThrow.transientChangesTracker.getChangedLinksDetailed(this)
        return if (changedLinksDetailed != null) {
            AddedOrRemovedLinksFromSetTransientEntityIterable.get(changedLinksDetailed, linkNames, removed)
        } else {
            UniversalEmptyEntityIterable
        }
    }

    override fun getAddedLinks(linkNames: Set<String>): EntityIterable {
        return getAddedRemovedLinks(linkNames, removed = false)
    }

    override fun getRemovedLinks(linkNames: Set<String>): EntityIterable {
        return getAddedRemovedLinks(linkNames, removed = true)
    }

    override fun setToOne(linkName: String, target: Entity?) {
        threadSessionOrThrow.setToOne(this, linkName, target as TransientEntity?)
    }

    override fun setManyToOne(manyToOneLinkName: String, oneToManyLinkName: String, one: Entity?) {
        if (one != null) {
            assertIsMultipleLink(one, oneToManyLinkName)
        }
        threadSessionOrThrow.setManyToOne(this, manyToOneLinkName, oneToManyLinkName, one as TransientEntity?)
    }

    override fun clearOneToMany(manyToOneLinkName: String, oneToManyLinkName: String) {
        assertIsMultipleLink(this, oneToManyLinkName)

        threadSessionOrThrow.clearOneToMany(this, manyToOneLinkName, oneToManyLinkName)
    }

    override fun createManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity) {
        assertIsMultipleLink(this, e1Toe2LinkName)
        assertIsMultipleLink(e2, e2Toe1LinkName)

        threadSessionOrThrow.createManyToMany(this, e1Toe2LinkName, e2Toe1LinkName, e2 as TransientEntity)
    }

    override fun clearManyToMany(e1Toe2LinkName: String, e2Toe1LinkName: String) {
        assertIsMultipleLink(this, e1Toe2LinkName)

        threadSessionOrThrow.clearManyToMany(this, e1Toe2LinkName, e2Toe1LinkName)
    }

    override fun setOneToOne(e1Toe2LinkName: String, e2Toe1LinkName: String, e2: Entity?) {
        threadSessionOrThrow.setOneToOne(this, e1Toe2LinkName, e2Toe1LinkName, e2 as TransientEntity?)
    }

    override fun removeOneToMany(manyToOneLinkName: String, oneToManyLinkName: String, many: Entity) {
        threadSessionOrThrow.removeOneToMany(this, manyToOneLinkName, oneToManyLinkName, many as TransientEntity)
    }

    override fun removeFromParent(parentToChildLinkName: String, childToParentLinkName: String) {
        threadSessionOrThrow.removeFromParent(this, parentToChildLinkName, childToParentLinkName)
    }

    override fun removeChild(parentToChildLinkName: String, childToParentLinkName: String) {
        threadSessionOrThrow.removeChild(this, parentToChildLinkName, childToParentLinkName)
    }

    override fun setChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        threadSessionOrThrow.setChild(this, parentToChildLinkName, childToParentLinkName, child as TransientEntity)
    }

    override fun clearChildren(parentToChildLinkName: String) {
        threadSessionOrThrow.clearChildren(this, parentToChildLinkName)
    }

    override fun addChild(parentToChildLinkName: String, childToParentLinkName: String, child: Entity) {
        threadSessionOrThrow.addChild(this, parentToChildLinkName, childToParentLinkName, child as TransientEntity)
    }

    private fun throwWrappedPersistentEntityUndefined(): Nothing = throw IllegalStateException("Cannot get wrapped persistent entity")
}
