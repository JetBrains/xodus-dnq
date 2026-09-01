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

import YTDBDatabaseProviderFactory
import YouTrackDBFactory
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseParams
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddyImpl
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.edgeClassName
import jetbrains.exodus.entitystore.youtrackdb.withTx
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.absolutePathString

/**
 * Artificial schema-initialization benchmark (XD-1283 performance work).
 *
 * Not part of the regular suite - on the slow path it takes minutes. Run it explicitly:
 *
 * ```
 * DNQ_BENCH=true DNQ_BENCH_CLASSES=300 DNQ_BENCH_DBTYPE=MEMORY \
 *     ./gradlew :dnq-query:test --tests '*SchemaInitBenchmark*'
 * ```
 *
 * Configuration comes from the environment (a Gradle `-D` would land in the Gradle JVM, not in
 * the test JVM), with a system-property fallback for IDE runs:
 * - `DNQ_BENCH` / `dnq.bench` (required, any value) - enables the benchmark
 * - `DNQ_BENCH_CLASSES` / `dnq.bench.classes` (default 300) - number of DNQ entity types
 * - `DNQ_BENCH_PROPERTIES` / `dnq.bench.properties` (default 10) - simple properties per type
 * - `DNQ_BENCH_DBTYPE` / `dnq.bench.dbtype` (default MEMORY) - MEMORY or DISK
 * - `DNQ_BENCH_TX_INDICES` / `dnq.bench.txIndices` (default false) - benchmark-only choice
 *   between creating ALL indices in ONE transaction and using the legacy per-index path. The
 *   production path uses the index-mode preflight rather than this direct A/B switch.
 * - `DNQ_BENCH_LATE_LINKS` / `dnq.bench.lateLinks` (default 50) - number of associations the
 *   runtime (post-startup) association-add benchmarks register after the schema is applied
 * - `DNQ_BENCH_HEAP` / `dnq.bench.heap` (default false) - measure the heap around the index
 *   pass of `single-pass schema application on a fresh database` (used heap before, PEAK heap
 *   during, used heap after, all post-GC where meaningful). Off by default because the forced
 *   collections it needs would perturb the timings.
 * - `DNQ_BENCH_FSYNC` / `dnq.bench.fsync` - overrides `youtrackdb.storage.callFsync`
 * - `DNQ_BENCH_COLLECTIONS` / `dnq.bench.collections` - overrides
 *   `youtrackdb.class.collectionsCount` (default 8; each collection costs 5 storage files, and
 *   every created file costs 2 fsyncs, so this dominates on-disk initialization)
 * - `DNQ_BENCH_PROFILE` / `dnq.bench.profile` - sample the measured phases and print the hottest
 *   YTDB/DNQ frames
 * - `DNQ_BENCH_INDEX_BATCH` / `dnq.bench.indexBatch` (default 0 = one transaction for the whole
 *   pass, i.e. today's production shape) - create the index definitions in chunks of this many
 *   definitions, one transaction per chunk. For the k-sweep that looks for the total-time optimum:
 *   the overlay scan's cost is invariant in the chunk count, the per-transaction floor grows with
 *   it, and `populateTxCreatedIndex`'s per-index walk of the transaction's own record-operation
 *   list shrinks with it (N^2/k), so an interior optimum can exist.
 * - `DNQ_BENCH_MERGE_INDEX_TX` / `dnq.bench.mergeIndexTx` (default false) - create the index
 *   definitions INSIDE the schema (DDL) transaction instead of a separate one, i.e. one transaction
 *   for classes + properties + links + indexes. On the pre-F1 engine this loses (the class-creating
 *   commit rebuilds the immutable schema twice, and both rebuilds then resolve every class against
 *   the transaction's ~3900-entry index overlay); the point of the knob is to re-ask the question on
 *   an engine where that scan is O(1) per class. The complementary-property backfill stays after the
 *   merged commit, as it needs committed schema. Requires `TX_INDICES=true` to take effect; when
 *   transactional index creation is disabled, this and the other shaping knobs are ignored. It
 *   cannot be combined with `INDEX_BATCH` or `INDEX_TAIL_TXS` when transactional mode is enabled,
 *   because those probes require separate transactions.
 * - `DNQ_BENCH_INDEX_TAIL_TXS` / `dnq.bench.indexTailTxs` (default 0) - create all but the last N
 *   definitions in one transaction, then each of those N in its OWN transaction. This measures the
 *   MARGINAL cost of one extra schema-carrying transaction at this schema size (the `A+F` term of
 *   the sweep model: tx-local schema seed + commit-entry rebuild + promotion + carry publish),
 *   without paying the whole grid: the delta against a `0` run, divided by N, is that cost.
 *   Ignored unless `TX_INDICES=true`; mutually exclusive with `INDEX_BATCH` and `MERGE_INDEX_TX`.
 *
 * Numeric shape constraints are checked before any database is created: `CLASSES` must be positive,
 * `PROPERTIES` must be at least 2 because the model always declares a `prop0`+`prop1` composite
 * index, and `LATE_LINKS`, `INDEX_BATCH`, and `INDEX_TAIL_TXS` must be non-negative.
 */
class SchemaInitBenchmark {

    private val classCount = config("CLASSES", "classes", "300").toInt()
    private val propertyCount = config("PROPERTIES", "properties", "10").toInt()
    private val dbType = DatabaseType.valueOf(config("DBTYPE", "dbtype", "MEMORY"))
    private val txIndices = config("TX_INDICES", "txIndices", "false").toBoolean()
    private val measureHeap = config("HEAP", "heap", "false").toBoolean()
    private val lateLinkCount = config("LATE_LINKS", "lateLinks", "50").toInt()
    private val mergeIndexTx = config("MERGE_INDEX_TX", "mergeIndexTx", "false").toBoolean()
    private val indexBatch = config("INDEX_BATCH", "indexBatch", "0").toInt()
    private val indexTailTxs = config("INDEX_TAIL_TXS", "indexTailTxs", "0").toInt()

    /**
     * The realistic DNQ bootstrap shape (see `DNQMetaDataUtil.initMetaData`): all entity
     * metadata is registered first, then every link is registered with
     * `ModelMetaDataImpl.addAssociation`. The first `addAssociation` triggers `prepare()` (=
     * the whole `onPrepared` schema pass), and every association after it goes through
     * `onAddAssociation` - which is what makes a large model pay one schema transaction per
     * link.
     */
    @Test
    fun `model bootstrap - one callback pass per link (legacy shape)`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        withDatabase { provider ->
            val model = newModel(provider)
            val start = System.nanoTime()
            addEntityMetaData(model)
            addAssociations(model)
            model.prepare()
            val totalMs = (System.nanoTime() - start) / 1_000_000

            report("model bootstrap, legacy shape (fresh database)") {
                line("classes / links    = $classCount / $classCount")
                line("bootstrap          = $totalMs ms")
                line("storage files      = ${storageFileCount()}")
            }
            verifySchema(provider)
        }
    }

    /**
     * The same bootstrap assembled inside [ModelMetaDataImpl.buildModel], i.e. what
     * `DNQMetaDataUtil.initMetaData` does: one single schema-application pass over the complete
     * model instead of one per link.
     */
    @Test
    fun `model bootstrap - single callback pass (batched)`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        withDatabase { provider ->
            val model = newModel(provider)
            val start = System.nanoTime()
            model.buildModel {
                addEntityMetaData(model)
                addAssociations(model)
            }
            model.prepare()
            val totalMs = (System.nanoTime() - start) / 1_000_000

            report("model bootstrap, batched (fresh database)") {
                line("classes / links    = $classCount / $classCount")
                line("bootstrap          = $totalMs ms")
                line("storage files      = ${storageFileCount()}")
            }
            verifySchema(provider)
        }
    }

    /**
     * The runtime (post-startup) association-add shape: the model is already built and applied, and
     * then [lateLinkCount] further associations are registered one at a time, the way a client that
     * discovers links from data does (JT-95771: YouTrack's bootstrap registers hundreds of them).
     * Each one pays its own session, transaction and commit - and, inside YouTrackDB, its own
     * transaction-local schema copy and commit-time promotion, both proportional to the WHOLE
     * schema. This is the control for the batched shape below.
     */
    @Test
    fun `runtime association add - one transaction per association`() =
        measureLateAssociations(batched = false)

    /**
     * The same associations registered inside one `ModelMetaDataImpl.batchAssociations` scope
     * (XD-1283): the deltas are collected and applied by a single [YTDBModelMetaData.onAddAssociations]
     * call, hence one transaction for all of them, with no full-model `prepare()` pass in between.
     */
    @Test
    fun `runtime association add - one transaction for the whole delta`() =
        measureLateAssociations(batched = true)

    private fun measureLateAssociations(batched: Boolean) {
        assumeBenchmarkEnabled()
        printConfiguration()

        withDatabase { provider ->
            val model = newModel(provider)
            model.buildModel {
                addEntityMetaData(model)
                addAssociations(model)
            }
            model.prepare()
            verifySchema(provider)

            val start = System.nanoTime()
            if (batched) {
                model.batchAssociations { addLateAssociations(model) }
            } else {
                addLateAssociations(model)
            }
            val totalMs = (System.nanoTime() - start) / 1_000_000

            report("runtime association add, ${if (batched) "batched" else "one transaction each"}") {
                line("classes / startup links = $classCount / $classCount")
                line("late links              = $lateLinkCount")
                line("late association add    = $totalMs ms")
                line("per late link           = ${if (lateLinkCount == 0) "n/a" else "%.1f".format(totalMs.toDouble() / lateLinkCount) + " ms"}")
                line("storage files           = ${storageFileCount()}")
            }
            verifyLateAssociations(provider)
        }
    }

    private fun addLateAssociations(model: YTDBModelMetaData) {
        for (i in 0 until lateLinkCount) {
            model.addAssociation(
                typeName(i % classCount),
                typeName((i + 1) % classCount),
                AssociationType.Directed,
                lateLinkName(i),
                AssociationEndCardinality._0_n,
                false, false, false, false,
                null, null, false, false, false, false
            )
        }
    }

    private fun verifyLateAssociations(provider: YTDBDatabaseProvider) {
        provider.withSession { session ->
            val missing = (0 until lateLinkCount).count { i ->
                session.schema.getClass(edgeClassName(lateLinkName(i))) == null
            }
            check(missing == 0) { "$missing of $lateLinkCount late edge classes missing" }
        }
    }

    private fun lateLinkName(index: Int) = "lateLink$index"

    /**
     * Phase breakdown of a single-pass schema application: the model is fully built (against a
     * throwaway database) and then applied to a fresh one through the startup contract -
     * one transaction for the DDL, then the index pass.
     */
    @Test
    fun `single-pass schema application on a fresh database`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        // Build the model against a throwaway database so that every schema-application
        // callback has already fired by the time the measured database is touched.
        val entities = buildModelOnThrowawayDatabase()

        withDatabase { provider ->
            var schemaMs = 0L
            var schemaCommitMs = 0L
            var indicesMs = 0L
            var indexCount = 0
            var indexTxCount: Int? = null
            var initializeMs = 0L
            val mergedIndices = mergeIndexTx && txIndices
            var heapBeforeIndices = -1L
            var peakHeapDuringIndices = -1L
            var heapAfterIndices = -1L

            provider.withSession { session ->
                // ---- phase 1: schema (DDL) -------------------------------------------------
                val startSchema = System.nanoTime()
                session.begin()
                val result = phaseApplySchema {
                    session.applySchema(
                        entities,
                        indexForEverySimpleProperty = true,
                        applyLinkCardinality = true
                    )
                }
                val afterSchemaDdl = System.nanoTime()
                indexCount = result.indices.values.sumOf { it.size }

                // ---- merged shape: the index DDL joins the schema transaction ---------------
                // This mode is validated above to have no batch/tail shaping, so the direct call
                // preserves the actual one-transaction shape and avoids chunk construction.
                if (mergedIndices) {
                    if (measureHeap) {
                        heapBeforeIndices = usedHeapAfterGc()
                        resetPeakHeap()
                    }
                    val startIndices = System.nanoTime()
                    phaseApplyIndices { session.applyIndices(result.indices) }
                    indicesMs = (System.nanoTime() - startIndices) / 1_000_000
                    indexTxCount = 1
                }

                val beforeSchemaCommit = System.nanoTime()
                phaseApplySchemaCommit { session.activeTransaction.commit() }
                val afterSchemaCommit = System.nanoTime()
                schemaMs = (afterSchemaDdl - startSchema) / 1_000_000
                schemaCommitMs = (afterSchemaCommit - beforeSchemaCommit) / 1_000_000

                if (mergedIndices && measureHeap) {
                    // The merged index DDL and its commit are both part of the measured work.
                    peakHeapDuringIndices = peakHeapUsed()
                    heapAfterIndices = usedHeapAfterGc()
                }

                // ---- phase 2: indices ------------------------------------------------------
                session.initializeComplementaryPropertiesForNewIndexedLinks(result.newIndexedLinks)
                if (!mergedIndices) {
                    if (measureHeap) {
                        heapBeforeIndices = usedHeapAfterGc()
                        resetPeakHeap()
                    }
                    val startIndices = System.nanoTime()
                    phaseApplyIndices {
                        if (txIndices) {
                            if (indexBatch == 0 && indexTailTxs == 0) {
                                // Keep the normal benchmark path identical to the pre-probe shape:
                                // do not flatten/regroup the definitions for a one-transaction run.
                                indexTxCount = 1
                                session.withTx { it.applyIndices(result.indices) }
                            } else {
                                val chunks = indexTransactionChunks(result.indices)
                                indexTxCount = chunks.size
                                chunks.forEach { chunk -> session.withTx { it.applyIndices(chunk) } }
                            }
                        } else {
                            session.applyIndicesNonTx(result.indices)
                        }
                    }
                    indicesMs = (System.nanoTime() - startIndices) / 1_000_000
                    if (measureHeap) {
                        peakHeapDuringIndices = peakHeapUsed()
                        heapAfterIndices = usedHeapAfterGc()
                    }
                }

                // ---- phase 3: schema-buddy initialize --------------------------------------
                val startInitialize = System.nanoTime()
                YTDBSchemaBuddyImpl(provider, autoInitialize = false).initialize(session)
                initializeMs = (System.nanoTime() - startInitialize) / 1_000_000
            }

            report("single-pass schema application (fresh database)") {
                line("index definitions  = $indexCount")
                line("applySchema (DDL)  = $schemaMs ms")
                line("applySchema commit = $schemaCommitMs ms (${if (mergedIndices) "DDL + index commit" else "DDL only"})")
                line("applySchema total  = ${schemaMs + schemaCommitMs} ms (${if (mergedIndices) "DDL + merged commit; index DDL above" else "DDL + commit"})")
                line("applyIndices       = $indicesMs ms (${if (mergedIndices) "DDL only; commit above" else if (txIndices) "including commit" else "non-transactional"})")
                line("applyIndices txs   = ${indexTxCount ?: "n/a (non-transactional)"}")
                line("merged index tx    = $mergedIndices")
                line("buddy.initialize   = $initializeMs ms")
                line("TOTAL              = ${schemaMs + schemaCommitMs + indicesMs + initializeMs} ms")
                line("storage files      = ${storageFileCount()}")
                if (measureHeap) {
                    // XD-1283 track 04 (risk R2): what it costs to hold all index definitions of
                    // the pass in ONE transaction. Compare a txIndices=true run with a
                    // txIndices=false one; the delta is the transaction's own footprint.
                    line("heap max           = ${mb(Runtime.getRuntime().maxMemory())} MB")
                    line("heap before pass   = ${mb(heapBeforeIndices)} MB (post-GC)")
                    line("heap PEAK in pass  = ${mb(peakHeapDuringIndices)} MB")
                    line("heap after pass    = ${mb(heapAfterIndices)} MB (post-GC)")
                }
            }
            verifySchema(provider)
        }
    }

    /**
     * Second startup over an already initialized database: every class, property and index is
     * present, so the whole pass degenerates into existence checks.
     */
    @Test
    fun `re-applying the schema to an initialized database`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        val entities = buildModelOnThrowawayDatabase()

        withDatabase { provider ->
            // first pass: initialize
            provider.withSession { session ->
                val result = session.withTx {
                    it.applySchema(entities, indexForEverySimpleProperty = true, applyLinkCardinality = true)
                }
                session.initializeComplementaryPropertiesForNewIndexedLinks(result.newIndexedLinks)
                if (txIndices) session.withTx { it.applyIndices(result.indices) }
                else session.applyIndicesNonTx(result.indices)
            }

            // second pass: everything already exists
            var schemaMs = 0L
            var indicesMs = 0L
            provider.withSession { session ->
                val startSchema = System.nanoTime()
                val result = session.withTx {
                    it.applySchema(entities, indexForEverySimpleProperty = true, applyLinkCardinality = true)
                }
                schemaMs = (System.nanoTime() - startSchema) / 1_000_000
                val startIndices = System.nanoTime()
                if (txIndices) session.withTx { it.applyIndices(result.indices) }
                else session.applyIndicesNonTx(result.indices)
                indicesMs = (System.nanoTime() - startIndices) / 1_000_000
            }

            report("re-application (warm database)") {
                line("applySchema        = $schemaMs ms")
                line("applyIndices       = $indicesMs ms")
                line("TOTAL              = ${schemaMs + indicesMs} ms")
            }
        }
    }

    /**
     * Probe for the DDL phase: the same class/property shape created directly against the YTDB
     * schema API, once through the public `createProperty` (which runs
     * `SchemaClassImpl.checkPersistentPropertyType` = one SQL SELECT per property) and once
     * through the internal `unsafe = true` overload that skips it.
     */
    @Test
    fun `probe - cost of checkPersistentPropertyType`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        val safe1 = rawSchemaCreation(unsafe = false)
        val unsafe1 = rawSchemaCreation(unsafe = true)
        val unsafe2 = rawSchemaCreation(unsafe = true)
        val safe2 = rawSchemaCreation(unsafe = false)

        report("probe: createProperty with and without the schemaless-data check") {
            line("classes            = $classCount")
            line("properties created = ${classCount / 10 * propertyCount + 2 * classCount}")
            line("public createProperty  = $safe1 ms (cold), $safe2 ms (warm)")
            line("unsafe createProperty  = $unsafe1 ms, $unsafe2 ms")
        }
    }

    /**
     * The probe that matters for the runtime path: the classes are created and COMMITTED first, and
     * only then are the properties added, in a second transaction. YouTrackDB's in-memory fast path
     * for `checkPersistentPropertyType` / `fireDatabaseMigration` is gated on
     * `hasOnlyTransactionLocalCollections()`, i.e. on the class having been created in the CURRENT
     * transaction - not on it being empty - so this shape takes the per-property SQL path even though
     * every class holds zero records. The `unsafe = true` side is what a client could reach if it
     * proved emptiness itself.
     */
    @Test
    fun `probe - cost of checkPersistentPropertyType on committed empty classes`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        val safe1 = rawSchemaCreationOnCommittedClasses(unsafe = false)
        val unsafe1 = rawSchemaCreationOnCommittedClasses(unsafe = true)
        val unsafe2 = rawSchemaCreationOnCommittedClasses(unsafe = true)
        val safe2 = rawSchemaCreationOnCommittedClasses(unsafe = false)

        report("probe: properties added to COMMITTED, EMPTY classes") {
            line("classes            = $classCount")
            line("properties created = ${classCount / 10 * propertyCount + 2 * classCount}")
            line("public createProperty  = $safe1 ms, $safe2 ms")
            line("unsafe createProperty  = $unsafe1 ms, $unsafe2 ms")
        }
    }

    private fun rawSchemaCreationOnCommittedClasses(unsafe: Boolean): Long = withDatabase { provider ->
        provider.withSession { session ->
            // transaction 1: the classes only - committed, so their collection ids stop being
            // provisional and YouTrackDB's transaction-local fast path no longer applies
            session.begin()
            for (i in 0 until classCount) {
                val cls = session.schema.createVertexClass(typeName(i))
                if (i % 10 != 0) {
                    cls.addSuperClass(session.schema.getClass(typeName(i - i % 10)))
                }
                session.schema.createEdgeClass("link$i")
            }
            session.activeTransaction.commit()

            // transaction 2: the properties, over committed but empty classes
            session.begin()
            val start = System.nanoTime()
            for (i in 0 until classCount) {
                if (i % 10 == 0) {
                    val cls = session.schema.getClass(typeName(i))!!
                    for (p in 0 until propertyCount) {
                        cls.createBenchProperty("prop$p", ytdbType(p), unsafe)
                    }
                }
                session.schema.getClass(typeName(i))!!
                    .createBenchProperty("out_link$i", PropertyType.LINKBAG, unsafe)
                session.schema.getClass(typeName((i + 1) % classCount))!!
                    .createBenchProperty("in_link$i", PropertyType.LINKBAG, unsafe)
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            session.activeTransaction.commit()
            ms
        }
    }

    /**
     * The probe that measures the LAST remaining schema-validation cost of a fresh YouTrack init
     * (XD-1283 / JT-95771): the emptiness guard of commit 3b43c6a3 correctly declines to fire on a
     * class that HOLDS COMMITTED RECORDS, so DNQ still pays the validated path for every LINKBAG
     * property (`out_<edge>` / `in_<edge>`) it declares on a populated vertex class. Profiling a
     * 19.2 s init attributed ~1.57 s to exactly that, and ~90% of it is not the SELECT itself but the
     * full `ImmutableSchema` snapshot rebuild that the SELECT's query planner forces inside the DDL
     * transaction (the plan cache is bypassed for the whole duration of a schema-changing
     * transaction).
     *
     * The shape is the committed-empty probe above plus a middle transaction that writes
     * [recordsPerClass] records into every vertex class, which is what defeats the emptiness guard.
     * The `unsafe = true` side is the prize a booked-name guard (`out_*` / `in_*` on vertex classes,
     * names DNQ owns and no user data can carry) would buy.
     */
    @Test
    fun `probe - cost of checkPersistentPropertyType on committed POPULATED classes`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        val safe1 = rawSchemaCreationOnPopulatedClasses(unsafe = false)
        val unsafe1 = rawSchemaCreationOnPopulatedClasses(unsafe = true)
        val unsafe2 = rawSchemaCreationOnPopulatedClasses(unsafe = true)
        val safe2 = rawSchemaCreationOnPopulatedClasses(unsafe = false)

        report("probe: properties added to COMMITTED, POPULATED classes") {
            line("classes                = $classCount")
            line("records per class      = $recordsPerClass (total ${classCount * recordsPerClass})")
            line("properties created     = ${classCount / 10 * propertyCount + 2 * classCount}")
            line("  of which LINKBAG     = ${2 * classCount}")
            line("  of which simple      = ${classCount / 10 * propertyCount}")
            line("public createProperty  = ${safe1.totalMs} ms, ${safe2.totalMs} ms")
            line("unsafe createProperty  = ${unsafe1.totalMs} ms, ${unsafe2.totalMs} ms")
            line("  LINKBAG only  safe   = ${safe1.linkBagMs} ms, ${safe2.linkBagMs} ms")
            line("  LINKBAG only  unsafe = ${unsafe1.linkBagMs} ms, ${unsafe2.linkBagMs} ms")
            line("  simple  only  safe   = ${safe1.simpleMs} ms, ${safe2.simpleMs} ms")
            line("  simple  only  unsafe = ${unsafe1.simpleMs} ms, ${unsafe2.simpleMs} ms")
        }
    }

    /** Split of the measured transaction of [rawSchemaCreationOnPopulatedClasses]. */
    private class PropertyCreationTiming(val totalMs: Long, val linkBagMs: Long, val simpleMs: Long)

    /**
     * Deliberately tiny: the records only have to exist so that the class is not empty. Anything
     * larger would measure inserts instead of the validation the probe is about.
     */
    private val recordsPerClass = 3

    private fun rawSchemaCreationOnPopulatedClasses(unsafe: Boolean): PropertyCreationTiming =
        withDatabase { provider ->
            provider.withSession { session ->
                // transaction 1: the classes only - same shape as the committed-empty probe
                session.begin()
                for (i in 0 until classCount) {
                    val cls = session.schema.createVertexClass(typeName(i))
                    if (i % 10 != 0) {
                        cls.addSuperClass(session.schema.getClass(typeName(i - i % 10)))
                    }
                    session.schema.createEdgeClass("link$i")
                }
                session.activeTransaction.commit()

                // transaction 2: make every class genuinely populated, with committed collections,
                // so the emptiness guard cannot fire on the measured pass. The filler property name
                // is one no later phase declares, so it cannot bias checkPersistentPropertyType.
                session.begin()
                for (i in 0 until classCount) {
                    repeat(recordsPerClass) { r ->
                        session.newVertex(typeName(i)).setProperty("benchFill", r.toLong())
                    }
                }
                session.activeTransaction.commit()

                // transaction 3 (MEASURED): the properties, over committed and POPULATED classes
                session.begin()
                var linkBagNs = 0L
                var simpleNs = 0L
                val start = System.nanoTime()
                for (i in 0 until classCount) {
                    if (i % 10 == 0) {
                        val cls = session.schema.getClass(typeName(i))!!
                        val startSimple = System.nanoTime()
                        for (p in 0 until propertyCount) {
                            cls.createBenchProperty("prop$p", ytdbType(p), unsafe)
                        }
                        simpleNs += System.nanoTime() - startSimple
                    }
                    val startLinkBag = System.nanoTime()
                    session.schema.getClass(typeName(i))!!
                        .createBenchProperty("out_link$i", PropertyType.LINKBAG, unsafe)
                    session.schema.getClass(typeName((i + 1) % classCount))!!
                        .createBenchProperty("in_link$i", PropertyType.LINKBAG, unsafe)
                    linkBagNs += System.nanoTime() - startLinkBag
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                session.activeTransaction.commit()
                PropertyCreationTiming(ms, linkBagNs / 1_000_000, simpleNs / 1_000_000)
            }
        }

    private fun rawSchemaCreation(unsafe: Boolean): Long = withDatabase { provider ->
        provider.withSession { session ->
            session.begin()
            val start = System.nanoTime()
            for (i in 0 until classCount) {
                val cls = session.schema.createVertexClass(typeName(i))
                if (i % 10 != 0) {
                    cls.addSuperClass(session.schema.getClass(typeName(i - i % 10)))
                }
                if (i % 10 == 0) {
                    for (p in 0 until propertyCount) {
                        cls.createBenchProperty("prop$p", ytdbType(p), unsafe)
                    }
                }
            }
            for (i in 0 until classCount) {
                session.schema.createEdgeClass("link$i")
                session.schema.getClass(typeName(i))!!
                    .createBenchProperty("out_link$i", PropertyType.LINKBAG, unsafe)
                session.schema.getClass(typeName((i + 1) % classCount))!!
                    .createBenchProperty("in_link$i", PropertyType.LINKBAG, unsafe)
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            session.activeTransaction.commit()
            ms
        }
    }

    private fun SchemaClass.createBenchProperty(
        name: String,
        type: PropertyType,
        unsafe: Boolean
    ) {
        if (existsProperty(name)) return
        if (unsafe) {
            (this as SchemaClassInternal)
                .createProperty(
                    name,
                    PropertyTypeInternal
                        .convertFromPublicType(type),
                    null as PropertyTypeInternal?,
                    true
                )
        } else {
            createProperty(name, type)
        }
    }

    private fun ytdbType(index: Int): PropertyType = when (index % 4) {
        0 -> PropertyType.STRING
        1 -> PropertyType.INTEGER
        2 -> PropertyType.LONG
        else -> PropertyType.BOOLEAN
    }

    /** Counts what the model actually turns into in the YTDB schema. */
    @Test
    fun `probe - schema object counts`() {
        assumeBenchmarkEnabled()
        printConfiguration()

        val entities = buildModelOnThrowawayDatabase()
        withDatabase { provider ->
            var indexDefs = 0
            provider.withSession { session ->
                val result = session.withTx {
                    it.applySchema(entities, indexForEverySimpleProperty = true, applyLinkCardinality = true)
                }
                indexDefs = result.indices.values.sumOf { it.size }
            }
            provider.withSession { session ->
                val classes = session.schema.classes.filter { it.name.startsWith("BenchType") || it.name.startsWith("link") }
                val vertexClasses = classes.count { it.name.startsWith("BenchType") }
                val edgeClasses = classes.count { it.name.startsWith("link") }
                val declared = classes.sumOf { it.declaredProperties.size }
                val declaredOnRoots = classes.filter { c ->
                    c.name.startsWith("BenchType") && c.name.removePrefix("BenchType").toInt() % 10 == 0
                }.sumOf { it.declaredProperties.size }
                report("probe: schema object counts") {
                    line("entity types (model)   = $classCount")
                    line("vertex classes         = $vertexClasses")
                    line("edge classes           = $edgeClasses")
                    line("declared properties    = $declared (on root types: $declaredOnRoots)")
                    line("index definitions      = $indexDefs")
                }
                report("probe: shape of the schema") {
                    for (name in listOf("BenchType0", "BenchType1", "BenchType9", "BenchType10")) {
                        val c = session.schema.getClass(name) ?: continue
                        line("$name : superclasses=${c.superClassesNames} subclasses=${c.subclasses.size}")
                        line("    declared = ${c.declaredProperties.map { it.name }.sorted()}")
                    }
                    val edge = session.schema.classes.first { it.name.contains("link0") }
                    line("edge class '${edge.name}' : superclasses=${edge.superClassesNames}")
                    line("    declared = ${edge.declaredProperties.map { "${it.name}:${it.type}" }.sorted()}")
                }
            }
        }
    }

    // ---- model -----------------------------------------------------------------------------

    /**
     * A YouTrack-ish shape: every 10th type is a root type and the nine types after it inherit
     * from it; every type has [propertyCount] simple properties (which, because startup applies
     * `indexForEverySimpleProperty = true`, produce one index each), one composite unique index,
     * and one outgoing link (= one edge class and one unique edge index per type).
     */
    private fun newModel(provider: YTDBDatabaseProvider) =
        YTDBModelMetaData(provider, YTDBSchemaBuddyImpl(provider, autoInitialize = false))

    private fun buildModelOnThrowawayDatabase(): List<EntityMetaData> = withMemoryDatabase { provider ->
        val model = newModel(provider)
        model.buildModel {
            addEntityMetaData(model)
            addAssociations(model)
        }
        model.entitiesMetaData.toList()
    }

    private fun addEntityMetaData(model: YTDBModelMetaData) {
        for (i in 0 until classCount) {
            val entity = EntityMetaDataImpl()
            entity.type = typeName(i)
            entity.superType = if (i % 10 == 0) null else typeName(i - i % 10)
            model.addEntityMetaData(entity)
            entity.propertiesMetaData = (0 until propertyCount).map { p ->
                SimplePropertyMetaDataImpl("prop$p", propertyTypeName(p))
            }
            val index = IndexImpl()
            index.ownerEntityType = entity.type
            index.fields = listOf("prop0", "prop1").map { fieldName ->
                IndexFieldImpl().apply {
                    isProperty = true
                    name = fieldName
                }
            }
            entity.ownIndexes = setOf(index)
        }
    }

    private fun addAssociations(model: YTDBModelMetaData) {
        for (i in 0 until classCount) {
            model.addAssociation(
                typeName(i),
                typeName((i + 1) % classCount),
                AssociationType.Directed,
                "link$i",
                AssociationEndCardinality._0_n,
                false, false, false, false,
                null, null, false, false, false, false
            )
        }
    }

    private fun propertyTypeName(index: Int): String = when (index % 4) {
        0 -> "string"
        1 -> "int"
        2 -> "long"
        else -> "boolean"
    }

    private fun typeName(index: Int) = "BenchType$index"

    private fun verifySchema(provider: YTDBDatabaseProvider) {
        provider.withSession { session ->
            for (i in 0 until classCount) {
                checkNotNull(session.schema.getClass(typeName(i))) { "${typeName(i)} missing" }
            }
            val missingIndices = (0 until classCount).count { i ->
                !session.schema.indexExists(
                    DeferredIndex(typeName(i), setOf("prop0"), unique = false).indexName
                )
            }
            check(missingIndices == 0) { "$missingIndices per-property indices missing" }
        }
    }

    // ---- harness ---------------------------------------------------------------------------

    private fun assumeBenchmarkEnabled() = Assume.assumeTrue(
        "set DNQ_BENCH=true (env) to run the schema-init benchmark",
        System.getenv("DNQ_BENCH") != null || System.getProperty("dnq.bench") != null
    )

    private fun validateBenchmarkConfiguration() {
        require(classCount > 0) {
            "CLASSES must be > 0, got $classCount"
        }
        require(propertyCount >= 2) {
            "PROPERTIES must be >= 2 because the benchmark declares a prop0+prop1 composite index, got $propertyCount"
        }
        require(lateLinkCount >= 0) {
            "LATE_LINKS must be >= 0, got $lateLinkCount"
        }
        require(indexBatch >= 0) {
            "INDEX_BATCH must be >= 0, got $indexBatch"
        }
        require(indexTailTxs >= 0) {
            "INDEX_TAIL_TXS must be >= 0, got $indexTailTxs"
        }
        if (txIndices) {
            require(indexBatch == 0 || indexTailTxs == 0) {
                "INDEX_BATCH and INDEX_TAIL_TXS are mutually exclusive"
            }
            require(!mergeIndexTx || (indexBatch == 0 && indexTailTxs == 0)) {
                "MERGE_INDEX_TX cannot be combined with INDEX_BATCH or INDEX_TAIL_TXS"
            }
        }
    }

    private fun printConfiguration(): Unit {
        validateBenchmarkConfiguration()
        report("configuration") {
            line("classes            = $classCount")
            line("properties/class   = $propertyCount")
            line("database type      = $dbType")
            line("txIndices flag     = $txIndices")
            line("index batch        = $indexBatch${if (!txIndices) " (ignored; TX_INDICES=false)" else ""}")
            line("index tail txs     = $indexTailTxs${if (!txIndices) " (ignored; TX_INDICES=false)" else ""}")
            line("merge index tx     = $mergeIndexTx${if (!txIndices) " (ignored; TX_INDICES=false)" else ""}")
        }
    }

    /**
     * Builds the model against an in-memory database regardless of the configured type: only the
     * measured pass should pay for on-disk storage.
     */
    private fun <R> withMemoryDatabase(block: (YTDBDatabaseProvider) -> R): R =
        withDatabase(DatabaseType.MEMORY, block)

    private fun <R> withDatabase(block: (YTDBDatabaseProvider) -> R): R = withDatabase(dbType, block)

    private fun <R> withDatabase(type: DatabaseType, block: (YTDBDatabaseProvider) -> R): R {
        val dbPath = Files.createTempDirectory("youTrackDB_bench")
        databasePath = dbPath.absolutePathString()
        var db: YouTrackDBImpl? = null
        var failure: Throwable? = null

        fun recordFailure(error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else primary.addSuppressed(error)
        }

        try {
            config("FSYNC", "fsync", "").let { fsync ->
                if (fsync.isNotEmpty()) {
                    com.jetbrains.youtrackdb.api.config.GlobalConfiguration.STORAGE_CALL_FSYNC
                        .setValue(fsync.toBoolean())
                }
            }
            config("COLLECTIONS", "collections", "").let { collections ->
                if (collections.isNotEmpty()) {
                    com.jetbrains.youtrackdb.api.config.GlobalConfiguration.CLASS_COLLECTIONS_COUNT
                        .setValue(collections.toInt())
                }
            }
            val params = YTDBDatabaseParams.builder()
                .withDatabaseType(type)
                .withDatabasePath(dbPath.absolutePathString())
                .withAppUser("admin", "password")
                .withDatabaseName("benchDB")
                .build()
            db = YouTrackDBFactory.createEmbedded(params) as YouTrackDBImpl
            return block(YTDBDatabaseProviderFactory.createProvider(params, db!!))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                db?.close()
            } catch (error: Throwable) {
                recordFailure(error)
            }
            try {
                check(dbPath.toFile().deleteRecursively()) { "Failed to delete $dbPath" }
            } catch (error: Throwable) {
                recordFailure(error)
            }
            failure?.let { throw it }
        }
    }

    private fun report(title: String, body: ReportBuilder.() -> Unit) {
        val builder = ReportBuilder()
        builder.body()
        val banner = "=".repeat(64)
        println(banner)
        println("SchemaInitBenchmark - $title")
        println(banner)
        builder.lines.forEach { println("  $it") }
        println(banner)
    }

    private class ReportBuilder {
        val lines = mutableListOf<String>()
        fun line(s: String) = lines.add(s)
    }

    private var databasePath: String? = null

    // ---- heap measurement (XD-1283 track 04, risk R2) ---------------------------------------

    private fun heapPools() = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
        .filter { it.type == java.lang.management.MemoryType.HEAP }

    private fun resetPeakHeap() = heapPools().forEach { it.resetPeakUsage() }

    /** Peak used heap over all heap pools since the last [resetPeakHeap]. */
    private fun peakHeapUsed(): Long = heapPools().sumOf { it.peakUsage?.used ?: 0L }

    /** Used heap after two collections - i.e. approximately the live set. */
    private fun usedHeapAfterGc(): Long {
        System.gc()
        Thread.sleep(100)
        System.gc()
        Thread.sleep(100)
        return java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private fun mb(bytes: Long): String = if (bytes < 0) "n/a" else "%.1f".format(bytes / 1024.0 / 1024.0)

    /** Number of files the storage created - one fsync-bound `WOWCache.addFile` each. */
    private fun storageFileCount(): Int {
        val path = databasePath ?: return -1
        return java.io.File(path).walkTopDown().count { it.isFile }
    }

    /**
     * Poor-man's sampling profiler: samples the calling thread's stack while [block] runs and
     * prints the hottest frames. Only active with `DNQ_BENCH_PROFILE=true`.
     */
    /**
     * Named, non-inlined phase markers so that an external profiler (async-profiler / JFR) can
     * split the recording per phase with a stack filter, e.g.
     * `jfrconv --cpu -I 'phaseApplyIndices' run.jfr indices.html`.
     */
    private fun <R> phaseApplySchema(block: () -> R): R = sampling("applySchema", block)

    private fun <R> phaseApplySchemaCommit(block: () -> R): R = sampling("applySchema commit", block)

    private fun <R> phaseApplyIndices(block: () -> R): R = sampling("applyIndices", block)

    private fun <R> sampling(phase: String, block: () -> R): R {
        if (config("PROFILE", "profile", "false") != "true") return block()
        val target = Thread.currentThread()
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        var samples = 0
        val sampler = Thread {
            while (!stop.get()) {
                val stack = target.stackTrace
                samples++
                // Only frames below the measured block are interesting, and only YTDB/DNQ ones:
                // the JUnit/Gradle prefix is present in every sample and would crowd the report.
                val cut = stack.indexOfFirst { it.methodName == "sampling" }
                val relevant = if (cut > 0) stack.copyOfRange(0, cut) else stack
                val seen = HashSet<String>()
                for (frame in relevant) {
                    if (!frame.className.startsWith("com.jetbrains.youtrackdb") &&
                        !frame.className.startsWith("jetbrains.exodus")
                    ) continue
                    val key = "${frame.className.substringAfterLast('.')}.${frame.methodName}"
                    if (seen.add(key)) counts.merge(key, 1) { a, b -> a + b }
                }
                if (relevant.isNotEmpty()) {
                    val leaf = relevant[0]
                    counts.merge(
                        "LEAF ${leaf.className.substringAfterLast('.')}.${leaf.methodName}",
                        1
                    ) { a, b -> a + b }
                }
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        sampler.isDaemon = true
        sampler.start()
        try {
            return block()
        } finally {
            stop.set(true)
            sampler.join(1000)
            report("profile: $phase ($samples samples)") {
                counts.entries.sortedByDescending { it.value }.take(40).forEach { (frame, count) ->
                    line("%5d  %s".format(count, frame))
                }
            }
        }
    }

    /**
     * Splits the pass's index definitions into the per-transaction chunks the knobs ask for.
     * Definitions are flattened to (owner class, definition) pairs and regrouped per chunk, so a
     * chunk boundary may fall inside a class's index set - which is what a production chunking would
     * have to tolerate too (`applyIndices` looks each owner class up per chunk). The default is a
     * single chunk: today's shape, so a `0/0` run is the unmodified baseline.
     */
    private fun indexTransactionChunks(
        indices: Map<String, Set<DeferredIndex>>
    ): List<Map<String, Set<DeferredIndex>>> {
        val flat = indices.entries.flatMap { (owner, set) -> set.map { owner to it } }
        val chunks = when {
            indexTailTxs > 0 -> {
                val tail = indexTailTxs.coerceAtMost(flat.size)
                listOf(flat.take(flat.size - tail)) + flat.takeLast(tail).map { listOf(it) }
            }
            indexBatch > 0 -> flat.chunked(indexBatch)
            else -> listOf(flat)
        }
        return chunks.filter { it.isNotEmpty() }
            .map { chunk -> chunk.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.toSet() } }
    }

    private companion object {
        fun config(envSuffix: String, propertySuffix: String, default: String): String =
            System.getenv("DNQ_BENCH_$envSuffix")
                ?: System.getProperty("dnq.bench.$propertySuffix")
                ?: default
    }
}
