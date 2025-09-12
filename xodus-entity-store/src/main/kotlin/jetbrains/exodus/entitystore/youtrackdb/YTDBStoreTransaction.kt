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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource
import com.jetbrains.youtrackdb.api.record.Blob
import com.jetbrains.youtrackdb.api.record.Edge
import com.jetbrains.youtrackdb.api.record.RID
import com.jetbrains.youtrackdb.api.record.Vertex
import com.jetbrains.youtrackdb.api.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.Questionable
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinEntityIterable

interface YTDBStoreTransaction : StoreTransaction {

    fun getTransactionId(): Long

    fun requireActiveTransaction()

    fun requireActiveWritableTransaction()

    fun deactivateOnCurrentThread()

    fun activateOnCurrentThread()

    fun generateEntityId(entityType: String, vertex: Vertex)

    fun bindToSession(vertex: Vertex): Vertex

    fun bindToSession(entity: YTDBVertexEntity): YTDBVertexEntity

    fun g(): YTDBGraphTraversalSource

    fun getOEntityId(entityId: PersistentEntityId): YTDBEntityId

    /**
     * If the class has not been found, returns -1. It is how it was in the Classic Xodus.
     */
    fun getTypeId(entityType: String): Int

    /**
    If the class has not been found, will throw EntityRemovedInDatabaseException with invalid type id
     */
    fun getType(entityTypeId: Int): String

    fun getOSequence(sequenceName: String): DBSequence

    fun updateOSequence(sequenceName: String, currentValue: Long)

    fun renameOClass(oldName: String, newName: String)

    fun getOrCreateEdgeClass(
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass

    fun deleteOClass(entityTypeName: String)

    fun getVertex(id: YTDBEntityId): Vertex

    override fun getEntity(id: EntityId): YTDBVertexEntity

    fun getBlob(rid: RID): Blob

    fun findEdge(edgeClassName: String, outId: RID, inId: RID): Edge?

    fun newEntity(entityType: String, localEntityId: Long): YTDBVertexEntity

    fun newVertex(entityType: String?): Vertex

    override fun newEntity(entityType: String): YTDBVertexEntity

    fun newBlob(bytes: ByteArray): Blob

    fun isNotBound(v: YTDBVertexEntity): Boolean

    override fun getStore(): YTDBEntityStore

    override fun getSnapshot(): YTDBStoreTransaction

    override fun getAll(entityType: String): GremlinEntityIterable

    override fun getSingletonIterable(entity: Entity): GremlinEntityIterable

    override fun find(
        entityType: String,
        propertyName: String,
        value: Comparable<*>
    ): GremlinEntityIterable

    override fun find(
        entityType: String,
        propertyName: String,
        minValue: Comparable<*>,
        maxValue: Comparable<*>
    ): GremlinEntityIterable

    override fun findContaining(
        entityType: String,
        propertyName: String,
        value: String,
        ignoreCase: Boolean
    ): GremlinEntityIterable

    override fun findStartingWith(
        entityType: String,
        propertyName: String,
        value: String
    ): GremlinEntityIterable

    override fun findIds(
        entityType: String,
        minValue: Long,
        maxValue: Long
    ): GremlinEntityIterable

    override fun findWithProp(
        entityType: String,
        propertyName: String
    ): GremlinEntityIterable

    override fun findWithPropSortedByValue(
        entityType: String,
        propertyName: String
    ): GremlinEntityIterable

    override fun findWithBlob(
        entityType: String,
        blobName: String
    ): GremlinEntityIterable

    override fun findLinks(
        entityType: String,
        entity: Entity,
        linkName: String
    ): GremlinEntityIterable

    override fun findLinks(
        entityType: String,
        entities: EntityIterable,
        linkName: String
    ): GremlinEntityIterable

    override fun findWithLinks(
        entityType: String,
        linkName: String
    ): GremlinEntityIterable

    override fun findWithLinks(
        entityType: String,
        linkName: String,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): GremlinEntityIterable

    override fun sort(
        entityType: String,
        propertyName: String,
        ascending: Boolean
    ): GremlinEntityIterable

    override fun sort(
        entityType: String,
        propertyName: String,
        rightOrder: EntityIterable,
        ascending: Boolean
    ): GremlinEntityIterable

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable
    ): GremlinEntityIterable

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): GremlinEntityIterable

    override fun toEntityId(representation: String): YTDBEntityId
}
