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

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession.STATUS
import com.jetbrains.youtrackdb.api.exception.ModificationOperationProhibitedException
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource
import com.jetbrains.youtrackdb.api.gremlin.__
import com.jetbrains.youtrackdb.api.record.Blob
import com.jetbrains.youtrackdb.api.record.Edge
import com.jetbrains.youtrackdb.api.record.RID
import com.jetbrains.youtrackdb.api.record.Vertex
import com.jetbrains.youtrackdb.api.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexInternal
import com.jetbrains.youtrackdb.internal.core.id.ImmutableRecordId
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.Questionable
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.SortDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinEntityIterableImpl
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.property.YTDBSequenceImpl
import jetbrains.exodus.entitystore.youtrackdb.query.YTDBQueryCancellingPolicy
import jetbrains.exodus.env.ReadonlyTransactionException
import java.util.Optional
import kotlin.jvm.optionals.getOrNull

internal typealias TransactionEventHandler = (YTDBStoreTransaction) -> Unit

class YTDBGremlinStoreTransactionImpl(
    private val graph: YTDBGraph,
    private val store: YTDBPersistentEntityStore,
    private val schemaBuddy: YTDBSchemaBuddy,
    private val onFinished: TransactionEventHandler,
    private val onDeactivated: TransactionEventHandler,
    private val onActivated: TransactionEventHandler,
    private val readOnly: Boolean = false
) : YTDBStoreTransaction {
    private var queryCancellingPolicy: YTDBQueryCancellingPolicy? = null

    private fun activeYtdbSession(): DatabaseSessionEmbedded {
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

    fun loadVertex(id: RID): Optional<Vertex> =
        g().V(id)
            .tryNext()
            .map { (it as YTDBVertexInternal).rawEntity }

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
//        check((session as DatabaseSessionInternal).isActiveOnCurrentThread) {
//            "The active session is no the session the transaction was started in"
//        }
//        val currentTx = session.activeTransaction
//        check(currentTx.status == FrontendTransaction.TXSTATUS.BEGUN) {
//            "The current OTransaction status is ${currentTx.status}, but the status ${FrontendTransaction.TXSTATUS.BEGUN} was expected."
//        }
    }

    override fun requireActiveWritableTransaction() {
        check(!readOnly) { "Cannot modify read-only transaction" }
        requireActiveTransaction()
    }

    override fun deactivateOnCurrentThread() {
        requireActiveTransaction()
        onDeactivated(this)
    }

    override fun activateOnCurrentThread() {
        val session = activeYtdbSession()
        check(session.status == STATUS.OPEN) { "The transaction is finished, the internal session state: ${session.status}" }
        onActivated(this)
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

    override fun bindToSession(vertex: Vertex): Vertex {
        requireActiveTransaction()
        return loadVertex(vertex.identity)
            .orElseThrow {
                RecordNotFoundException(
                    "Cannot find a vertex with id ${vertex.identity} in the database",
                    vertex.identity,
                )
            }
    }

    override fun bindToSession(entity: YTDBVertexEntity): YTDBVertexEntity {
        requireActiveTransaction()

        if (entity.isUnloaded) {
            return YTDBVertexEntity(bindToSession(entity.vertex), store)
        }

        return entity
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
            onFinished(this)
        }
    }

    override fun getSnapshot(): YTDBStoreTransaction = this

    override fun newVertex(entityType: String?): Vertex {
        if (entityType != null) {
            schemaBuddy.requireTypeExists(activeYtdbSession(), entityType)
        }

        requireActiveWritableTransaction()
        return (g().addV(entityType).next() as YTDBVertexInternal).rawEntity
    }

    override fun newEntity(entityType: String): YTDBVertexEntity {
        val vertex = newVertex(entityType)
        setLocalEntityId(activeYtdbSession(), entityType, vertex)
        return YTDBVertexEntity(vertex, store)
    }

    override fun newEntity(entityType: String, localEntityId: Long): YTDBVertexEntity {
        val vertex = newVertex(entityType)
        vertex.setProperty(LOCAL_ENTITY_ID_PROPERTY_NAME, localEntityId)
        return YTDBVertexEntity(vertex, store)
    }

    override fun newBlob(bytes: ByteArray): Blob {
        return activeYtdbSession().newBlob(bytes)
    }

    override fun generateEntityId(entityType: String, vertex: Vertex) {
        setLocalEntityId(activeYtdbSession(), entityType, vertex)
    }

    override fun getEntity(id: EntityId): YTDBVertexEntity =
        YTDBVertexEntity(
            getVertex(store.requireOEntityId(id)),
            store
        )

    override fun getVertex(id: YTDBEntityId): Vertex {
        requireActiveTransaction()
        val ytdbId = id.asOId()
        if (ytdbId == ImmutableRecordId.EMPTY_RECORD_ID) {
            throw EntityRemovedInDatabaseException(id.getTypeName(), id)
        }
        return loadVertex(ytdbId)
            .orElseThrow { EntityRemovedInDatabaseException(id.getTypeName(), id) }
    }

    override fun getBlob(rid: RID): Blob {
        return activeYtdbSession().loadBlob(rid)
    }

    @Questionable("Not tested")
    override fun findEdge(edgeClassName: String, outId: RID, inId: RID): Edge? {
        return g().V(outId)
            .outE(edgeClassName)
            .where(`__`.inV().hasId(inId))
            .tryNext()
            .map { e -> (e as YTDBStatefulEdgeImpl).rawEntity }
            .getOrNull()
    }

    override fun isNotBound(v: YTDBVertexEntity): Boolean = v.vertex.isNotBound(activeYtdbSession())

    override fun getEntityTypes(): List<String> {
        return activeYtdbSession().schema.classes
            .filter { it.isVertexType && it.name != Vertex.CLASS_NAME }
            .map { it.name }
    }

    override fun getAll(entityType: String): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(entityType, this, GremlinBlock.All)
    }

    override fun getSingletonIterable(entity: Entity): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.query(
            this, GremlinQuery.all
                .then(GremlinBlock.IdEqual((entity.id as YTDBEntityId).asOId()))
        )
    }

    override fun find(
        entityType: String,
        propertyName: String,
        value: Comparable<Nothing>
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(entityType, this, GremlinBlock.PropEqual(propertyName, value))
    }

    override fun find(
        entityType: String,
        propertyName: String,
        minValue: Comparable<Nothing>,
        maxValue: Comparable<Nothing>
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(
            entityType,
            this,
            GremlinBlock.PropInRange(propertyName, minValue, maxValue)
        )
    }

    override fun findContaining(
        entityType: String,
        propertyName: String,
        value: String,
        ignoreCase: Boolean
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(
            entityType,
            this,
            GremlinBlock.HasSubstring(propertyName, value, !ignoreCase)
        )
    }

    override fun findStartingWith(
        entityType: String,
        propertyName: String,
        value: String
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(entityType, this, GremlinBlock.HasPrefix(propertyName, value, false))
    }

    override fun findIds(entityType: String, minValue: Long, maxValue: Long): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(
            entityType,
            this,
            GremlinBlock.PropInRange(LOCAL_ENTITY_ID_PROPERTY_NAME, minValue, maxValue)
        )
    }

    override fun findWithProp(entityType: String, propertyName: String): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(entityType, this, GremlinBlock.PropNotNull(propertyName))
    }

    override fun findWithPropSortedByValue(
        entityType: String,
        propertyName: String
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.query(
            this,
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(GremlinBlock.PropNotNull(propertyName))
                // todo: move SortBy out of Condition
                .then(GremlinBlock.Sort(GremlinBlock.Sort.ByProp(propertyName), SortDirection.ASC))
        )
    }

    override fun findWithBlob(entityType: String, blobName: String): GremlinEntityIterable {
        return findWithProp(entityType, blobName)
    }

    override fun findLinks(entityType: String, entity: Entity, linkName: String): GremlinEntityIterable {
        requireActiveTransaction()

        return GremlinEntityIterable.query(
            this,
            GremlinQuery
                .ByIds(listOf((entity.id as YTDBEntityId).asOId()))
                .then(GremlinBlock.InLink(linkName))
                .then(GremlinBlock.HasLabel(entityType))
        )
    }

    override fun findLinks(
        entityType: String,
        entities: EntityIterable,
        linkName: String
    ): GremlinEntityIterable {
        requireActiveTransaction()
        val entityQuery = entities.asGremlinIterable().query
        return GremlinEntityIterable.query(
            this,
            entityQuery
                .then(GremlinBlock.InLink(linkName))
                .then(GremlinBlock.HasLabel(entityType))
        )
    }

    override fun findWithLinks(entityType: String, linkName: String): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(entityType, this, GremlinBlock.HasLink(linkName))
    }

    override fun findWithLinks(
        entityType: String,
        linkName: String,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.where(entityType, this, GremlinBlock.HasLink(linkName))
    }

    override fun sort(
        entityType: String,
        propertyName: String,
        ascending: Boolean
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.query(
            this,
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByProp(propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                )
        )
    }

    override fun sort(
        entityType: String,
        propertyName: String,
        rightOrder: EntityIterable,
        ascending: Boolean
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterableImpl(
            this,
            rightOrder.asGremlinIterable().query
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByProp(propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                )
        )
    }

    fun sortLinked(
        entityType: String,
        linkName: String,
        propertyName: String,
        ascending: Boolean
    ): EntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.query(
            this,
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByLinked(linkName, propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                )
        )
    }

    fun sortLinked(
        entityType: String,
        linkName: String,
        propertyName: String,
        rightOrder: EntityIterable,
        ascending: Boolean
    ): EntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterableImpl(
            this,
            rightOrder.asGremlinIterable().query
                .then(
                    GremlinBlock.Sort(
                        GremlinBlock.Sort.ByLinked(linkName, propertyName),
                        if (ascending) SortDirection.ASC else SortDirection.DESC
                    )
                )
        )
    }

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable
    ): GremlinEntityIterable {
        requireActiveTransaction()
        return GremlinEntityIterable.query(
            this,
            sortedLinks.asGremlinIterable().query
                .then(GremlinBlock.InLink(linkName))
                .intersect(rightOrder.asGremlinIterable().query)
                .then(GremlinBlock.HasLabel(entityType))
        )
    }

    override fun sortLinks(
        entityType: String,
        sortedLinks: EntityIterable,
        isMultiple: Boolean,
        linkName: String,
        rightOrder: EntityIterable,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): GremlinEntityIterable {
        requireActiveTransaction()
        // todo: check if we need this
        // Not sure about skipping oppositeEntityType and oppositeLinkName values
        return GremlinEntityIterable.query(
            this,
            sortedLinks.asGremlinIterable().query
                .then(GremlinBlock.InLink(linkName))
                .intersect(rightOrder.asGremlinIterable().query)
                .then(GremlinBlock.HasLabel(entityType))
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

    override fun toEntityId(representation: String): YTDBEntityId {
        requireActiveTransaction()
        val legacyId = PersistentEntityId.toEntityId(representation)
        val oEntityId = store.requireOEntityId(legacyId)
        return if (oEntityId == RIDEntityId.EMPTY_ID) {
            RIDEntityId(
                legacyId.typeId, legacyId.localId,
                ImmutableRecordId.EMPTY_RECORD_ID, null
            )
        } else {
            oEntityId
        }
    }

    override fun getSequence(sequenceName: String): Sequence {
        return getSequence(sequenceName, -1)
    }

    override fun getSequence(sequenceName: String, initialValue: Long): Sequence {
        val session = activeYtdbSession()
        // make sure the OSequence created
        schemaBuddy.getOrCreateSequence(session, sequenceName, initialValue)
        return YTDBSequenceImpl(session as DatabaseSessionInternal, sequenceName, store)
    }

    override fun getOSequence(sequenceName: String): DBSequence {
        return schemaBuddy.getSequence(activeYtdbSession(), sequenceName)
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
        require(policy is YTDBQueryCancellingPolicy) { "Only OQueryCancellingPolicy is supported, but was ${policy?.javaClass?.simpleName}" }
        this.queryCancellingPolicy = policy
    }

    override fun getQueryCancellingPolicy() = this.queryCancellingPolicy

    override fun getOEntityId(entityId: PersistentEntityId): YTDBEntityId {
        return schemaBuddy.getOEntityId(activeYtdbSession(), entityId)
    }

    override fun getTypeId(entityType: String): Int {
        return schemaBuddy.getTypeId(activeYtdbSession(), entityType)
    }

    override fun getType(entityTypeId: Int): String {
        return schemaBuddy.getType(activeYtdbSession(), entityTypeId)
    }
}
