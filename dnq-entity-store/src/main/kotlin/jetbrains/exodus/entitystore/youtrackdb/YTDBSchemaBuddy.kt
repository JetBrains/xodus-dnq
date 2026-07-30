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

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException

import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_CUSTOM_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_SEQUENCE_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import java.util.concurrent.ConcurrentHashMap

interface YTDBSchemaBuddy {
    fun initialize(session: DatabaseSessionEmbedded)

    fun resolveEntityIdOrNull(session: DatabaseSessionEmbedded, typeId: Int, localId: Long): RIDEntityId?

    /**
     * If the class has not been found, returns -1. It is how it was in the Classic Xodus.
     */
    fun getTypeId(session: DatabaseSessionEmbedded, entityType: String): Int

    fun getType(session: DatabaseSessionEmbedded, entityTypeId: Int): String

    fun requireTypeExists(session: DatabaseSessionEmbedded, entityType: String)

    fun getOrCreateSequence(
        session: DatabaseSessionEmbedded,
        sequenceName: String,
        initialValue: Long
    ): DBSequence

    fun getSequence(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence

    fun getSequenceOrNull(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence?

    fun updateSequence(session: DatabaseSessionEmbedded, sequenceName: String, currentValue: Long)

    fun renameOClass(session: DatabaseSessionEmbedded, oldName: String, newName: String)

    fun deleteOClass(session: DatabaseSessionEmbedded, name: String)

    fun getOrCreateEdgeClass(
        session: DatabaseSessionEmbedded,
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass
}

class YTDBSchemaBuddyImpl(
    private val dbProvider: YTDBDatabaseProvider,
    autoInitialize: Boolean = true,
) : YTDBSchemaBuddy {
    companion object {
        val INTERNAL_CLASS_NAMES = hashSetOf(SchemaClass.VERTEX_CLASS_NAME)
    }

    private val classIdToOClassId = ConcurrentHashMap<Int, Pair<Int, String>>()

    init {
        if (autoInitialize) {
            dbProvider.withSession(this::initialize)
        }
    }

    override fun initialize(session: DatabaseSessionEmbedded) {
        // sequence creation runs in its own short immediately-committed side-tx internally (XD-1283)
        session.createClassIdSequenceIfAbsent()
        /*
         * The schema scan keeps its own short transaction (XD-1283): schema access is
         * transactional under YTDB's transactional schema. If the caller has already opened
         * a transaction (e.g. the migrator launcher), the scan simply rides it.
         */
        if (session.isTxActive) {
            scanClasses(session)
        } else {
            session.withTx(this::scanClasses)
        }
    }

    private fun scanClasses(session: DatabaseSessionEmbedded) {
        for (oClass in session.schema.classes) {
            if (oClass.isVertexType && !INTERNAL_CLASS_NAMES.contains(oClass.name)) {
                classIdToOClassId[oClass.requireClassId()] = oClass.collectionIds[0] to oClass.name
            }
        }
    }

    override fun getOrCreateSequence(
        session: DatabaseSessionEmbedded,
        sequenceName: String,
        initialValue: Long
    ): DBSequence {
        val oSequence =
            (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.getSequence(sequenceName)
        if (oSequence != null) return oSequence


        return dbProvider.withSession { dbSession ->
            val params = DBSequence.CreateParams().setStart(initialValue).setIncrement(1)
            (dbSession as DatabaseSessionEmbedded).metadata.sequenceLibrary.createSequence(
                sequenceName,
                DBSequence.SEQUENCE_TYPE.ORDERED,
                params
            )
        }
    }

    /**
     * Renames the class in the CALLER's transaction (XD-1283, site 6) - the sole sanctioned
     * exception to "business transactions never perform DDL on their own session".
     *
     * The DDL now commits and rolls back with the business transaction, which fixes two latent
     * bugs of the previous separate-session-with-immediate-commit implementation: the rename
     * leaked out of a rolled-back business transaction, and a `NeedRetryException` replay
     * re-executed it against the already-renamed schema.
     *
     * Joining the caller's transaction engages YTDB's single-permit metadata write mutex for
     * the rest of that transaction, so subsequent same-thread side-session DDL fails loudly at
     * `MetadataWriteMutex.engage`. Sites that hold a session guard against this (see
     * [getOrCreateEdgeClass]); the combination with the association callbacks, which get no
     * session, is declared unsupported (AD11).
     */
    override fun renameOClass(session: DatabaseSessionEmbedded, oldName: String, newName: String) {
        session.requireTxForDDL("renameOClass")
        val oldClass = session.schema.getClass(oldName)
            ?: throw IllegalArgumentException("Class $oldName not found")
        oldClass.setName(newName)
        evictCachedClassName(oldName)
    }

    /**
     * Drops the class in the CALLER's transaction (XD-1283, site 6) - see [renameOClass] for
     * the rationale and the consequences of joining the caller's transaction.
     */
    override fun deleteOClass(session: DatabaseSessionEmbedded, name: String) {
        session.requireTxForDDL("deleteOClass")
        if (session.schema.getClass(name) != null) {
            session.schema.dropClass(name)
            evictCachedClassName(name)
        }
    }

    private fun DatabaseSessionEmbedded.requireTxForDDL(operation: String) {
        check(isTxActive) {
            "$operation requires an active transaction: schema operations must run in " +
                    "transactional context (XD-1283)"
        }
    }

    /**
     * Drops the classId -> (collectionId, name) cache entry of a class whose name is about to
     * change or disappear.
     *
     * Since the DDL rides the caller's transaction (XD-1283 site 6), the cached name is stale
     * only once that transaction commits - but evicting eagerly is correct for both outcomes:
     * the entry is re-resolved from the schema on the next miss, which is the committed schema
     * after a rollback and the new one after a commit. It does not fully close the window: a
     * concurrent [getType] miss between this eviction and the commit re-caches the still
     * committed old name (bounded by the next miss after the commit).
     */
    private fun evictCachedClassName(className: String) {
        classIdToOClassId.entries.removeIf { (_, value) -> value.second == className }
    }

    override fun getOrCreateEdgeClass(
        session: DatabaseSessionEmbedded,
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass {
        val edgeClassName = YTDBVertexEntity.edgeClassName(linkName)
        val oClass = session.schema.getClass(edgeClassName)
        if (oClass != null) return oClass

        /*
         * AD3 guard (XD-1283): if the caller's transaction already carries tx-local schema
         * state (site-6 rename/deleteOClass joined it), same-thread side-session DDL would
         * fail loudly at MetadataWriteMutex.engage - so the edge class is created in the
         * caller's transaction instead.
         */
        if (session.txSchemaState != null) {
            return session.createEdgeClassCatchingRace(edgeClassName)
        }

        /*
         * Hot data path: never join the (potentially long-running) caller transaction with
         * DDL - the edge class is created on a separate session in a short, immediately
         * committed transaction (XD-1283).
         */
        dbProvider.withSession { sessionToWork ->
            /*
             * Pre-write re-check on the side session (XD-1283): a pre-first-write read
             * resolves the live committed schema, so a class committed by a concurrent winner
             * after the caller's check is seen here and the loser short-circuits without
             * paying the metadata mutex, the tx-local schema copy and a forced schema commit.
             */
            if (sessionToWork.schema.getClass(edgeClassName) == null) {
                sessionToWork.withTx {
                    it.createEdgeClassCatchingRace(edgeClassName)
                }
            }
        }

        return session.schema.getClass(edgeClassName)
            ?: throw IllegalStateException("Class $edgeClassName could not be created")
    }

    /**
     * Creates the edge class, tolerating the concurrent-creation race: another session may
     * commit the same class between the caller's existence check and `createEdgeClass`, which
     * throws a [SchemaException] ("... already exists ...") - in that case the freshly created
     * class is re-read and returned instead of failing (XD-1283).
     */
    private fun DatabaseSessionEmbedded.createEdgeClassCatchingRace(edgeClassName: String): SchemaClass {
        return try {
            schema.createEdgeClass(edgeClassName)
        } catch (e: SchemaException) {
            schema.getClass(edgeClassName) ?: throw e
        }
    }

    override fun getSequence(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence {
        return (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.getSequence(
            sequenceName
        )
            ?: throw IllegalStateException("$sequenceName sequence not found")
    }

    override fun getSequenceOrNull(session: DatabaseSessionEmbedded, sequenceName: String): DBSequence? {
        return (session as DatabaseSessionEmbedded).metadata.sequenceLibrary.getSequence(
            sequenceName
        )
    }

    override fun updateSequence(
        session: DatabaseSessionEmbedded,
        sequenceName: String,
        currentValue: Long
    ) {
        dbProvider.withSession { sessionToWork ->
            sessionToWork.begin();
            getSequence(sessionToWork, sequenceName).updateParams(
                sessionToWork as DatabaseSessionEmbedded,
                DBSequence.CreateParams().setCurrentValue(
                    currentValue
                )
            )
            sessionToWork.commit()
        }
    }

    override fun resolveEntityIdOrNull(
        session: DatabaseSessionEmbedded,
        typeId: Int,
        localId: Long
    ): RIDEntityId? {
        // Keep in mind that it is possible that we are given an entityId that is not in the database.
        // It is a valid case: we return null to signal "not found".

        val classId = typeId
        val localEntityId = localId
        val oClassId = classIdToOClassId[classId]?.first ?: return null
        val schema = session.schema
        val oClass = schema.getClassByCollectionId(oClassId) ?: return null

        val oid = session.activeTransaction
            .query("SELECT FROM ${oClass.name} WHERE $LOCAL_ENTITY_ID_PROPERTY_NAME = ?", localEntityId)
            .use { resultSet ->
                if (resultSet.hasNext()) {
                    resultSet.next().asVertexOrNull()?.identity ?: return null
                } else {
                    return null
                }
            }

        return RIDEntityId(classId, localEntityId, oid, oClass.name)
    }

    override fun getTypeId(session: DatabaseSessionEmbedded, entityType: String): Int {
        return session.schema.getClass(entityType)?.requireClassId() ?: -1
    }

    override fun getType(
        session: DatabaseSessionEmbedded,
        entityTypeId: Int
    ): String {
        /*
         * Never memoize a name resolved from a tx-local schema (XD-1283): once the caller's
         * transaction has written schema (site 6 rename/deleteOClass joined it), schema reads
         * on that session resolve its uncommitted tx-local copy. Caching that name would
         * outlive a rollback and poison every later lookup - so this resolution stays local to
         * the transaction that can see it.
         */
        if (session.txSchemaState != null) {
            return session.resolveTypeName(entityTypeId)
        }
        val (_, typeName) = classIdToOClassId.computeIfAbsent(entityTypeId) {
            val oClass = session.schema.classes.find { oClass ->
                oClass.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME)?.toInt() == entityTypeId
            } ?: throw EntityRemovedInDatabaseException("Invalid type ID $entityTypeId")
            oClass.requireClassId() to oClass.name
        }
        return typeName
    }

    private fun DatabaseSessionEmbedded.resolveTypeName(entityTypeId: Int): String {
        val oClass = schema.classes.find { oClass ->
            oClass.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME)?.toInt() == entityTypeId
        } ?: throw EntityRemovedInDatabaseException("Invalid type ID $entityTypeId")
        return oClass.name
    }

    override fun requireTypeExists(session: DatabaseSessionEmbedded, entityType: String) {
        val oClass = session.schema.getClass(entityType)
        check(oClass != null) { "$entityType has not been found" }
    }

}

fun DatabaseSessionEmbedded.createClassIdSequenceIfAbsent(startFrom: Long = -1L) {
    createSequenceIfAbsent(CLASS_ID_SEQUENCE_NAME, startFrom)
}

fun DatabaseSessionEmbedded.createLocalEntityIdSequenceIfAbsent(
    oClass: SchemaClass,
    startFrom: Long = -1L
) {
    // Only the class NAME crosses into the side-session sequence call below - never a
    // (potentially tx-local) SchemaClass proxy obtained inside an open schema transaction.
    createSequenceIfAbsent(localEntityIdSequenceName(oClass.name), startFrom)
}

private fun DatabaseSessionEmbedded.createSequenceIfAbsent(sequenceName: String, startFrom: Long = 0L) {
    if (metadata.sequenceLibrary.getSequence(sequenceName) != null) return

    /*
     * Sequence creation must never join a (potentially long-running) caller transaction
     * (XD-1283): sequence.next() self-hoists to a pooled session that can only see committed
     * records, so the sequence record must be committed before its first use. Therefore the
     * sequence is created on an independent session (for pooled sessions copy() == pool.acquire())
     * in a short, immediately-committed transaction - createSequence manages its own transaction
     * via computeInTx on a session with no active transaction.
     *
     * Sequence creation is DDL-free post-genesis (the OSequence class exists from database
     * creation), so it cannot conflict with a schema transaction holding the metadata-write
     * mutex on the caller's session.
     *
     * Note: sequence.next() itself must NOT be wrapped in any additional transaction here -
     * it already runs on a pooled session internally.
     */
    copy().use { sideSession ->
        val sequences = sideSession.metadata.sequenceLibrary
        if (sequences.getSequence(sequenceName) == null) {
            val params = DBSequence.CreateParams()
            params.start = startFrom
            sequences.createSequence(sequenceName, DBSequence.SEQUENCE_TYPE.ORDERED, params)
        }
    }
}

/**
 * Bootstrap helper (XD-1283): the `setCustom` DDL write rides the caller's entry-point
 * transaction. `sequence.next()` is deliberately NOT wrapped in any additional transaction -
 * it self-hoists to a pooled session internally (which can only see committed sequence
 * records; the sequence is guaranteed committed by the side-tx creation helpers above).
 */
fun DatabaseSessionEmbedded.setClassIdIfAbsent(oClass: SchemaClass) {
    if (oClass.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME) == null) {
        val sequences = (this as DatabaseSessionEmbedded).metadata.sequenceLibrary
        val sequence: DBSequence = sequences.getSequence(CLASS_ID_SEQUENCE_NAME)
            ?: throw IllegalStateException("$CLASS_ID_SEQUENCE_NAME not found")

        oClass.setCustom(CLASS_ID_CUSTOM_PROPERTY_NAME, sequence.next(this).toString())
    }
}

fun setLocalEntityId(tx: YTDBStoreTransaction, className: String, vertex: YTDBVertex) {
    val sequenceName = localEntityIdSequenceName(className)
    val id = tx.getSequenceNextValue(sequenceName)
    vertex.property(LOCAL_ENTITY_ID_PROPERTY_NAME, id)
}

/**
 * Bootstrap helper (XD-1283): all pure DDL here (`createVertexClass`, `setClassIdIfAbsent`)
 * rides the caller's entry-point transaction; only sequence creation is hoisted into short
 * immediately-committed side-txs by `createClassIdSequenceIfAbsent` /
 * `createLocalEntityIdSequenceIfAbsent`.
 */
fun DatabaseSessionEmbedded.createVertexClassWithClassId(className: String): SchemaClass {
    createClassIdSequenceIfAbsent()
    val oClass = schema.createVertexClass(className)
    setClassIdIfAbsent(oClass)
    createLocalEntityIdSequenceIfAbsent(oClass)
    return oClass
}

/**
 * Bootstrap helper (XD-1283): the DDL rides the caller's entry-point transaction - see
 * [createVertexClassWithClassId].
 */
internal fun DatabaseSessionEmbedded.getOrCreateVertexClass(className: String): SchemaClass {
    val existingClass = this.schema.getClass(className)
    if (existingClass != null) return existingClass

    return createVertexClassWithClassId(className)
}
