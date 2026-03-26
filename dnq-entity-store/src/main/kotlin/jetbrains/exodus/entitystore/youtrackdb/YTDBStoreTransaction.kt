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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable

interface YTDBStoreTransaction : StoreTransaction {

    fun getTransactionId(): Long

    fun requireActiveTransaction()

    fun requireActiveWritableTransaction()

    fun generateEntityId(entityType: String, vertex: YTDBVertex)

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

    fun getSequenceNextValue(sequenceName: String): Long

    fun updateOSequence(sequenceName: String, currentValue: Long)

    fun renameOClass(oldName: String, newName: String)

    fun getOrCreateEdgeClass(
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass

    fun deleteOClass(entityTypeName: String)

    fun loadVertexOrNull(id: RID): YTDBVertex?
    fun getVertex(id: RID): YTDBVertex
    fun getVertex(id: YTDBEntityId): YTDBVertex

    fun deleteVertex(id: RID)
    fun deleteEdge(id: RID)

    override fun getEntity(id: EntityId): YTDBVertexEntity

    fun getBlob(rid: RID): Blob

    fun findEdge(edgeClassName: String, outId: RID, inId: RID): YTDBEdge?

    fun newEntity(entityType: String, localEntityId: Long): YTDBVertexEntity

    fun newVertex(entityType: String?): YTDBVertex


    override fun newEntity(entityType: String): YTDBVertexEntity

    fun newBlob(bytes: ByteArray): Blob

    override fun getStore(): YTDBEntityStore

    override fun getSnapshot(): YTDBStoreTransaction

    override fun getAll(entityType: String): YTDBEntityIterable

    override fun getSingletonIterable(entity: Entity): YTDBEntityIterable

    override fun find(
        entityType: String,
        propertyName: String,
        value: Comparable<*>
    ): YTDBEntityIterable

    override fun find(
        entityType: String,
        propertyName: String,
        minValue: Comparable<*>,
        maxValue: Comparable<*>
    ): YTDBEntityIterable

    override fun findContaining(
        entityType: String,
        propertyName: String,
        value: String,
        ignoreCase: Boolean
    ): YTDBEntityIterable

    override fun findStartingWith(
        entityType: String,
        propertyName: String,
        value: String
    ): YTDBEntityIterable

    override fun findIds(
        entityType: String,
        minValue: Long,
        maxValue: Long
    ): YTDBEntityIterable

    override fun findWithProp(
        entityType: String,
        propertyName: String
    ): YTDBEntityIterable

    override fun findWithPropSortedByValue(
        entityType: String,
        propertyName: String
    ): YTDBEntityIterable

    override fun findWithBlob(
        entityType: String,
        blobName: String
    ): YTDBEntityIterable

    override fun findLinks(
        entityType: String,
        entity: Entity,
        linkName: String
    ): YTDBEntityIterable

    fun findLinks(
        entityType: String,
        entityId: YTDBEntityId,
        linkName: String
    ): YTDBEntityIterable

    override fun findLinks(
        entityType: String,
        entities: EntityIterable,
        linkName: String
    ): YTDBEntityIterable

    /**
     * Returns all entities that have an incoming link named [linkName] from [entity],
     * regardless of the source entity type. Unlike [findLinks], no `HasLabel` filter
     * is applied, so a single DB traversal covers all source types.
     */
    fun findLinksUntyped(entity: Entity, linkName: String): YTDBEntityIterable

    override fun findWithLinks(
        entityType: String,
        linkName: String
    ): YTDBEntityIterable

    override fun findWithLinks(
        entityType: String,
        linkName: String,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): YTDBEntityIterable

    override fun sort(
        entityType: String,
        propertyName: String,
        ascending: Boolean
    ): YTDBEntityIterable

    override fun sort(
        entityType: String,
        propertyName: String,
        rightOrder: EntityIterable,
        ascending: Boolean
    ): YTDBEntityIterable

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable
    ): YTDBEntityIterable

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): YTDBEntityIterable

    override fun toEntityId(representation: String): YTDBEntityId

    /**
     * Returns a user object identified by the specified key and bound to the transaction, or `null` if no
     * object is bound to the transaction by the specified key.
     *
     * @param key a key identifying the user object
     * @return a user object identified by the specified key and bound to the transaction
     */
    fun getUserObject(key: Any): Any?

    /**
     * Bind a user object (`value`) identified by a key to the transaction.
     *
     * @param key   a key identifying the user object
     * @param value user object bound to the transaction
     */
    fun setUserObject(key: Any, value: Any)
}
