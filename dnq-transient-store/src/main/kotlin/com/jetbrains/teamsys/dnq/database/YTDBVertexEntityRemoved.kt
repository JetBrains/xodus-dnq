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

import com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.query.InMemoryEntityIterable
import jetbrains.exodus.query.QueryEngine
import jetbrains.exodus.util.UTFUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class YTDBVertexEntityRemoved(
    oEntityId: RIDEntityId,
    originalVertex: YTDBVertexEntity,
    store: YTDBEntityStore,
    private val queryEngine: QueryEngine,
    private val changesTracker: TransientChangesTrackerImpl,
    sourceEntity: TransientEntity,
) : YTDBVertexEntity(
    oEntityId = oEntityId,
    ytdbVertex =
        if (originalVertex.vertex is YTDBDetachedVertex) originalVertex.vertex
        else YTDBDetachedVertex(originalVertex.vertex),
    store = store
) {

    private val links = mutableMapOf<String, Set<EntityId>>()

    /**
     * Snapshot of blob bytes captured at construction time, before the YTDB session that owns
     * the underlying `RecordBytes` is closed. YTDB calls `RecordAbstract.unload()` on its cached
     * records when the session closes, resetting `status` to `NOT_LOADED` and clearing `source`.
     * Without this eager copy, a flushed-sync listener that reads a blob field on the snapshot
     * would trip `RecordAbstract.checkForBinding` with
     * `Record #X:Y is not bound to the current session` (XD-1272).
     *
     * Regular scalar properties (String, Long, …) don't need this — they are stored inline on
     * the vertex, `YTDBDetachedVertex` copies their values by reference, and the JVM objects
     * are unaffected by YTDB session lifecycle.
     */
    private val blobBytes: Map<String, ByteArray> = captureBlobBytes(originalVertex, store)

    init {
        // The live vertex reflects post-mutation state at delete() time. To get the
        // pre-transaction state, overlay the tracker's LinkChange records:
        //   + add back removedEntities / deletedEntities (stripped earlier in txn)
        //   - subtract addedEntities (added earlier in txn — not in pre-txn state)
        val linkChanges = changesTracker.getChangedLinksDetailed(sourceEntity).orEmpty()
        val linkNames = LinkedHashSet<String>(originalVertex.linkNames).apply {
            addAll(linkChanges.keys)
        }
        for (linkName in linkNames) {
            val ids = originalVertex.getLinks(linkName).asSequence().mapTo(LinkedHashSet()) { it.id }
            linkChanges[linkName]?.let { change ->
                change.removedEntities?.forEach { ids.add(it.id) }
                change.deletedEntities?.forEach { ids.add(it.id) }
                change.addedEntities?.forEach { ids.remove(it.id) }
            }
            if (ids.isNotEmpty()) {
                links[linkName] = ids
            }
        }
    }

    override fun getBlob(blobName: String): InputStream? =
        blobBytes[blobName]?.let { ByteArrayInputStream(it) }

    override fun getBlobString(blobName: String): String? =
        blobBytes[blobName]?.let { UTFUtil.readUTF(ByteArrayInputStream(it)) }

    override fun getBlobSize(blobName: String): Long =
        blobBytes[blobName]?.size?.toLong() ?: -1L

    override fun getBlobNames(): List<String> = blobBytes.keys.toList()

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

/**
 * Reads each blob property's bytes into a JVM-owned `ByteArray` while the YTDB session that
 * owns the underlying [RecordBytes] is still active. The returned map outlives the session,
 * so the snapshot can serve blob reads after `session.close()` (which calls
 * `RecordAbstract.unload()` on every cached record).
 *
 * Force-loads each [RecordBytes] via `transaction.getBlob(rid)` before reading, because the
 * record may still be in `NOT_LOADED` state if nothing on the entity's caller path materialised
 * its bytes before removal.
 */
private fun captureBlobBytes(
    originalVertex: YTDBVertexEntity,
    store: YTDBEntityStore
): Map<String, ByteArray> {
    val vertex = originalVertex.vertex
    if (vertex is YTDBDetachedVertex) {
        // Re-snapshotting an already-detached vertex: the bytes were captured by the upstream
        // snapshot already, and we no longer have access to a live session to re-read them.
        return emptyMap()
    }

    val result = mutableMapOf<String, ByteArray>()
    val txn = store.requireActiveTransaction()
    vertex.properties<Any>().forEachRemaining { prop ->
        val value = prop.value()
        if (value is RecordBytes) {
            val rid = value.identity
            if (rid.isValidPosition) {
                // getBlob → session.load(rid) → ensures status == LOADED and source != null,
                // even if the entity caller never touched this blob before removing the entity.
                val loaded = txn.getBlob(rid) as RecordBytes
                result[prop.key()] = loaded.toStream()
            }
        }
    }
    return result
}