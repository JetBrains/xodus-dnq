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
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.SortDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.StringCompare
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterableImpl
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.env.ReadonlyTransactionException
import java.util.*
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
        val ytdbId = id.asOId()
        if (ytdbId == RIDEntityId.EMPTY_YTDB_ID) {
            throw EntityRemovedInDatabaseException(id.getTypeName(), id)
        }
        return loadVertexOrNull(ytdbId) ?: throw EntityRemovedInDatabaseException(id.getTypeName(), id)
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

    override fun getAll(entityType: String): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, this, GremlinBlock.All)
    }

    override fun getSingletonIterable(entity: Entity): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            this, GremlinQuery.all
                .then(GremlinBlock.IdEqual((entity.id as YTDBEntityId).asOId()))
        )
    }

    override fun find(
        entityType: String,
        propertyName: String,
        value: Comparable<Nothing>
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, this, GremlinBlock.PropEqual(propertyName, value))
    }

    override fun find(
        entityType: String,
        propertyName: String,
        minValue: Comparable<Nothing>,
        maxValue: Comparable<Nothing>
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
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
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            this,
            GremlinBlock.MatchStringProp(
                propertyName,
                StringCompare.Substring,
                value,
                isCollection = false,
                caseSensitive = !ignoreCase
            )
        )
    }

    override fun findStartingWith(
        entityType: String,
        propertyName: String,
        value: String
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            this,
            GremlinBlock.MatchStringProp(
                propertyName,
                StringCompare.Prefix,
                value,
                isCollection = false,
                caseSensitive = false
            )
        )
    }

    override fun findIds(entityType: String, minValue: Long, maxValue: Long): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(
            entityType,
            this,
            GremlinBlock.PropInRange(LOCAL_ENTITY_ID_PROPERTY_NAME, minValue, maxValue)
        )
    }

    override fun findWithProp(entityType: String, propertyName: String): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, this, GremlinBlock.PropNotNull(propertyName))
    }

    override fun findWithPropSortedByValue(
        entityType: String,
        propertyName: String
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
            this,
            GremlinQuery.all
                .then(GremlinBlock.HasLabel(entityType))
                .then(GremlinBlock.PropNotNull(propertyName))
                // todo: move SortBy out of Condition
                .then(GremlinBlock.Sort(GremlinBlock.Sort.ByProp(propertyName), SortDirection.ASC))
        )
    }

    override fun findWithBlob(entityType: String, blobName: String): YTDBEntityIterable {
        return findWithProp(entityType, blobName)
    }

    override fun findLinks(entityType: String, entity: Entity, linkName: String): YTDBEntityIterable {
        requireActiveTransaction()

        return YTDBEntityIterable.query(
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
    ): YTDBEntityIterable {
        requireActiveTransaction()

        return if (entities === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterable.query(
            this,
            entities.asYTDBIterable().query
                .then(GremlinBlock.InLink(linkName))
                .then(GremlinBlock.HasLabel(entityType))
        )
    }

    override fun findWithLinks(entityType: String, linkName: String): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, this, GremlinBlock.HasLink(linkName))
    }

    override fun findWithLinks(
        entityType: String,
        linkName: String,
        oppositeEntityType: String,
        oppositeLinkName: String
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.where(entityType, this, GremlinBlock.HasLink(linkName))
    }

    override fun sort(
        entityType: String,
        propertyName: String,
        ascending: Boolean
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return YTDBEntityIterable.query(
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
    ): YTDBEntityIterable {
        requireActiveTransaction()

        return if (rightOrder === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterableImpl(
            this,
            rightOrder.asYTDBIterable().query
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
        return YTDBEntityIterable.query(
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
        return if (rightOrder === YTDBEntityIterable.EMPTY) return YTDBEntityIterable.EMPTY
        else YTDBEntityIterableImpl(
            this,
            rightOrder.asYTDBIterable().query
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
    ): YTDBEntityIterable {
        requireActiveTransaction()
        return if (rightOrder === YTDBEntityIterable.EMPTY || sortedLinks === YTDBEntityIterable.EMPTY)
            YTDBEntityIterable.EMPTY
        else YTDBEntityIterable.query(
            this,
            sortedLinks.asYTDBIterable().query
                .then(GremlinBlock.InLink(linkName))
                .intersect(rightOrder.asYTDBIterable().query)
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
    ): YTDBEntityIterable {
        requireActiveTransaction()
        // todo: check if we need this
        // Not sure about skipping oppositeEntityType and oppositeLinkName values
        return if (rightOrder === YTDBEntityIterable.EMPTY || sortedLinks === YTDBEntityIterable.EMPTY)
            YTDBEntityIterable.EMPTY
        else YTDBEntityIterable.query(
            this,
            sortedLinks.asYTDBIterable().query
                .then(GremlinBlock.InLink(linkName))
                .intersect(rightOrder.asYTDBIterable().query)
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
                RIDEntityId.EMPTY_YTDB_ID, null
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
        return YTDBSequenceImpl(session as DatabaseSessionEmbedded, sequenceName, store)
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
