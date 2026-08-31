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
package jetbrains.exodus.query.metadata

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddy
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddyImpl
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity

class YTDBModelMetaData(
    private val dbProvider: YTDBDatabaseProvider,
    private val schemaBuddy: YTDBSchemaBuddy = YTDBSchemaBuddyImpl(dbProvider, autoInitialize = false)
) : ModelMetaDataImpl(), YTDBSchemaBuddy by schemaBuddy {

    override fun onPrepared(entitiesMetaData: MutableCollection<EntityMetaData>) {
        dbProvider.withSession { session ->
            /*
             * Startup schema application is transactional (XD-1283):
             * 1. one explicit transaction over the whole schema pass - pure DDL only.
             *    Sequence creation (classId sequence, per-class localEntityId sequences) is
             *    hoisted out of this transaction by the schema-buddy helpers: it runs on an
             *    independent session in a short, immediately-committed transaction, because
             *    sequence.next() self-hoists to a pooled session that can only see committed
             *    records;
             * 2. the complementary-property backfill keeps its own batched data transactions;
             * 3. index creation is dual-mode behind YTDBDatabaseParams.transactionalIndexCreation:
             *    by default (true) one explicit transaction covers all index creation, which
             *    fails at commit for populated classes until YTDB-1064 is lifted; with the flag
             *    off it runs on YTDB's legacy non-transactional path (createIndex + fillIndex
             *    over committed rows - works for populated classes), which is what a database
             *    that already contains data must use. The flag retires when YTDB-1064 is lifted.
             */
            /*
             * EXPERIMENTAL (JT-95771): the caller may declare that this database's schema already
             * matches the model - a test opening a database seeded from a template built by the
             * same model, for instance. The whole application pass is then skipped: on a correct
             * schema it is ~4 transactions of existence checks that write nothing. The store's
             * per-database caches (class-id map) are still initialised below, because those live in
             * the JVM and must be rebuilt for every database.
             */
            if (dbProvider.skipSchemaApplication) {
                initialize(session)
                return@withSession
            }
            val result = session.withTx {
                it.applySchema(
                    entitiesMetaData,
                    // EXPERIMENTAL (JT-95771): `false` drops the automatic index of every
                    // auto-indexed simple property (~3900 of them for a full YouTrack model),
                    // which is the dominant cost of schema application, of the on-disk file count
                    // and of every subsequent database open. Unique/composite indices are
                    // unaffected, so it is a query-plan trade, for test databases only.
                    indexForEverySimpleProperty = dbProvider.autoIndexSimpleProperties,
                    applyLinkCardinality = true,
                    useBatchedSequenceAcquisition = dbProvider.useBatchedSequenceAcquisition
                )
            }
            session.initializeComplementaryPropertiesForNewIndexedLinks(result.newIndexedLinks)
            if (dbProvider.transactionalIndexCreation) {
                session.withTx {
                    it.applyIndices(result.indices)
                }
            } else {
                session.applyIndicesNonTx(result.indices)
            }
            initialize(session)
        }
    }

    override fun onAddAssociation(entityMetaData: EntityMetaData, association: AssociationEndMetaData) {
        applyAssociations(listOf(ModelMetaDataImpl.AddedAssociation(entityMetaData, association)))
    }

    /**
     * The batched counterpart of [onAddAssociation] (XD-1283 performance): every association added
     * inside a `ModelMetaDataImpl.batchAssociations` scope is applied by ONE call, hence one session,
     * one transaction and one commit for the whole delta.
     *
     * This is what makes runtime registration affordable at scale. A single association's DDL is
     * cheap in itself, but the transaction around it is not: YouTrackDB seeds a transaction-local
     * schema copy by re-parsing the whole committed schema (`SchemaShared.copyForTx`), re-parses it
     * again when promoting the copy at commit, and rebuilds the immutable schema snapshot on every
     * schema write - all of it proportional to the total schema size, not to the size of the change.
     * Paying that per association is what dominates a client that registers hundreds of links after
     * startup; paying it once per batch does not.
     */
    override fun onAddAssociations(associations: List<ModelMetaDataImpl.AddedAssociation>) {
        applyAssociations(associations)
    }

    private fun applyAssociations(associations: List<ModelMetaDataImpl.AddedAssociation>) {
        if (associations.isEmpty()) {
            return
        }
        /*
         * Runtime association-add is transactional (XD-1283). No session parameter reaches
         * this callback, so the DDL always runs on a separate session; combining it with
         * rename/deleteOClass DDL in one business transaction is declared unsupported (AD11)
         * and fails loudly with MetadataWriteMutex's same-thread IllegalStateException.
         *
         * Index creation is dual-mode (XD-1283, YTDBDatabaseParams.transactionalIndexCreation):
         * - flag on (the default): single transaction for DDL + index when no backfill is needed (AD10);
         *   three-phase (DDL tx -> batched backfill txs -> index tx, mirroring startup) only
         *   when the new indexed links require the complementary-property backfill (AD4).
         *   In-tx index creation over classes with pre-existing committed rows fails at
         *   commit until YTDB-1064 is lifted - accepted for this mode.
         * - flag off (required for a database that already contains data, until YTDB-1064 is
         *   lifted): DDL tx (+ backfill txs if needed), then indices on the legacy
         *   non-transactional path, which works for populated classes.
         */
        /*
         * EXPERIMENTAL (JT-95771), see YTDBDatabaseParams.skipSchemaApplication: the caller has
         * declared this database's schema already matches the model, so an association whose ends
         * and indices are already there needs no DDL pass. The in-JVM model has been updated by
         * ModelMetaDataImpl before this hook runs, which is the part that must always happen.
         */
        if (dbProvider.skipSchemaApplication) return
        dbProvider.withSession { session ->
            val inTxIndices = dbProvider.transactionalIndexCreation
            val result = session.withTx { sessionToWork ->
                /*
                 * The whole delta's DDL goes into this one transaction; the deferred indices of all
                 * of it are merged and created once, after the last link exists, so an index over a
                 * link added later in the same batch is still covered.
                 */
                val schemaApplicationResult = associations.map { added ->
                    sessionToWork.addAssociation(added.entityMetaData, added.association)
                }.merged()
                if (inTxIndices && schemaApplicationResult.newIndexedLinks.isEmpty()) {
                    // no backfill needed: DDL + index commit atomically in this one tx (AD10)
                    sessionToWork.applyIndices(schemaApplicationResult.indices)
                }
                schemaApplicationResult
            }
            if (result.newIndexedLinks.isNotEmpty()) {
                // backfill keeps its own batched transactions between the DDL and index phases
                session.initializeComplementaryPropertiesForNewIndexedLinks(result.newIndexedLinks)
            }
            when {
                !inTxIndices -> session.applyIndicesNonTx(result.indices)
                result.newIndexedLinks.isNotEmpty() -> session.withTx { it.applyIndices(result.indices) }
                // else: the indices were already created inside the DDL transaction above
            }
        }
    }

    override fun onRemoveAssociation(sourceTypeName: String, targetTypeName: String, associationName: String) {
        /*
         * Documented exception to the transactional-schema contract (XD-1283): association
         * removal stays on the legacy non-tx path (side session, no explicit transaction) -
         * YTDB 0.5.0-dev-2026-07-29 forbids dropProperty under ANY active transaction
         * (SchemaClassEmbedded.java:473/493, no tx-local exemption), unlike
         * createProperty/dropClass/setName which are in-tx-supported. To be lifted when YTDB
         * supports in-tx dropProperty.
         *
         * !! KNOWN HAZARD, ACCEPTED AS TEMPORARY (XD-1283, user decision 2026-07-30) !!
         * A non-transactional schema write takes NO metadata write mutex (MetadataWriteMutex is
         * engaged only by a transaction's first schema write; the gap is acknowledged upstream at
         * IndexManagerEmbedded.java:1762-1764). If this call overlaps ANY transaction that has
         * already written schema - a startup schema pass, an association add, an ad-hoc edge
         * class, a rename/drop riding a business transaction - that transaction's commit promotes
         * a schema copy frozen before this write and clobbers it. Measured on
         * 0.5.0-dev-2026-07-29: overlapping classes = this removal is silently and durably
         * reverted (the dropped property comes back), disjoint classes = silent loss PLUS a
         * storage error state, and a file-based database that can no longer be opened
         * ("NullPointerException ... globalRef is null"). Neither side raises anything; commit()
         * returns normally. A non-tx write that completes BEFORE the transaction's first schema
         * write is safe - the hazard window opens at that first write, not at transaction start.
         *
         * There is no DNQ-side guard here: this callback receives no session, so it cannot even
         * see whether a schema transaction is open. The exposure ends when YTDB supports in-tx
         * dropProperty and this path becomes transactional (upstream ticket needed) - or if
         * upstream makes non-tx DDL engage the mutex.
         */
        dbProvider.withSession { session ->
            session.removeAssociation(sourceTypeName, targetTypeName, associationName)
        }
    }

    override fun getOrCreateEdgeClass(
        session: DatabaseSessionEmbedded,
        linkName: String,
        outClassName: String,
        inClassName: String
    ): SchemaClass {
        /**
         * It is enough to check the existence of the edge class.
         * We reuse the same edge class for all the links with the same name.
         */
        val edgeClassName = YTDBVertexEntity.edgeClassName(linkName)
        val oClass = session.schema.getClass(edgeClassName)
        if (oClass != null) {
            return oClass
        }

        val link = LinkMetadata(
            name = linkName,
            outClassName = outClassName,
            inClassName = inClassName,
            AssociationEndCardinality._0_n
        )

        /*
         * AD3 guard (XD-1283): if the caller's transaction already carries tx-local schema
         * state (site-6 rename/deleteOClass joined it), same-thread side-session DDL would
         * fail loudly at MetadataWriteMutex.engage - so the edge class (and its indices)
         * are created in the caller's transaction instead.
         *
         * This branch is deliberately NOT gated on transactionalIndexCreation: the caller's
         * transaction is open, so the legacy non-tx index path is unreachable here (it
         * requires a session with no active transaction), and the same-tx-created edge class
         * is exempt from YTDB-1064 anyway.
         */
        if (session.txSchemaState != null) {
            val result =
                session.addAssociation(link, indicesContainingLink = listOf(), applyLinkCardinality = false)
            // ad-hoc edge-class paths never need the complementary-property backfill (AD8),
            // so DDL + index stay one atomic unit in the caller's transaction (AD10)
            session.applyIndices(result.indices)
            return session.schema.getClass(edgeClassName)
                ?: throw IllegalStateException("$edgeClassName not found, it must never happen")
        }

        dbProvider.withSession { sessionToWork ->
            /**
             * We do not apply link cardinality because:
             * 1. We do not have any cardinality restrictions for ad-hoc links.
             * 2. Applying the cardinality causes adding extra properties to existing vertices,
             * that in turn potentially causes OConcurrentModificationException in the original session.
             * Keep in mind that we create this edge class (and those extra properties) in a separate session.
             * So, there is an original session with the business logic that may fail if we change vertices here.
             */
            /*
             * One short immediately-committed transaction for DDL + index (XD-1283, AD10):
             * ad-hoc edge-class paths never need the complementary-property backfill (AD8),
             * and a same-tx-created class is exempt from YTDB-1064, so the edge class and its
             * indices commit atomically - a failure rolls both back for a clean retry.
             * Deliberately NOT gated on transactionalIndexCreation: the 1064 exemption makes
             * the in-tx path safe here in both modes, and splitting DDL and index would trade
             * away the atomicity for nothing.
             *
             * Pre-write re-check on the side session: a pre-first-write read resolves the live
             * committed schema, so an edge class committed by a concurrent winner after the
             * caller's check is seen here and the loser short-circuits without paying the
             * metadata mutex, the tx-local schema copy and a forced schema commit. The
             * short-circuit on class existence alone is safe because the winner commits the
             * class and its indices atomically in one transaction. A winner committing
             * between this re-check and our first schema write is tolerated one level lower:
             * the initializer's check-then-create sites (createEdgeClassIfAbsent and friends)
             * catch "already exists" and re-check.
             */
            if (sessionToWork.schema.getClass(edgeClassName) == null) {
                sessionToWork.withTx {
                    val result =
                        it.addAssociation(link, indicesContainingLink = listOf(), applyLinkCardinality = false)
                    it.applyIndices(result.indices)
                }
            }
        }

        return session.schema.getClass(edgeClassName)
            ?: throw IllegalStateException("$edgeClassName not found, it must never happen")
    }
}