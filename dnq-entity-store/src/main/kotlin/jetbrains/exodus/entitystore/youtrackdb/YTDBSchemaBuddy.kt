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
     * the rest of that transaction. Subsequent DDL from the same thread on another session then
     * fails loudly at `MetadataWriteMutex.engage` - but ONLY if that DDL runs in a transaction:
     * a NON-transactional schema write takes no mutex at all and is silently clobbered when this
     * transaction promotes its tx-local schema copy at commit (see the warning on
     * `YTDBModelMetaData.onRemoveAssociation`, the one remaining non-transactional path).
     * Sites that hold a session guard against the transactional case (see [getOrCreateEdgeClass]);
     * the combination with the association callbacks, which get no session, is declared
     * unsupported (AD11).
     */
    override fun renameOClass(session: DatabaseSessionEmbedded, oldName: String, newName: String) {
        session.requireTxForDDL("renameOClass")
        val oldClass = session.schema.getClass(oldName)
            ?: throw IllegalArgumentException("Class $oldName not found")
        oldClass.setName(newName)
    }

    /**
     * Drops the class in the CALLER's transaction (XD-1283, site 6) - see [renameOClass] for
     * the rationale and the consequences of joining the caller's transaction.
     */
    override fun deleteOClass(session: DatabaseSessionEmbedded, name: String) {
        session.requireTxForDDL("deleteOClass")
        if (session.schema.getClass(name) != null) {
            session.schema.dropClass(name)
        }
    }

    private fun DatabaseSessionEmbedded.requireTxForDDL(operation: String) {
        check(isTxActive) {
            "$operation requires an active transaction: schema operations must run in " +
                    "transactional context (XD-1283)"
        }
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
         * state (site-6 rename/deleteOClass joined it), DDL in a side-session transaction would
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
        /*
         * The cached collection id is validated against the class it resolves to: a dropped
         * class leaves its entry behind, and YTDB reuses collection ids, so an unvalidated hit
         * can name a class of a completely different type (whose entities would then be handed
         * out under this type id). Same reasoning as the cache-hit validation in [getType].
         */
        if (oClass.classIdOrNull() != classId) return null

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
            return session.resolveTypeClass(entityTypeId).name
        }
        /*
         * A cache hit is validated against the schema rather than trusted (XD-1283): a rename or
         * a drop leaves a cached name behind, and no eviction can cover that reliably - the DDL
         * rides a transaction, so a concurrent lookup between the DDL and its commit would just
         * re-cache the name that is still committed at that moment. Validating on read keeps the
         * cache self-healing instead of poisoned until the process restarts, and it never turns a
         * live entry into a miss (which is what evicting did: a miss makes
         * resolveEntityIdOrNull give up on entities that do exist).
         */
        val cached = classIdToOClassId[entityTypeId]
        if (cached != null && session.schema.getClass(cached.second)?.classIdOrNull() == entityTypeId) {
            return cached.second
        }
        val oClass = session.resolveTypeClass(entityTypeId)
        // The cached pair's first element is the COLLECTION id - the same slot semantics
        // scanClasses writes and resolveEntityIdOrNull reads (getClassByCollectionId).
        classIdToOClassId[entityTypeId] = oClass.collectionIds[0] to oClass.name
        return oClass.name
    }

    private fun DatabaseSessionEmbedded.resolveTypeClass(entityTypeId: Int): SchemaClass {
        return schema.classes.find { oClass ->
            oClass.classIdOrNull() == entityTypeId
        } ?: throw EntityRemovedInDatabaseException("Invalid type ID $entityTypeId")
    }

    /**
     * The class's DNQ type id, or null when it has none (edge classes and YTDB's own internal
     * classes do not). Deliberately null-tolerant: it is used to VALIDATE a cached name, and a
     * name freed by a rename may since have been taken by a class without a type id.
     */
    private fun SchemaClass.classIdOrNull(): Int? = getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME)?.toInt()

    override fun requireTypeExists(session: DatabaseSessionEmbedded, entityType: String) {
        val oClass = session.schema.getClass(entityType)
        check(oClass != null) { "$entityType has not been found" }
    }

}

fun DatabaseSessionEmbedded.createClassIdSequenceIfAbsent(startFrom: Long = -1L) {
    createSequencesIfAbsent(listOf(CLASS_ID_SEQUENCE_NAME), startFrom)
}

fun DatabaseSessionEmbedded.createLocalEntityIdSequenceIfAbsent(
    oClass: SchemaClass,
    startFrom: Long = -1L
) {
    // Only the class NAME crosses into the side-session sequence call below - never a
    // (potentially tx-local) SchemaClass proxy obtained inside an open schema transaction.
    createSequencesIfAbsent(listOf(localEntityIdSequenceName(oClass.name)), startFrom)
}

/**
 * Creates every sequence of [sequenceNames] that does not exist yet, all of them in ONE short,
 * immediately-committed transaction on an independent session (XD-1283 performance): the schema
 * pass of a model with hundreds of entity types needs one localEntityId sequence per type, and
 * creating them one at a time cost one transaction - hence one storage commit - each.
 *
 * Why an independent session and not the caller's transaction (XD-1283/AD1, unchanged):
 * `sequence.next()` self-hoists to a pooled session that can only see committed records, so a
 * sequence record must be committed before its first use; and `SequenceLibraryImpl.createSequence`
 * caches the new sequence eagerly, before the surrounding transaction commits, so a rollback would
 * leave the library cache pointing at a record that never existed. Keeping creation in its own
 * immediately-committed transaction avoids both.
 *
 * `DBSequence`'s constructor runs `session.computeInTx`, which JOINS an already active transaction
 * (`DatabaseSessionEmbedded.begin()` nests, `finishTx` commits only the outermost frame) - that is
 * what makes the batch a single commit.
 *
 * Post-genesis this is DDL-free (the OSequence class exists from database creation), so it cannot
 * conflict with a schema transaction holding the metadata-write mutex on the caller's session. On
 * a genesis database the first call creates the OSequence class, which is why the schema pass runs
 * this BEFORE its own first DDL write.
 */
fun DatabaseSessionEmbedded.createSequencesIfAbsent(
    sequenceNames: Collection<String>,
    startFrom: Long = -1L
) = createSequencesIfAbsent(sequenceNames.associateWith { startFrom })

/**
 * [createSequencesIfAbsent] with a per-sequence start value.
 */
fun DatabaseSessionEmbedded.createSequencesIfAbsent(sequenceStarts: Map<String, Long>) {
    val sequences = metadata.sequenceLibrary
    val missing = sequenceStarts.filterKeys { sequences.getSequence(it) == null }
    if (missing.isEmpty()) return

    copy().use { sideSession ->
        val sideSequences = sideSession.metadata.sequenceLibrary
        try {
            sideSession.withTx {
                for ((sequenceName, startFrom) in missing) {
                    if (sideSequences.getSequence(sequenceName) != null) continue
                    val params = DBSequence.CreateParams().setStart(startFrom)
                    sideSequences.createSequence(sequenceName, DBSequence.SEQUENCE_TYPE.ORDERED, params)
                }
            }
        } catch (e: Throwable) {
            /*
             * `SequenceLibraryImpl.createSequence` caches the new sequence as soon as it is
             * created, before this transaction commits, so a failure part-way through the batch
             * would leave the (shared) library holding sequences whose records the rollback
             * removed - and every later `getSequence` would hand out a handle to a record that
             * does not exist. Reloading the library from the database restores it to the committed
             * truth. With one transaction per sequence this could poison at most one entry; the
             * batch makes the recovery worth doing explicitly.
             */
            try {
                sideSequences.load()
            } catch (reloadException: Throwable) {
                e.addSuppressed(reloadException)
            }
            throw e
        }
    }
}

/**
 * Bootstrap helper (XD-1283): the `setCustom` DDL write rides the caller's entry-point
 * transaction. `sequence.next()` is deliberately NOT wrapped in any additional transaction -
 * it self-hoists to a pooled session internally (which can only see committed sequence
 * records; the sequence is guaranteed committed by the side-tx creation helpers above).
 *
 * Pass a [reservation] to take the id from a pre-reserved block instead: every `next()` call is a
 * transaction of its own on a pooled session (`DBSequence.callRetry` does `db.copy()` +
 * `computeInTx`), so a schema pass over hundreds of types paid one storage commit per type just to
 * hand out class ids - see [ClassIdReservation].
 */
fun DatabaseSessionEmbedded.setClassIdIfAbsent(
    oClass: SchemaClass,
    reservation: ClassIdReservation? = null
) {
    if (oClass.getCustom(CLASS_ID_CUSTOM_PROPERTY_NAME) == null) {
        val classId = reservation?.nextClassId(this) ?: classIdSequence().next(this)
        oClass.setCustom(CLASS_ID_CUSTOM_PROPERTY_NAME, classId.toString())
    }
}

private fun DatabaseSessionEmbedded.classIdSequence(): DBSequence =
    metadata.sequenceLibrary.getSequence(CLASS_ID_SEQUENCE_NAME)
        ?: throw IllegalStateException("$CLASS_ID_SEQUENCE_NAME not found")

/**
 * Hands out class ids from a block reserved with a single bump of the classId sequence instead of
 * one `sequence.next()` transaction per class (XD-1283 performance).
 *
 * The block is reserved LAZILY, on the first id actually handed out, and deliberately so: the
 * reservation is only safe once the caller's transaction holds YTDB's single-permit metadata write
 * mutex, which it does from its first schema write onwards. Every path that consumes a class id
 * creates or alters a class first, so by the time the first id is requested the mutex is held and
 * no other thread can be inside `setClassIdIfAbsent` (it would block on its own DDL) - which is
 * what makes reading the sequence's current value and jumping it by [count] in one step safe here
 * while it would be racy on its own.
 *
 * Both the read and the bump run on independent sessions in their own short transactions - reading
 * because `DBSequence.current` self-hoists to a pooled session (`callRetry` -> `db.copy()`), and
 * writing because the caller's transaction cannot see a sequence record committed after it began
 * (which is exactly the case for the sequences [createSequencesIfAbsent] just created). Committing
 * the bump immediately also means a later rollback of the schema pass leaves the consumed ids
 * behind as a gap rather than handing them out twice, which is the harmless direction.
 *
 * Not thread-safe: one instance belongs to one schema pass on one session.
 *
 * @param count how many ids the pass expects to need. Handing out more than that is not an error -
 * the extra ids fall back to `sequence.next()` - it only costs a transaction per extra id.
 */
class ClassIdReservation(private val count: Int) {

    private var nextId = 0L
    private var remaining = 0
    private var reserved = false

    internal fun nextClassId(session: DatabaseSessionEmbedded): Long {
        if (!reserved) {
            reserved = true
            if (count > 0) {
                val sequence = session.classIdSequence()
                val current = sequence.current(session)
                session.copy().use { sideSession ->
                    sideSession.withTx {
                        sequence.updateParams(
                            sideSession,
                            DBSequence.CreateParams().setCurrentValue(current + count)
                        )
                    }
                }
                nextId = current + 1
                remaining = count
            }
        }
        if (remaining > 0) {
            remaining--
            return nextId++
        }
        return session.classIdSequence().next(session)
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
