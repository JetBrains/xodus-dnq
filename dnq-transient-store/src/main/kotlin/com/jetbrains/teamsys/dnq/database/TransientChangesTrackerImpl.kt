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

import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator
import jetbrains.exodus.core.dataStructures.decorators.LinkedHashSetDecorator
import jetbrains.exodus.core.dataStructures.hash.HashMap
import jetbrains.exodus.core.dataStructures.hash.HashSet
import jetbrains.exodus.core.dataStructures.hash.LinkedHashSet
import jetbrains.exodus.database.*
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import jetbrains.exodus.entitystore.youtrackdb.RIDEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import java.math.BigInteger
import java.util.*

/**
 * @author Vadim.Gurov
 */
class TransientChangesTrackerImpl : TransientChangesTracker {

    private val _changedEntities = LinkedHashSet<TransientEntity>()
    override val changedEntities: Set<TransientEntity>
        get() = _changedEntities

    private var addedEntities = EntityIdSetFactory.newSet()

    private val removedSnapshots = mutableMapOf<EntityId, YTDBVertexEntityRemoved>()
    private val removedEntities = mutableMapOf<EntityId, YTDBVertexEntityRemoved>()

    private val _affectedEntityTypes = HashSet<String>()
    override val affectedEntityTypes: Set<String>
        get() = Collections.unmodifiableSet(_affectedEntityTypes)

    private val removedFrom = HashMapDecorator<TransientEntity, MutableList<LinkChange>>()
    private val entityToChangedLinksDetailed = HashMapDecorator<TransientEntity, MutableMap<String, LinkChange>>()
    private val entityToChangedPropertiesOldValues =
        HashMapDecorator<TransientEntity, HashMap<String, Comparable<*>?>>()

    // do not take into consideration RemovedNew entities - such entities was created and removed during same transaction
    override val changesHash: BigInteger
        get() = changedEntities
            .filterNot { it.id in addedEntities && it.id in removedEntities }
            .sortedBy { it.id }
            .filter { e -> entityToChangedPropertiesOldValues[e]?.isNotEmpty() == true || entityToChangedLinksDetailed[e].isNotEmpty }
            .fold(BigInteger.ONE) { hc, entity ->
                var h = hc.applyHashCode(entity.id).applyHashCode(getEntityChangeType(entity))
                getChangedProperties(entity)?.sorted()?.forEach { propertyName ->
                    h = h.applyHashCode(propertyName)
                }
                getChangedLinksDetailed(entity)?.values?.filter { it.isNotEmpty() }?.sortedBy { it.linkName }
                    ?.forEach { linkChange ->
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

    // do not notify about RemovedNew entities - such entities were created and removed during same transaction
    override val changesDescription: Set<TransientEntityChange>
        get() = changedEntities
            .filterNot { it.id in addedEntities && it.id in removedEntities }
            .mapTo(LinkedHashSetDecorator()) {
                TransientEntityChangeImpl(
                    this,
                    it,
                    entityToChangedPropertiesOldValues[it],
                    getChangedLinksDetailed(it),
                    getEntityChangeType(it)
                )
            }

    override val changesDescriptionCount: Int
        get() {
            val addedAndRemovedCount = removedEntities.count { it.key in addedEntities }
            return changedEntities.size - addedAndRemovedCount
        }

    fun getRemoved(entityId: EntityId): YTDBVertexEntityRemoved? = removedEntities[entityId]

    override fun getSnapshotEntity(transientEntity: TransientEntity): TransientEntity {
        return removedEntities[transientEntity.id]
            ?.let { removed -> RemovedTransientEntity(removed, transientEntity.store) }
            ?: ReadonlyTransientEntity(
                getChangeDescription(transientEntity),
                transientEntity.entity,
                transientEntity.store
            )
    }

    private fun getEntityChangeType(transientEntity: TransientEntity): EntityChangeType {
        return when (transientEntity.id) {
            in addedEntities -> EntityChangeType.ADD
            in removedEntities -> EntityChangeType.REMOVE
            else -> EntityChangeType.UPDATE
        }
    }

    override fun getChangeDescription(transientEntity: TransientEntity): TransientEntityChange {
        return TransientEntityChangeImpl(
            this,
            transientEntity,
            entityToChangedPropertiesOldValues[transientEntity],
            getChangedLinksDetailed(transientEntity),
            getEntityChangeType(transientEntity)
        )
    }

    override fun getChangedLinksDetailed(transientEntity: TransientEntity): Map<String, LinkChange>? {
        return entityToChangedLinksDetailed[transientEntity]
    }

    override fun getChangedProperties(transientEntity: TransientEntity): Set<String>? {
        return entityToChangedPropertiesOldValues[transientEntity]?.keys
    }

    override fun hasChanges(transientEntity: TransientEntity): Boolean =
        !getChangedProperties(transientEntity).isNullOrEmpty() || !getChangedLinksDetailed(transientEntity).isNullOrEmpty()

    override fun hasPropertyChanges(transientEntity: TransientEntity, propName: String): Boolean =
        getChangedProperties(transientEntity).orEmpty().contains(propName)

    override fun hasLinkChanges(transientEntity: TransientEntity, linkName: String): Boolean =
        getChangedLinksDetailed(transientEntity).orEmpty().containsKey(linkName)

    override fun getPropertyOldValue(transientEntity: TransientEntity, propName: String): Comparable<*>? {
        val entityOldValues = entityToChangedPropertiesOldValues[transientEntity]
        return if (entityOldValues?.contains(propName) == true){
            return entityOldValues.get(propName)
        } else {
            transientEntity.getProperty(propName)
        }

    }

    override fun isNew(transientEntity: TransientEntity): Boolean {
        return transientEntity.id in addedEntities
    }

    override fun isRemoved(entityId: EntityId): Boolean {
        return entityId in removedEntities
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

    private fun getLinkChange(
        source: TransientEntity,
        linkName: String
    ): Pair<MutableMap<String, LinkChange>, LinkChange> {
        val linksDetailed = entityToChangedLinksDetailed.getOrPut(source) { HashMap() }
        val linkChange = linksDetailed.getOrPut(linkName) { LinkChange(linkName, this) }
        return Pair(linksDetailed, linkChange)
    }

    override fun linkChanged(
        source: TransientEntity,
        linkName: String,
        target: TransientEntity,
        oldTarget: TransientEntity?,
        add: Boolean
    ) {
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

    override fun propertyChanged(e: TransientEntity, propertyName: String, oldValue: Comparable<*>?) {
        entityChanged(e)

        val oldValues = entityToChangedPropertiesOldValues.getOrPut(e) { HashMap() }
        if (!oldValues.contains(propertyName)) {
            oldValues[propertyName] = oldValue
        }
    }

    override fun removePropertyChanged(e: TransientEntity, propertyName: String) {
        val properties = entityToChangedPropertiesOldValues[e]
        if (properties != null) {
            properties.remove(propertyName)
            if (properties.isEmpty()) {
                entityToChangedPropertiesOldValues.remove(e)
            }
        }
    }

    override fun entityAdded(e: TransientEntity) {
        entityChanged(e)
        addedEntities = addedEntities.add(e.id)
    }

    // we create a detached entity (snapshot) because at this point we have information about entity links
    override fun entityBeforeRemoved(e: TransientEntity) {
        removedSnapshots[e.id] = createRemovedSnapshot(e)
    }

    override fun entityRemoved(e: TransientEntity) {
        entityChanged(e)
        val removed = removedSnapshots.remove(e.id) ?: createRemovedSnapshot(e)
        removedEntities[e.id] = removed
        val changes = removedFrom[e]
        if (changes != null) {
            for (change in changes) {
                change.addDeleted(e)
            }
        }
    }

    private fun createRemovedSnapshot(e: TransientEntity): YTDBVertexEntityRemoved {
        val ytdbEntity = e.entity as YTDBVertexEntity
        val removed = YTDBVertexEntityRemoved(
            ytdbEntity.id as RIDEntityId, ytdbEntity,
            ytdbEntity.store,
            e.store.queryEngine,
            this
        )
        return removed
    }

    override fun upgrade(): TransientChangesTracker {
        return this
    }

    override fun dispose() {

    }

    override fun getRemovedEntitiesIds() = removedEntities.keys.toList()
}

// 2^256 - 1
private val mod = (BigInteger.ONE shl 256) - BigInteger.ONE

// Eighth Mersenne prime
private val eighthMersennePrime = (BigInteger.ONE shl 31) - BigInteger.ONE

private val MutableMap<String, LinkChange>?.isNotEmpty: Boolean get() = this != null && isNotEmpty()

private val Set<String>?.isNotEmpty: Boolean get() = this != null && isNotEmpty()

fun BigInteger.applyHashCode(o: Any): BigInteger {
    val h = if (o is Enum<*>) (o.ordinal + 1) else o.hashCode()
    return ((this * eighthMersennePrime) + BigInteger.valueOf(h.toLong())) and mod
}
