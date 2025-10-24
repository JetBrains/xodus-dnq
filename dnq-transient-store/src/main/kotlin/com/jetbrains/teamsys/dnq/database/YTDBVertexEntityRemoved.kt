/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.query.InMemoryEntityIterable
import jetbrains.exodus.query.QueryEngine
import java.io.File
import java.io.InputStream

class YTDBVertexEntityRemoved(
    oEntityId: RIDEntityId,
    originalVertex: YTDBVertexEntity,
    store: YTDBEntityStore,
    private val queryEngine: QueryEngine,
    private val changesTracker: TransientChangesTrackerImpl
) : YTDBVertexEntity(
    oEntityId = oEntityId,
    ytdbVertex =
        if (originalVertex.vertex is YTDBDetachedVertex) originalVertex.vertex
        else YTDBDetachedVertex(originalVertex.vertex),
    store = store
) {

    private val links = mutableMapOf<String, Set<EntityId>>()

    init {
        for (link in originalVertex.linkNames) {
            links[link] = originalVertex
                .getLinks(link)
                .asSequence()
                .map { it.id }
                .toSet()
        }
    }

    override fun getLinkNames(): List<String> = links.keys.toList()

    override fun getLink(linkName: String): Entity? =
        links[linkName]?.firstOrNull()?.let { id ->
            changesTracker.getRemoved(id)
                ?: store.requireActiveTransaction()
                    .loadVertexOrNull(store.requireOEntityId(id).asOId())
                    ?.let { YTDBVertexEntity(it, store) }
        }

    override fun getLinks(linkName: String): EntityIterable =
        loadMultiple(links[linkName] ?: setOf())

    override fun getLinks(linkNames: Collection<String>): EntityIterable =
        loadMultiple(linkNames.flatMap { links[it] ?: setOf() })

    private fun loadMultiple(ids: Iterable<EntityId>): EntityIterable {
        if (ids.none()) {
            return YTDBEntityIterable.EMPTY
        }

        val (removedIds, existingIds) = ids.partition(changesTracker::isRemoved)

        val txn = requireActiveTx()
        val removed = InMemoryEntityIterable(
            removedIds.mapNotNull(changesTracker::getRemoved),
            txn,
            queryEngine
        )

        if (existingIds.none()) {
            return removed
        }

        val existing = YTDBEntityIterable.query(
            txn,
            GremlinQuery.ByIds(existingIds.map { store.requireOEntityId(it).asOId() })
        )

        if (removedIds.none()) {
            return existing
        }

        return queryEngine.inMemoryConcat(existing, removed)
    }

    private fun loadEntity(id: YTDBEntityId): Entity? =
        changesTracker.getRemoved(id)
            ?: store.requireActiveTransaction().loadVertexOrNull(id.asOId())?.let { YTDBVertexEntity(it, store) }

    override fun delete(): Boolean = vertexIsRemoved()
    override fun resetToNew() = vertexIsRemoved()
    override fun setProperty(propertyName: String, value: Comparable<*>): Boolean = vertexIsRemoved()
    override fun setBlob(blobName: String, blob: InputStream) = vertexIsRemoved()
    override fun setBlob(blobName: String, file: File) = vertexIsRemoved()
    override fun setBlobString(blobName: String, blobString: String): Boolean = vertexIsRemoved()
    override fun setLink(linkName: String, target: Entity?): Boolean = vertexIsRemoved()
    override fun setLink(linkName: String, targetId: EntityId): Boolean = vertexIsRemoved()
    override fun addLink(linkName: String, target: Entity): Boolean = vertexIsRemoved()
    override fun addLink(linkName: String, targetId: EntityId): Boolean = vertexIsRemoved()
    override fun deleteProperty(propertyName: String): Boolean = vertexIsRemoved()
    override fun deleteBlob(blobName: String): Boolean = vertexIsRemoved()
    override fun deleteLink(linkName: String, target: Entity): Boolean = vertexIsRemoved()
    override fun deleteLink(linkName: String, targetId: EntityId): Boolean = vertexIsRemoved()
    override fun deleteLinks(linkName: String) = vertexIsRemoved()


    private fun vertexIsRemoved(): Nothing {
        throw IllegalArgumentException("Already deleted")
    }
}