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

import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource
import com.jetbrains.youtrackdb.api.gremlin.__
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.Questionable
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.SortDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.StringCompare
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterableImpl
import jetbrains.exodus.env.ReadonlyTransactionException
import kotlin.jvm.optionals.getOrNull

internal typealias TransactionEventHandler = (YTDBStoreTransaction) -> Unit

class YTDBStoreTransactionImpl(
    private val graph: YTDBGraph,
    private val store: YTDBPersistentEntityStore,
    private val schemaBuddy: YTDBSchemaBuddy,
    private val onFinished: TransactionEventHandler,
    private val readOnly: Boolean = false
) : YTDBStoreTransaction {
    private var queryCancellingPolicy: YTDBQueryCancellingPolicy? = null

    private val userObjects: MutableMap<Any, Any> = HashMapDecorator()

    /**
     * Get access to the underlying YTDB database session. This method should not be used in general,
     * and is made public only for unit tests. Once YTDB supports sequences and schema manipulation via
     * Gremlin API, this method should be removed or made private at least.
     */
    fun activeYtdbSession(): DatabaseSessionEmbedded {
        requireActiveTransaction()
        return (graph as YTDBGraphEmbedded).underlyingDatabaseSession
    }

    /**
     * The Orient transaction gets changed on flush(), so its id gets changed too.
     * It would be strange if id of OStoreTransaction gets changed during its lifetime,
     * so it was decided to remember the first Orient transaction id and use it as OStoreTransaction id.
     *
     * If you think that it should be implemented differently, come and let's discuss.
     */
    private val transactionIdImpl by lazy {
        activeYtdbSession().activeTransaction.id
    }

    override fun getTransactionId(): Long {
        return transactionIdImpl
    }


    override fun g(): YTDBGraphTraversalSource = graph.traversal()

    override fun getStore(): YTDBEntityStore = store

    override fun isIdempotent(): Boolean =
        readOnly || activeYtdbSession().activeTransaction.recordOperations.findAny().isEmpty

    override fun isReadonly(): Boolean = readOnly

    override fun isFinished(): Boolean = !graph.tx().isOpen

    override fun requireActiveTransaction() {
        check(!isFinished) {
            "The transaction is finished"
        }
    }

    override fun requireActiveWritableTransaction() {
        check(!readOnly) { "Cannot modify read-only transaction" }
        requireActiveTransaction()
    }

    fun begin() {
        // check(session.status == STATUS.OPEN) { "The session status is ${session.status}. But ${STATUS.OPEN} is required." }
        // check((session as DatabaseSessionInternal).isActiveOnCurrentThread) { "The session is not active on the current thread" }
        val tx = graph.tx()
        check(!tx.isOpen) { "The session must not have a transaction" }
        try {
            graph.tx().open()
            // initialize transaction id
            // todo: it might be not initialized yet?
            transactionIdImpl
        } finally {
            cleanUpTxIfNeeded()
        }
    }

    override fun commit(): Boolean {
        try {
            requireActiveTransaction()
            graph.tx().commit()
        } finally {
            cleanUpTxIfNeeded()
        }

        return true
    }

    override fun flush(): Boolean {
        try {
            requireActiveTransaction()
            graph.tx().commit()
            graph.tx().open()
        } catch (_: ModificationOperationProhibitedException) {
            throw ReadonlyTransactionException()
        } finally {
            cleanUpTxIfNeeded()
        }

        return true
    }

    override fun abort() {
        try {
            requireActiveTransaction()
            graph.tx().rollback()
        } finally {
            cleanUpTxIfNeeded()
        }
    }

    override fun revert() {
        try {
            requireActiveTransaction()
            graph.tx().rollback()
            graph.tx().open()
        } finally {
            cleanUpTxIfNeeded()
        }
    }

    private fun cleanUpTxIfNeeded() {
        if (isFinished) {
            clearUserObjects()
            onFinished(this)
        }
    }

    override fun getSnapshot(): YTDBStoreTransaction = this

    override fun newVertex(entityType: String?): YTDBVertex {
        if (entityType != null) {
            schemaBuddy.requireTypeExists(activeYtdbSession(), entityType)
        }

        requireActiveWritableTransaction()
        val t = if (entityType == null) g().addV() else g().addV(entityType)
        return t.next() as YTDBVertex
    }

    override fun newEntity(entityType: String): YTDBVertexEntity {
        val vertex = newVertex(entityType)
        setLocalEntityId(this, entityType, vertex)
        return YTDBVertexEntity(vertex, store)
    }

    override fun newEntity(entityType: String, localEntityId: Long): YTDBVertexEntity {
        val vertex = newVertex(entityType)
        vertex.property(LOCAL_ENTITY_ID_PROPERTY_NAME, localEntityId)
        return YTDBVertexEntity(vertex, store)
    }

    override fun newBlob(bytes: ByteArray): Blob {
        return activeYtdbSession().newBlob(bytes)
    }

    override fun generateEntityId(entityType: String, vertex: YTDBVertex) {
        setLocalEntityId(this, entityType, vertex)
    }

    override fun getEntity(id: EntityId): YTDBVertexEntity =
        YTDBVertexEntity(
            getVertex(store.requireOEntityId(id)),
            store
        )

    override fun deleteVertex(id: RID) {
        g().V(id).drop().iterate()
    }

    override fun deleteEdge(id: RID) {
        g().E(id).drop().iterate()
    }

    override fun loadVertexOrNull(id: RID): YTDBVertex? =
        g().V(id)
            .tryNext()
            .map { (it as YTDBVertex) }
            .getOrNull()

    override fun getVertex(id: RID): YTDBVertex {
        val session = activeYtdbSession()
        return loadVertexOrNull(id) ?: throw RecordNotFoundException(session, id)
    }

    override fun getVertex(id: YTDBEntityId): YTDBVertex {
        requireActiveTransaction()
        return loadVertexOrNull(id.asOId()) ?: throw EntityRemovedInDatabaseException(id.getTypeName(), id)
    }

    override fun getBlob(rid: RID): Blob {
        return activeYtdbSession().loadBlob(rid)
    }

    @Questionable("Not tested")
    override fun findEdge(edgeClassName: String, outId: RID, inId: RID): YTDBEdge? {
        return g().V(outId)
            .outE(edgeClassName)
            .where(`__`.inV().hasId(inId))
            .tryNext()
            .map { e -> (e as YTDBEdge) }
            .getOrNull()
    }

    override fun getEntityTypes(): List<String> {
        return activeYtdbSession().schema.classes
            .filter { it.isVertexType && it.name != Vertex.CLASS_NAME }
            .map { it.name }
    }

    override fun getAll(entityType: String, polymorphic: Boolean): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, getStore(), GremlinBlock.All, polymorphic)
    }

    override fun getSingletonIterable(entity: Entity): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.single(getStore(), entity.id)
    }

    override fun find(
        entityType: String,
        propertyName: String,
        value: Comparable<*>,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, getStore(), GremlinBlock.PropEqual(propertyName, value), polymorphic)
    }

    override fun find(
        entityType: String,
        propertyName: String,
        minValue: Comparable<*>,
        maxValue: Comparable<*>,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            getStore(),
            GremlinBlock.PropInRange(propertyName, minValue, maxValue),
            polymorphic
        )
    }

    override fun findContaining(
        entityType: String,
        propertyName: String,
        value: String,
        ignoreCase: Boolean,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            getStore(),
            GremlinBlock.MatchStringProp(
                propertyName,
                StringCompare.Substring,
                value,
                isCollection = false,
                caseSensitive = !ignoreCase
            ),
            polymorphic
        )
    }

    override fun findStartingWith(
        entityType: String,
        propertyName: String,
        value: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            getStore(),
            GremlinBlock.MatchStringProp(
                propertyName,
                StringCompare.Prefix,
                value,
                isCollection = false,
                caseSensitive = false
            ),
            polymorphic
        )
    }

    override fun findIds(entityType: String, minValue: Long, maxValue: Long, polymorphic: Boolean): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            getStore(),
            GremlinBlock.PropInRange(LOCAL_ENTITY_ID_PROPERTY_NAME, minValue, maxValue),
            polymorphic
        )
    }

    override fun findWithProp(entityType: String, propertyName: String, polymorphic: Boolean): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, getStore(), GremlinBlock.PropNotNull(propertyName), polymorphic)
    }

    override fun findWithPropSortedByValue(
        entityType: String,
        propertyName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            getStore(),
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(GremlinBlock.PropNotNull(propertyName))
                // todo: move SortBy out of Condition
                .then(GremlinBlock.Sort(GremlinBlock.Sort.ByProp(propertyName), SortDirection.ASC)),
            polymorphic
        )
    }

    override fun findWithBlob(entityType: String, blobName: String, polymorphic: Boolean): YTDBEntityIterable {
        return findWithProp(entityType, blobName, polymorphic)
    }

    override fun findLinks(
        entityType: String,
        entityId: YTDBEntityId,
        linkName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            getStore(),
            GremlinQuery
                .ByIds(listOf(entityId.asOId()))
                .then(GremlinBlock.InLink(linkName))
                .then(GremlinBlock.HasLabel(entityType)),
            polymorphic
        )
    }

    override fun findLinks(
        entityType: String,
        entity: Entity,
        linkName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        return findLinks(entityType, entity.id as YTDBEntityId, linkName, polymorphic)
    }

    override fun findLinksUntyped(
        entity: Entity,
        linkName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            getStore(),
            GremlinQuery
                .ByIds(listOf((entity.id as YTDBEntityId).asOId()))
                .then(GremlinBlock.InLink(linkName)),
            polymorphic
        )
    }

    override fun findLinks(
        entityType: String,
        entities: EntityIterable,
        linkName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return if (entities === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterable.query(
            getStore(),
            entities.asYTDBIterable().query
                .then(GremlinBlock.InLink(linkName))
                .then(GremlinBlock.HasLabel(entityType)),
            polymorphic
        )
    }

    override fun findWithLinks(entityType: String, linkName: String, polymorphic: Boolean): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, getStore(), GremlinBlock.HasLink(linkName), polymorphic)
    }

    override fun findWithLinks(
        entityType: String,
        linkName: String,
        oppositeEntityType: String,
        oppositeLinkName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, getStore(), GremlinBlock.HasLink(linkName), polymorphic)
    }

    override fun sort(
        entityType: String,
        propertyName: String,
        ascending: Boolean,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            getStore(),
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByProp(propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                ),
            polymorphic
        )
    }

    override fun sort(
        entityType: String,
        propertyName: String,
        rightOrder: EntityIterable,
        ascending: Boolean,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return if (rightOrder === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterableImpl(
            getStore(),
            rightOrder.asYTDBIterable().query
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByProp(propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                ),
            polymorphic
        )
    }

    fun sortLinked(
        entityType: String,
        linkName: String,
        propertyName: String,
        ascending: Boolean,
        polymorphic: Boolean = true
    ): EntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            getStore(),
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByLinked(linkName, propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                ),
            polymorphic
        )
    }

    fun sortLinked(
        entityType: String,
        linkName: String,
        propertyName: String,
        rightOrder: EntityIterable,
        ascending: Boolean,
        polymorphic: Boolean = true
    ): EntityIterable {
        requireActiveTransaction()
        return if (rightOrder === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterableImpl(
            getStore(),
            rightOrder.asYTDBIterable().query
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByLinked(linkName, propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                ),
            polymorphic
        )
    }

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return if (rightOrder === YTDBEntityIterable.EMPTY || sortedLinks === YTDBEntityIterable.EMPTY)
            YTDBEntityIterable.EMPTY
        else YTDBEntityIterable.query(
            getStore(),
            sortedLinks.asYTDBIterable().query
                .then(GremlinBlock.InLink(linkName))
                .intersect(rightOrder.asYTDBIterable().query)
                .then(GremlinBlock.HasLabel(entityType)),
            polymorphic
        )
    }

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable,
        oppositeEntityType: String,
        oppositeLinkName: String,
        polymorphic: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        // todo: check if we need this
        // Not sure about skipping oppositeEntityType and oppositeLinkName values
        return if (rightOrder === YTDBEntityIterable.EMPTY || sortedLinks === YTDBEntityIterable.EMPTY)
            YTDBEntityIterable.EMPTY
        else YTDBEntityIterable.query(
            getStore(),
            sortedLinks.asYTDBIterable().query
                .then(GremlinBlock.InLink(linkName))
                .intersect(rightOrder.asYTDBIterable().query)
                .then(GremlinBlock.HasLabel(entityType)),
            polymorphic
        )
    }

    @Deprecated("Deprecated in Java")
    override fun mergeSorted(
        sorted: List<EntityIterable>,
        comparator: Comparator<Entity>
    ): EntityIterable {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun mergeSorted(
        sorted: List<EntityIterable>,
        valueGetter: ComparableGetter,
        comparator: Comparator<Comparable<Any>>
    ): EntityIterable {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun toEntityId(representation: String): EntityId {
        // Parse-only, matching the classic Xodus contract: parse "<typeId>-<localId>" into a logical
        // PersistentEntityId without touching the database. No active transaction is required (a pure
        // parse needs none). Resolution to a RIDEntityId, when needed, happens explicitly via the
        // store (getOEntityId / requireOEntityId). Malformed input throws IllegalArgumentException
        // (or its subclass NumberFormatException), same family as before and classic-Xodus parity.
        return PersistentEntityId.toEntityId(representation)
    }

    override fun getSequence(sequenceName: String): Sequence {
        return getSequence(sequenceName, -1)
    }

    override fun getSequence(sequenceName: String, initialValue: Long): Sequence {
        val session = activeYtdbSession()
        // make sure the OSequence created
        schemaBuddy.getOrCreateSequence(session, sequenceName, initialValue)
        return YTDBSequence(session, sequenceName, store)
    }

    override fun getOSequence(sequenceName: String): DBSequence {
        return schemaBuddy.getSequence(activeYtdbSession(), sequenceName)
    }

    override fun getSequenceNextValue(sequenceName: String): Long {
        val session = activeYtdbSession()
        return schemaBuddy.getSequence(session, sequenceName).next(session)
    }

    override fun updateOSequence(sequenceName: String, currentValue: Long) {
        return schemaBuddy.updateSequence(activeYtdbSession(), sequenceName, currentValue)
    }

    override fun renameOClass(oldName: String, newName: String) {
        schemaBuddy.renameOClass(activeYtdbSession(), oldName, newName)
    }

    override fun deleteOClass(entityTypeName: String) {
        schemaBuddy.deleteOClass(activeYtdbSession(), entityTypeName)
    }

    override fun getOrCreateEdgeClass(
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass {
        return schemaBuddy.getOrCreateEdgeClass(activeYtdbSession(), linkName, outClassName, inClassName)
    }

    override fun setQueryCancellingPolicy(policy: QueryCancellingPolicy?) {
        // MIGRATION: Xodus supported arbitrary QueryCancellingPolicy implementations (e.g.
        // PersistentUserJobCancellationPolicy). YouTrackDB only supports YTDBQueryCancellingPolicy.
        // Non-compatible policies are silently ignored rather than crashing.
        this.queryCancellingPolicy = policy as? YTDBQueryCancellingPolicy
    }

    override fun getQueryCancellingPolicy() = this.queryCancellingPolicy

    override fun getOEntityId(typeId: Int, localId: Long): RIDEntityId? {
        return schemaBuddy.getOEntityId(activeYtdbSession(), typeId, localId)
    }

    override fun getTypeId(entityType: String): Int {
        return schemaBuddy.getTypeId(activeYtdbSession(), entityType)
    }

    override fun getType(entityTypeId: Int): String {
        return schemaBuddy.getType(activeYtdbSession(), entityTypeId)
    }

    override fun getUserObject(key: Any): Any? {
        synchronized(userObjects) {
            return userObjects[key]
        }
    }

    override fun setUserObject(key: Any, value: Any) {
        synchronized(userObjects) {
            userObjects[key] = value
        }
    }

    private fun clearUserObjects() {
        synchronized(userObjects) {
            userObjects.clear()
        }
    }

}
