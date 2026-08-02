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

import jetbrains.exodus.core.dataStructures.decorators.QueueDecorator
import jetbrains.exodus.database.TransientEntitiesUpdater
import jetbrains.exodus.database.TransientEntity
import mu.KLogging
import java.io.File
import java.io.InputStream


class TransientEntitiesUpdaterImpl(
    private val session: TransientSessionImpl,
) : TransientEntitiesUpdater {

    companion object : KLogging() {
        val NULL_BLOB = object : Comparable<Any> {
            override fun compareTo(other: Any): Int {
                return -1
            }

            override fun toString() = "Empty Binary Data"
        }
        val NOT_NULL_BLOB = object : Comparable<Any> {
            override fun compareTo(other: Any): Int {
                return -1
            }
            override fun toString() = "Binary Data"
        }
    }

    private val changes = QueueDecorator<() -> Boolean>()
    private var replayInProgress = false

    // we keep this collection to be able to call TransientChangesTracker.entityBeforeRemoved() during
    // transaction replay. This is crucial for generating correct REMOVED events with
    // correctly initialized links.
    private val deletedEntities = mutableListOf<TransientEntity>()

    private val transientChangesTracker get() = session.transientChangesTracker
    private val originalValuesProvider get() = session.originalValuesProvider

    override fun setBlob(transientEntity: TransientEntity, blobName: String, stream: InputStream) {
        // A queued change must be re-runnable: `TransientSessionImpl.flush` replays the whole queue
        // when it loses an MVCC race (NeedRetryException) or hits a DataIntegrityViolationException.
        // An InputStream is single-use, so capturing it by reference made the replay store an EMPTY
        // blob (the second `readAllBytes()` on the drained stream yields 0 bytes) — silent data loss.
        // Materialize once and hand each invocation a fresh stream. No extra peak memory: the
        // persistent layer (YTDBVertexEntity.setBlob) already does `blob.readAllBytes()` anyway.
        // The `File` overload below is replay-safe for the same reason: it re-opens the file per run.
        val content = stream.readBytes()
        addChangeAndRun {
            //this may be implemented another way. by checking if the blob is actually null
            val hasBlob = transientEntity.blobNames.contains(blobName)
            transientEntity.entity.setBlob(blobName, content.inputStream())
            transientChangesTracker.propertyChanged(transientEntity, blobName, if (hasBlob) NOT_NULL_BLOB else NULL_BLOB)
            true
        }
    }

    override fun setBlob(transientEntity: TransientEntity, blobName: String, file: File) {
        addChangeAndRun {
            //this may be implemented another way. by checking if the blob is actually null
            val hasBlob = transientEntity.blobNames.contains(blobName)
            transientEntity.entity.setBlob(blobName, file)
            transientChangesTracker.propertyChanged(transientEntity, blobName, if (hasBlob) NOT_NULL_BLOB else NULL_BLOB)
            true
        }
    }

    override fun setProperty(
        transientEntity: TransientEntity,
        propertyName: String,
        propertyNewValue: Comparable<*>
    ): Boolean {
        return addChangeAndRun { setPropertyInternal(transientEntity, propertyName, propertyNewValue) }
    }

    @Suppress("SuspiciousEqualsCombination")
    private fun setPropertyInternal(
        transientEntity: TransientEntity,
        propertyName: String,
        propertyNewValue: Comparable<*>
    ): Boolean {
        val currentValue = transientEntity.getProperty(propertyName)
        val oldValue = if (transientChangesTracker.hasPropertyChanges(transientEntity, propertyName)) {
            transientChangesTracker.getPropertyOldValue(transientEntity, propertyName)
        } else {
            if (currentValue is Set<*>){
                session.originalValuesProvider.getOriginalPropertyValue(transientEntity, propertyName)
            } else {
                currentValue
            }
        }
        return if (transientEntity.entity.setProperty(propertyName, propertyNewValue)) {
            @Suppress("SuspiciousEqualsCombination")
            if (propertyNewValue === oldValue || propertyNewValue == oldValue) {
                transientChangesTracker.removePropertyChanged(transientEntity, propertyName)
            } else {
                transientChangesTracker.propertyChanged(transientEntity, propertyName, oldValue)
            }
            true
        } else {
            false
        }
    }

    override fun deleteProperty(transientEntity: TransientEntity, propertyName: String): Boolean {
        return addChangeAndRun { deletePropertyInternal(transientEntity, propertyName) }
    }

    private fun deletePropertyInternal(transientEntity: TransientEntity, propertyName: String): Boolean {
        val currentValue = transientEntity.getProperty(propertyName)
        return if (transientEntity.entity.deleteProperty(propertyName)) {
            val oldValue = if (transientChangesTracker.hasPropertyChanges(transientEntity, propertyName)) {
                transientChangesTracker.getPropertyOldValue(transientEntity, propertyName)
            } else {
                currentValue
            }
            if (oldValue == null) {
                transientChangesTracker.removePropertyChanged(transientEntity, propertyName)
            } else {
                transientChangesTracker.propertyChanged(transientEntity, propertyName, oldValue)
            }
            true
        } else {
            false
        }
    }

    @Suppress("SuspiciousEqualsCombination")
    override fun setBlobString(transientEntity: TransientEntity, blobName: String, newValue: String?): Boolean {
        return addChangeAndRun {
            val currentValue = transientEntity.getBlobString(blobName)
            val oldValue = if (transientChangesTracker.hasPropertyChanges(transientEntity, blobName)) {
                transientChangesTracker.getPropertyOldValue(transientEntity, blobName)
            } else {
                currentValue
            }
            if (newValue == null) {
                transientEntity.entity.deleteBlob(blobName)
            } else {
                transientEntity.entity.setBlobString(blobName, newValue)
            }
            if (newValue === oldValue || newValue == oldValue) {
                transientChangesTracker.removePropertyChanged(transientEntity, blobName)
            } else {
                transientChangesTracker.propertyChanged(transientEntity, blobName, oldValue)
            }
            true
        }
    }

    override fun deleteBlob(transientEntity: TransientEntity, blobName: String): Boolean {
        return addChangeAndRun {
            val isNullOnTransactionStart = if (transientChangesTracker.hasPropertyChanges(transientEntity, blobName)) {
                // A blob that was absent at transaction start can be recorded either as the NULL_BLOB
                // marker (setBlob/deleteBlob paths) or as a plain null (setBlobString path, which stores
                // the real scalar old value). Recognize both, otherwise a null -> "" -> delete sequence
                // (e.g. clearing an empty summary/description) leaves a spurious property change and bumps
                // the issue's updated date. Matches Xodus, which nets such a sequence to no-change.
                val oldValue = transientChangesTracker.getPropertyOldValue(transientEntity, blobName)
                oldValue == NULL_BLOB || oldValue == null
            } else {
                transientEntity.getBlob(blobName) == null
            }
            if (transientEntity.entity.deleteBlob(blobName)) {
                if (isNullOnTransactionStart) {
                    transientChangesTracker.removePropertyChanged(transientEntity, blobName)
                } else {
                    transientChangesTracker.propertyChanged(transientEntity, blobName, NULL_BLOB)
                }
                true
            } else {
                false
            }
        }
    }

    override fun setLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return addChangeAndRun { setLinkInternal(source, linkName, target) }
    }

    private fun setLinkInternal(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        val oldTarget = source.getLink(linkName) as TransientEntity?
        assertLinkTypeIsSupported(source, linkName, target)
        return if (source.entity.setLink(linkName, target.entity)) {
            transientChangesTracker.linkChanged(source, linkName, target, oldTarget, true)
            true
        } else {
            false
        }
    }

    override fun addLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return addChangeAndRun { addLinkInternal(source, linkName, target) }
    }

    private fun addLinkInternal(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        assertLinkTypeIsSupported(source, linkName, target)
        return if (source.entity.addLink(linkName, target.entity)) {
            transientChangesTracker.linkChanged(source, linkName, target, null, true)
            true
        } else {
            false
        }
    }

    private fun assertLinkTypeIsSupported(source: TransientEntity, linkName: String, target: TransientEntity) {
        session.store.modelMetaData?.let {
            val linkMetaData = it.getEntityMetaData(source.type)?.getAssociationEndMetaData(linkName)
            if (linkMetaData != null) {
                val subTypes = linkMetaData.oppositeEntityMetaData.allSubTypes
                val ownType = linkMetaData.oppositeEntityMetaData.type
                if (target.type != ownType && !subTypes.contains(target.type)) {
                    val allowed = (subTypes + ownType).joinToString()
                    throw IllegalStateException("'${source.type}.$linkName' can contain only '$allowed' types. '${target.type}' type is not supported.")
                }
            }
        }
    }

    override fun deleteLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return addChangeAndRun { deleteLinkInternal(source, linkName, target) }
    }

    private fun deleteLinkInternal(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return if (source.entity.deleteLink(linkName, target.entity)) {
            transientChangesTracker.linkChanged(source, linkName, target, null, false)
            true
        } else {
            false
        }
    }

    override fun deleteLinks(source: TransientEntity, linkName: String) {
        addChangeAndRun {
            transientChangesTracker.linksRemoved(source, linkName, source.getLinks(linkName))
            source.entity.deleteLinks(linkName)
            true
        }
    }

    override fun deleteEntity(transientEntity: TransientEntity): Boolean {
        deletedEntities.add(transientEntity)
        return addChangeAndRun { deleteEntityInternal(transientEntity) }
    }

    internal fun deleteEntityInternal(e: TransientEntity): Boolean {
        transientChangesTracker.entityRemoved(e)
        return true
    }

    override fun setToOne(source: TransientEntity, linkName: String, target: TransientEntity?) {
        addChangeAndRun {
            if (target == null) {
                val oldTarget = source.getLink(linkName) as TransientEntity?
                if (oldTarget != null) {
                    deleteLinkInternal(source, linkName, oldTarget)
                }
            } else {
                setLinkInternal(source, linkName, target)
            }
            true
        }
    }

    override fun setManyToOne(
        many: TransientEntity,
        manyToOneLinkName: String,
        oneToManyLinkName: String,
        one: TransientEntity?
    ) {
        addChangeAndRun {
            val m = session.newLocalCopySafe(many)
            if (m != null) {
                val o = session.newLocalCopySafe(one)
                val oldOne = m.getLink(manyToOneLinkName) as TransientEntity?
                if (oldOne != null) {
                    deleteLinkInternal(oldOne, oneToManyLinkName, m)
                    if (o == null) {
                        deleteLinkInternal(m, manyToOneLinkName, oldOne)
                    }
                }
                if (o != null) {
                    addLinkInternal(o, oneToManyLinkName, m)
                    setLinkInternal(m, manyToOneLinkName, o)
                }
            }
            true
        }
    }

    override fun clearOneToMany(one: TransientEntity, manyToOneLinkName: String, oneToManyLinkName: String) {
        addChangeAndRun {
            for (target in one.getLinks(oneToManyLinkName)) {
                val many = target as TransientEntity
                deleteLinkInternal(one, oneToManyLinkName, many)
                deleteLinkInternal(many, manyToOneLinkName, one)
            }
            true
        }
    }

    override fun createManyToMany(
        e1: TransientEntity,
        e1Toe2LinkName: String,
        e2Toe1LinkName: String,
        e2: TransientEntity
    ) {
        addChangeAndRun {
            addLinkInternal(e1, e1Toe2LinkName, e2)
            addLinkInternal(e2, e2Toe1LinkName, e1)
            true
        }
    }

    override fun clearManyToMany(e1: TransientEntity, e1Toe2LinkName: String, e2Toe1LinkName: String) {
        addChangeAndRun {
            for (target in e1.getLinks(e1Toe2LinkName)) {
                val e2 = target as TransientEntity
                deleteLinkInternal(e1, e1Toe2LinkName, e2)
                deleteLinkInternal(e2, e2Toe1LinkName, e1)
            }
            true
        }
    }

    override fun setOneToOne(
        e1: TransientEntity,
        e1Toe2LinkName: String,
        e2Toe1LinkName: String,
        e2: TransientEntity?
    ) {
        addChangeAndRun {
            val prevE2 = e1.getLink(e1Toe2LinkName) as TransientEntity?
            if (prevE2 == null || prevE2 != e1) {
                if (prevE2 != null) {
                    deleteLinkInternal(prevE2, e2Toe1LinkName, e1)
                    deleteLinkInternal(e1, e1Toe2LinkName, prevE2)
                }
                if (e2 != null) {
                    val prevE1 = e2.getLink(e2Toe1LinkName) as TransientEntity?
                    if (prevE1 != null) {
                        deleteLinkInternal(prevE1, e1Toe2LinkName, e2)
                    }
                    setLinkInternal(e1, e1Toe2LinkName, e2)
                    setLinkInternal(e2, e2Toe1LinkName, e1)
                }
            }
            true
        }
    }

    override fun removeOneToMany(
        one: TransientEntity,
        manyToOneLinkName: String,
        oneToManyLinkName: String,
        many: TransientEntity
    ) {
        addChangeAndRun {
            val oldOne = many.getLink(manyToOneLinkName) as TransientEntity?
            if (one == oldOne) {
                deleteLinkInternal(many, manyToOneLinkName, oldOne)
            }
            deleteLinkInternal(one, oneToManyLinkName, many)
            true
        }
    }

    override fun removeFromParent(
        child: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String
    ) {
        addChangeAndRun {
            val parent = child.getLink(childToParentLinkName) as TransientEntity?
            if (parent != null) {
                // may be changed or removed
                removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child)
            }
            true
        }
    }

    override fun removeChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String
    ) {
        addChangeAndRun {
            val child = parent.getLink(parentToChildLinkName) as TransientEntity?
            if (child != null) {
                // may be changed or removed
                removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child)
            }
            true
        }
    }

    private fun removeChildFromParentInternal(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String?,
        child: TransientEntity
    ) {
        deleteLinkInternal(parent, parentToChildLinkName, child)
        deletePropertyInternal(child, PARENT_TO_CHILD_LINK_NAME)
        if (childToParentLinkName != null) {
            deleteLinkInternal(child, childToParentLinkName, parent)
            deletePropertyInternal(child, CHILD_TO_PARENT_LINK_NAME)
        }
    }

    override fun setChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String,
        child: TransientEntity
    ) {
        addChangeAndRun {
            if (removeChildFromCurrentParentInternal(child, childToParentLinkName, parentToChildLinkName, parent)) {
                val oldChild = parent.getLink(parentToChildLinkName) as TransientEntity?
                if (oldChild != null) {
                    removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, oldChild)
                }
                setLinkInternal(parent, parentToChildLinkName, child)
                setLinkInternal(child, childToParentLinkName, parent)
                setPropertyInternal(child, PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName)
                setPropertyInternal(child, CHILD_TO_PARENT_LINK_NAME, childToParentLinkName)
            }
            true
        }
    }

    private fun removeChildFromCurrentParentInternal(
        child: TransientEntity,
        childToParentLinkName: String,
        parentToChildLinkName: String,
        newParent: TransientEntity
    ): Boolean {
        val oldChildToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME) as String?
        if (oldChildToParentLinkName != null) {
            if (childToParentLinkName == oldChildToParentLinkName) {
                val oldParent = child.getLink(childToParentLinkName) as TransientEntity?
                if (oldParent != null) {
                    if (oldParent == newParent) {
                        return false
                    }
                    // child-to-parent link will be overwritten, so don't delete it directly
                    deleteLinkInternal(oldParent, parentToChildLinkName, child)
                }
            } else {
                val oldParent = child.getLink(oldChildToParentLinkName) as TransientEntity?
                if (oldParent != null) {
                    val oldParentToChildLinkName = child.getProperty(PARENT_TO_CHILD_LINK_NAME) as String?
                    deleteLinkInternal(oldParent, oldParentToChildLinkName ?: parentToChildLinkName, child)
                    deleteLinkInternal(child, oldChildToParentLinkName, oldParent)
                }
            }
        }
        return true
    }

    override fun clearChildren(parent: TransientEntity, parentToChildLinkName: String) {
        addChangeAndRun {
            for (child in parent.getLinks(parentToChildLinkName)) {
                val childToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME) as String?
                removeChildFromParentInternal(
                    parent,
                    parentToChildLinkName,
                    childToParentLinkName,
                    child as TransientEntity
                )
            }
            true
        }
    }

    override fun addChild(
        parent: TransientEntity,
        parentToChildLinkName: String,
        childToParentLinkName: String,
        child: TransientEntity
    ) {
        addChangeAndRun {
            if (removeChildFromCurrentParentInternal(child, childToParentLinkName, parentToChildLinkName, parent)) {
                addLinkInternal(parent, parentToChildLinkName, child)
                setLinkInternal(child, childToParentLinkName, parent)
                setPropertyInternal(child, PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName)
                setPropertyInternal(child, CHILD_TO_PARENT_LINK_NAME, childToParentLinkName)
            }
            true
        }
    }

    override fun addChangeAndRun(change: () -> Boolean): Boolean {
        session.upgradeReadonlyTransactionIfNecessary()
        return addChange(change).invoke()
    }

    override fun addChange(change: () -> Boolean): () -> Boolean {
        if (session.allowRunnables) {
            if (replayInProgress) {
                throw IllegalStateException("Changes can't be added during changes replay")
            }
            changes.offer(change)
        }
        return change
    }

    override fun hasChanges() = changes.isNotEmpty()

    override fun clear() {
        if (replayInProgress) {
            throw IllegalStateException("Changes can't be cleared during changes replay")
        }
        changes.clear()
        deletedEntities.clear()
    }

    override fun getDeletedEntities(): List<TransientEntity> = deletedEntities

    override fun replayChanges() {
        if (replayInProgress) {
            throw IllegalStateException("Replay already in progress");
        }
        replayInProgress = true
        try {
            changes.forEach { it() }
        } finally {
            replayInProgress = false
        }
    }
}
