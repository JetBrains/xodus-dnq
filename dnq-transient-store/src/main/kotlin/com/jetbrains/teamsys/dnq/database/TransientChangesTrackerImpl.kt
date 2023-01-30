/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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

import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator
import jetbrains.exodus.core.dataStructures.decorators.LinkedHashSetDecorator
import jetbrains.exodus.core.dataStructures.hash.HashMap
import jetbrains.exodus.core.dataStructures.hash.HashSet
import jetbrains.exodus.core.dataStructures.hash.LinkedHashSet
import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import jetbrains.exodus.entitystore.iterate.EntityIdSet
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import java.math.BigInteger

import java.util.*

/**
 * @author Vadim.Gurov
 */
class TransientChangesTrackerImpl(private var _snapshot: PersistentStoreTransaction?) : TransientChangesTracker {
    override val snapshot: PersistentStoreTransaction
        get() = _snapshot
                ?: throw IllegalStateException("Cannot get persistent store transaction because changes tracker is already disposed")

    private val _changedEntities = LinkedHashSet<TransientEntity>()
    override val changedEntities: Set<TransientEntity>
        get() = _changedEntities

    private var addedEntities = EntityIdSetFactory.newSet()

    private var removedEntities: EntityIdSet = EntityIdSetFactory.newSet()

    private val _affectedEntityTypes = HashSet<String>()
    override val affectedEntityTypes: Set<String>
        get() = Collections.unmodifiableSet(_affectedEntityTypes)

    private val removedFrom = HashMapDecorator<TransientEntity, MutableList<LinkChange>>()
    private val entityToChangedLinksDetailed = HashMapDecorator<TransientEntity, MutableMap<String, LinkChange>>()
    private val entityToChangedProperties = HashMapDecorator<TransientEntity, MutableSet<String>>()

    // do not take into consideration RemovedNew entities - such entities was created and removed during same transaction
    override val changesHash: BigInteger
        get() = changedEntities
                .filterNot { it.id in addedEntities && it.id in removedEntities }
                .sortedBy { it.id }
                .filter { e -> entityToChangedProperties[e].isNotEmpty || entityToChangedLinksDetailed[e].isNotEmpty }
                .fold(BigInteger.ONE) { hc, entity ->
                    var h = hc.applyHashCode(entity.id).applyHashCode(getEntityChangeType(entity))
                    getChangedProperties(entity)?.sorted()?.forEach { propertyName ->
                        h = h.applyHashCode(propertyName)
                    }
                    getChangedLinksDetailed(entity)?.values?.filter { it.isNotEmpty() }?.sortedBy { it.linkName }?.forEach { linkChange ->
                        h = h.applyHashCode(linkChange.changeType)
                        h = h.applyHashCode(linkChange.addedEntitiesSize)
                        h = h.applyHashCode(linkChange.removedEntitiesSize)
                        h = h.applyHashCode(linkChange.deletedEntitiesSize)
                        linkChange.addedEntities?.map { it.id }?.sorted()?.forEach { id ->
                            h = h.applyHashCode(id)
                        }
                        linkChange.removedEntities?.map { it.id }?.sorted()?.forEach { id ->
                            h = h.applyHashCode(id)
                        }
                        linkChange.deletedEntities?.map { it.id }?.sorted()?.forEach { id ->
                            h = h.applyHashCode(id)
                        }
                    }
                    h
                }

    // do not notify about RemovedNew entities - such entities was created and removed during same transaction
    override val changesDescription: Set<TransientEntityChange>
        get() = changedEntities
                .filterNot { it.id in addedEntities && it.id in removedEntities }
                .mapTo(LinkedHashSetDecorator()) {
                    TransientEntityChange(this, it, getChangedProperties(it), getChangedLinksDetailed(it), getEntityChangeType(it))
                }

    override val changesDescriptionCount: Int
        get() {
            val addedAndRemovedCount = removedEntities.count { it in addedEntities }
            return changedEntities.size - addedAndRemovedCount
        }

    override fun getSnapshotEntity(transientEntity: TransientEntity): TransientEntityImpl {
        val readOnlyPersistentEntity = transientEntity.persistentEntity.getSnapshot(snapshot)
        return ReadonlyTransientEntityImpl(getChangeDescription(transientEntity), readOnlyPersistentEntity, transientEntity.store)
    }

    private fun getEntityChangeType(transientEntity: TransientEntity): EntityChangeType {
        return when (transientEntity.id) {
            in addedEntities -> EntityChangeType.ADD
            in removedEntities -> EntityChangeType.REMOVE
            else -> EntityChangeType.UPDATE
        }
    }

    override fun getChangeDescription(transientEntity: TransientEntity): TransientEntityChange {
        return TransientEntityChange(
                this,
                transientEntity,
                getChangedProperties(transientEntity),
                getChangedLinksDetailed(transientEntity),
                getEntityChangeType(transientEntity)
        )
    }

    override fun getChangedLinksDetailed(transientEntity: TransientEntity): Map<String, LinkChange>? {
        return entityToChangedLinksDetailed[transientEntity]
    }

    override fun getChangedProperties(transientEntity: TransientEntity): Set<String>? {
        return entityToChangedProperties[transientEntity]
    }

    override fun hasChanges(transientEntity: TransientEntity): Boolean =
            !getChangedProperties(transientEntity).isNullOrEmpty() || !getChangedLinksDetailed(transientEntity).isNullOrEmpty()

    override fun hasPropertyChanges(transientEntity: TransientEntity, propName: String): Boolean =
            getChangedProperties(transientEntity).orEmpty().contains(propName)

    override fun hasLinkChanges(transientEntity: TransientEntity, linkName: String): Boolean =
            getChangedLinksDetailed(transientEntity).orEmpty().containsKey(linkName)

    override fun getPropertyOldValue(transientEntity: TransientEntity, propName: String): Comparable<*>? =
            transientEntity.persistentEntity.getSnapshot(snapshot).getProperty(propName)

    override fun isNew(transientEntity: TransientEntity): Boolean {
        return transientEntity.id in addedEntities
    }

    override fun isRemoved(transientEntity: TransientEntity): Boolean {
        return transientEntity.id in removedEntities
    }

    override fun isSaved(transientEntity: TransientEntity): Boolean {
        val id = transientEntity.id
        return id !in addedEntities && id !in removedEntities
    }

    override fun linksRemoved(source: TransientEntity, linkName: String, links: Iterable<Entity>) {
        links.iterator().let {
            if (it.hasNext()) {
                entityChanged(source)
                val (_, linkChange) = getLinkChange(source, linkName)
                do {
                    addRemoved(linkChange, it.next() as TransientEntity)
                } while (it.hasNext())
            }
        }
    }

    private fun getLinkChange(source: TransientEntity, linkName: String): Pair<MutableMap<String, LinkChange>, LinkChange> {
        val linksDetailed = entityToChangedLinksDetailed.getOrPut(source) { HashMap() }
        val linkChange = linksDetailed.getOrPut(linkName) { LinkChange(linkName) }
        return Pair(linksDetailed, linkChange)
    }

    override fun linkChanged(source: TransientEntity, linkName: String, target: TransientEntity, oldTarget: TransientEntity?, add: Boolean) {
        entityChanged(source)

        val (linksDetailed, linkChange) = getLinkChange(source, linkName)
        if (add) {
            if (oldTarget != null) {
                addRemoved(linkChange, oldTarget)
            }
            linkChange.addAdded(target)
        } else {
            addRemoved(linkChange, target)
        }
        if (linkChange.addedEntitiesSize == 0 && linkChange.removedEntitiesSize == 0 && linkChange.deletedEntitiesSize == 0) {
            linksDetailed.remove(linkName)
            if (linksDetailed.isEmpty()) {
                entityToChangedLinksDetailed.remove(source)
            }
        }
    }

    private fun addRemoved(change: LinkChange, entity: TransientEntity) {
        change.addRemoved(entity)
        val changes = removedFrom.getOrPut(entity) { ArrayList() }
        changes.add(change)
    }

    private fun entityChanged(e: TransientEntity) {
        _changedEntities.add(e)
        _affectedEntityTypes.add(e.type)
    }

    override fun propertyChanged(e: TransientEntity, propertyName: String) {
        entityChanged(e)

        val properties = entityToChangedProperties.getOrPut(e) { HashSet() }
        properties.add(propertyName)
    }

    override fun removePropertyChanged(e: TransientEntity, propertyName: String) {
        val properties = entityToChangedProperties[e]
        if (properties != null) {
            properties.remove(propertyName)
            if (properties.isEmpty()) {
                entityToChangedProperties.remove(e)
            }
        }
    }

    override fun entityAdded(e: TransientEntity) {
        entityChanged(e)
        addedEntities = addedEntities.add(e.id)
    }

    override fun entityRemoved(e: TransientEntity) {
        entityChanged(e)
        removedEntities = removedEntities.add(e.id)
        val changes = removedFrom[e]
        if (changes != null) {
            for (change in changes) {
                change.addDeleted(e)
            }
        }
    }

    override fun upgrade(): TransientChangesTracker {
        return this
    }

    override fun dispose() {
        _snapshot?.let {
            it.abort()
            _snapshot = null
        }
    }
}

// 2^256 - 1
private val mod = (BigInteger.ONE shl 256) - BigInteger.ONE

// Eighth Mersenne prime
private val eighthMersennePrime = (BigInteger.ONE shl 31) - BigInteger.ONE

private fun BigInteger.applyHashCode(o: Any): BigInteger {
    val h = if (o is Enum<*>) (o.ordinal + 1) else o.hashCode()
    return ((this * eighthMersennePrime) + BigInteger.valueOf(h.toLong())) and mod
}

private val MutableMap<String, LinkChange>?.isNotEmpty: Boolean get() = this != null && isNotEmpty()

private val Set<String>?.isNotEmpty: Boolean get() = this != null && isNotEmpty()
