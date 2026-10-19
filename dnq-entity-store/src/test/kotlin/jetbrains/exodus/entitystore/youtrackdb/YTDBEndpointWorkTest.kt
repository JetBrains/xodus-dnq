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

import com.jetbrains.youtrackdb.internal.common.util.RawPair
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeIterator
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeFromLinkBagIterator
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl
import io.mockk.*
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import org.junit.Rule
import org.junit.Test
import java.util.stream.Stream
import kotlin.test.*

class YTDBEndpointWorkTest {
    @Rule @JvmField val db = InMemoryYouTrackDB(false, false)

    private fun schema(name: String = EDGE, fields: Array<String> = arrayOf("in", "out"), indexed: Boolean = true) {
        db.withTxSession { s ->
            if (s.schema.getClass(VERTEX) == null) s.createVertexClassWithClassId(VERTEX)
            val c = s.schema.createEdgeClass(name)
            if (indexed) c.createIndex("${name}_endpoints", SchemaClass.INDEX_TYPE.UNIQUE, *fields)
        }
    }

    private fun vertex(tx: YTDBStoreTransactionImpl) = tx.newEntity(VERTEX).vertex.id()
    private fun add(tx: YTDBStoreTransactionImpl, out: RID, target: RID, label: String = EDGE) =
        tx.getVertex(out).addEdge(label, tx.getVertex(target))

    // The streams count entries consumed by the executor, not final SELECT rows.
    // Constructor spies count native adjacency iterators and keep their real behavior.
    private class Work(tx: YTDBStoreTransactionImpl, label: String = EDGE) : AutoCloseable {
        val session = tx.activeYtdbSession()
        val raw = session.activeTransaction as FrontendTransactionImpl
        val indexes = (session.schema.getClass(label) as SchemaClassInternal).indexesInternal
        var candidates = 0
        var probes = 0
        var adjacency = 0
        var preprocessed = 0
        var queries = 0
        val plans = mutableListOf<String>()
        private val failures = mutableListOf<Throwable>()
        fun record(block: () -> Unit) { try { block() } catch (failure: Throwable) { failures += failure } }
        fun healthy() { assertTrue(failures.isEmpty(), "Recorder failed: $failures") }
        init {
            assertNull(raw.queryResultCache, "Measurements require production cache-off settings")
            mockkObject(session)
            mockkObject(raw, recordPrivateCalls = true)
            every { raw["preProcessRecordOperationAndExecuteBeforeCallbacks"](any<RecordOperation>(), any<RecordSerializer>()) } answers {
                preprocessed++
                callOriginal()
            }
            every { session.query(any<String>(), any<Map<*, *>>()) } answers {
                val result = callOriginal() as LocalResultSet
                queries++
                record { plans += result.internalExecutionPlan.prettyPrint(0, 2) }
                result
            }
            for (index in indexes) {
                mockkObject(index)
                every { index.streamEntriesBetween(any(), any(), any(), any(), any(), any()) } answers {
                    probes++
                    @Suppress("UNCHECKED_CAST")
                    (callOriginal() as Stream<RawPair<Any, RID>>).peek { record { candidates++ } }
                }
            }
            mockkConstructor(EdgeIterator::class, EdgeFromLinkBagIterator::class)
            every { anyConstructed<EdgeIterator>().next() } answers { adjacency++; callOriginal() }
            every { anyConstructed<EdgeFromLinkBagIterator>().next() } answers { adjacency++; callOriginal() }
        }
        fun reset() { candidates = 0; probes = 0; adjacency = 0; preprocessed = 0; queries = 0; plans.clear() }
        fun bounded(hits: Int, lookups: Int) {
            healthy()
            assertNull(raw.queryResultCache)
            assertEquals(lookups, queries, "Every lookup must execute SQL")
            assertEquals(lookups, probes, "One full-key index probe per lookup")
            assertEquals(hits, candidates, "Unique full-key probes consume one entry per hit and none per miss")
            assertEquals(0, adjacency, "Indexed lookup must not enumerate adjacency")
        }
        override fun close() {
            unmockkConstructor(EdgeIterator::class, EdgeFromLinkBagIterator::class)
            indexes.forEach { unmockkObject(it) }
            unmockkObject(raw)
            unmockkObject(session)
            healthy()
        }
    }

    @Test fun `executed plans use both keys for hits misses inherited and reversed indexes`() {
        schema()
        schema("WorkReverse", arrayOf("out", "in"))
        schema("WorkScan", indexed = false)
        db.withTxSession { it.schema.createClass("WorkChild", it.schema.getClass(EDGE)) }
        val fixtures = db.withStoreTx { tx ->
            listOf(EDGE, "WorkReverse", "WorkChild", "WorkScan").associateWith { label ->
                val out = vertex(tx); val target = vertex(tx)
                add(tx, out, target, label)
                out to target
            }
        }
        db.withStoreTx { tx ->
            for (label in listOf(EDGE, "WorkReverse", "WorkChild")) Work(tx, label).use { w ->
                val ids = fixtures.getValue(label)
                assertNotNull(tx.findEdge(label, ids.first, ids.second))
                assertNull(tx.findEdge(label, ids.second, ids.first))
                w.bounded(1, 2)
                for (plan in w.plans) {
                    println("PLAN $label\n$plan")
                    val indexLookup = plan.substringBefore("+ EXTRACT VALUE")
                    assertTrue(indexLookup.contains(":outId") && indexLookup.contains(":inId"), "Both key parts must belong to the index lookup")
                    assertTrue(plan.contains("FETCH FROM INDEX"))
                    assertFalse(plan.contains("FETCH FROM CLASS"))
                    assertFalse(plan.contains("EXPAND"))
                }
            }
            val s = tx.activeYtdbSession()
            assertNull((s.activeTransaction as FrontendTransactionImpl).queryResultCache)
            val ids = fixtures.getValue("WorkScan")
            s.query("SELECT FROM WorkScan WHERE outV() = :outId AND inV() = :inId LIMIT 1",
                mapOf("outId" to ids.first, "inId" to ids.second)).use { rs ->
                assertTrue(rs.hasNext())
                val plan = assertNotNull(rs.executionPlan).prettyPrint(0, 2)
                println("UNINDEXED CONTROL\n$plan")
                assertTrue(plan.contains("FETCH FROM CLASS"))
            }
        }
    }

    @Test fun `unique endpoint work stays bounded in both directions at increasing degrees`() {
        schema()
        for (degree in listOf(32, 256, 2048)) {
            val fixture = db.withStoreTx { tx ->
                val hub = vertex(tx); val shared = vertex(tx); val missing = vertex(tx)
                val leaves = List(degree) { vertex(tx) }
                for (leaf in leaves) { add(tx, hub, leaf); add(tx, leaf, hub); add(tx, leaf, shared) }
                Triple(hub, shared, missing) to leaves
            }
            val (hub, shared, missing) = fixture.first
            val leaves = fixture.second
            db.withStoreTx { tx -> Work(tx).use { w ->
                // The negative control proves that the native-iterator counter is live.
                tx.getVertex(leaves[0]).edges(org.apache.tinkerpop.gremlin.structure.Direction.OUT, EDGE).forEachRemaining { }
                assertEquals(2, w.adjacency, "Control enumerates the leaf links to hub and shared target")
                for (kind in listOf("out-hit", "in-hit", "out-miss", "in-miss", "shared", "repeat")) {
                    w.reset()
                    val start = System.nanoTime()
                    repeat(32) { i ->
                        val pair = when (kind) {
                            "out-hit" -> hub to leaves[i]
                            "in-hit" -> leaves[i] to hub
                            "out-miss" -> hub to missing
                            "in-miss" -> missing to hub
                            "shared" -> leaves[i] to shared
                            else -> hub to leaves[0]
                        }
                        assertEquals(!kind.endsWith("miss"), tx.findEdge(EDGE, pair.first, pair.second) != null)
                    }
                    w.bounded(if (kind.endsWith("miss")) 0 else 32, 32)
                    println("WORK degree=$degree kind=$kind probes=${w.probes} candidates=${w.candidates} adjacency=${w.adjacency} preprocessed=${w.preprocessed} nanos=${System.nanoTime() - start}")
                }
                w.reset()
                assertNotNull(tx.findEdge(EDGE, hub, leaves[0])).remove()
                assertNull(tx.findEdge(EDGE, hub, leaves[0]))
                add(tx, hub, leaves[0])
                assertNotNull(tx.findEdge(EDGE, hub, leaves[0]))
                w.bounded(2, 3)
            } }
        }
    }

    @Test fun `pending preprocessing counts batch and incremental records independently of degree`() {
        schema()
        for (degree in listOf(32, 256, 2048)) {
            val ids = db.withStoreTx { tx ->
                val hub = vertex(tx); val leaves = List(degree) { vertex(tx) }
                leaves.forEach { add(tx, hub, it) }
                hub to leaves
            }
            for (pending in listOf(0, 32, 256)) db.withStoreTx { tx ->
                repeat(pending) { vertex(tx) }
                Work(tx).use { w ->
                    val first = System.nanoTime()
                    assertNotNull(tx.findEdge(EDGE, ids.first, ids.second[0]))
                    val firstNanos = System.nanoTime() - first
                    assertEquals(pending, w.preprocessed, "First query processes the whole independent batch")
                    w.reset()
                    val later = System.nanoTime()
                    repeat(32) { assertNotNull(tx.findEdge(EDGE, ids.first, ids.second[it])) }
                    w.bounded(32, 32)
                    assertEquals(0, w.preprocessed)
                    println("PENDING degree=$degree records=$pending firstNanos=$firstNanos later32Nanos=${System.nanoTime() - later}")
                    // Precreate endpoints and synchronize them before measuring edge-only writes.
                    val targets = List(64) { vertex(tx) }
                    assertNotNull(tx.findEdge(EDGE, ids.first, ids.second[0]))
                    w.reset()
                    targets.take(32).forEach { add(tx, ids.first, it) }
                    assertNotNull(tx.findEdge(EDGE, ids.first, targets[0]))
                    assertEquals(65, w.preprocessed, "32 edges, 32 targets and one shared source")
                    println("BATCH degree=$degree pending=$pending edges=32 preprocessed=${w.preprocessed}")
                    repeat(32) { offset ->
                        val i = offset + 32
                        w.reset()
                        add(tx, ids.first, targets[i])
                        assertNotNull(tx.findEdge(EDGE, ids.first, targets[i]))
                        w.bounded(1, 1)
                        assertEquals(3, w.preprocessed, "New edge plus two changed endpoints")
                        println("INTERLEAVED degree=$degree pending=$pending step=$i preprocessed=${w.preprocessed}")
                    }
                }
            }
        }
    }

    @Test fun `recorder failure is asserted outside observation callbacks`() {
        schema()
        db.withStoreTx { tx ->
            assertFailsWith<AssertionError> {
                Work(tx).use { w -> w.record { error("recorder sentinel") } }
            }
        }
    }

    @Test fun `parse plan and metadata diagnostics include link class cache eviction`() {
        schema()
        val labels = List(101) { "Diagnostic${it}Edge" }
        db.withTxSession { s -> labels.forEach { label ->
            s.schema.createEdgeClass(label).createIndex("${label}_endpoints", SchemaClass.INDEX_TYPE.UNIQUE, "in", "out")
        } }
        val ids = db.withStoreTx { tx -> vertex(tx) to vertex(tx) }
        db.withStoreTx { tx ->
            val s = tx.activeYtdbSession()
            assertNull((s.activeTransaction as FrontendTransactionImpl).queryResultCache)
            val statements = s.sharedContext.yqlStatementCache
            val plans = s.sharedContext.yqlExecutionPlanCache
            // Read instantiated capacities so the eviction premise does not depend on global defaults.
            for ((cache, owner) in listOf(statements to statements.javaClass, plans to plans.javaClass.superclass)) {
                val capacity = owner.getDeclaredField("capacity").apply { isAccessible = true }.getInt(cache)
                assertTrue(capacity in 1 until labels.size, "${owner.simpleName} capacity=$capacity must be positive and below ${labels.size}")
            }
            var parseCalls = 0; var parseMisses = 0; var parseNanos = 0L
            var planCalls = 0; var planNanos = 0L
            mockkObject(statements)
            mockkConstructor(com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner::class)
            try {
                every { statements.getCached(any(), any()) } answers {
                    parseCalls++
                    if (!statements.contains(firstArg())) parseMisses++
                    val start = System.nanoTime()
                    val result = callOriginal()
                    parseNanos += System.nanoTime() - start
                    result
                }
                every { anyConstructed<com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner>().createExecutionPlan(any(), any(), any()) } answers {
                    planCalls++
                    val start = System.nanoTime()
                    val result = callOriginal()
                    planNanos += System.nanoTime() - start
                    result
                }
                for ((phase, names) in listOf("cold101" to labels, "warm32" to List(32) { labels.last() }, "evicted101" to labels)) {
                    parseCalls = 0; parseMisses = 0; parseNanos = 0; planCalls = 0; planNanos = 0
                    var planMisses = 0
                    val start = System.nanoTime()
                    names.forEach { label ->
                        val sql = "SELECT FROM `$label` WHERE outV() = :outId AND inV() = :inId LIMIT 1"
                        if (!plans.contains(sql)) planMisses++
                        assertNull(tx.findEdge(label, ids.first, ids.second))
                    }
                    println("DIAGNOSTIC phase=$phase lookups=${names.size} parseCalls=$parseCalls parseMisses=$parseMisses parseNanos=$parseNanos planCalls=$planCalls planMisses=$planMisses planNanos=$planNanos totalNanos=${System.nanoTime() - start}")
                    assertEquals(names.size, parseCalls, "$phase must observe statement lookup for every endpoint lookup")
                    assertEquals(names.size, planCalls, "$phase must observe planning for every endpoint lookup")
                    when (phase) {
                        "cold101" -> {
                            assertEquals(labels.size, parseMisses, "Cold statements must miss for every class")
                            assertEquals(labels.size, planMisses, "Cold plans must miss for every class")
                        }
                        "warm32" -> {
                            assertEquals(0, parseMisses, "Warm statements must be reused")
                            assertEquals(0, planMisses, "Warm plans must be reused")
                        }
                        "evicted101" -> {
                            assertTrue(parseMisses in 1 until names.size, "Evicted statements must include both misses and hits: $parseMisses")
                            assertTrue(planMisses in 1 until names.size, "Evicted plans must include both misses and hits: $planMisses")
                        }
                    }
                }
                val start = System.nanoTime()
                repeat(3200) {
                    val c = s.schema.getClass(labels[it % labels.size]) as SchemaClassInternal
                    assertTrue(c.areIndexed(s, "in", "out"))
                }
                println("METADATA checks=3200 nanos=${System.nanoTime() - start}")
            } finally {
                unmockkConstructor(com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner::class)
                unmockkObject(statements)
            }
        }
    }

    @Test fun `association add delete and set remove the returned indexed edge`() {
        val label = YTDBVertexEntity.edgeClassName("association")
        schema(label)
        val ids = db.withStoreTx { tx -> List(3) { tx.newEntity(VERTEX).id } }
        db.withStoreTx { tx ->
            val source = tx.getEntity(ids[0]); val a = tx.getEntity(ids[1]); val b = tx.getEntity(ids[2])
            Work(tx, label).use { w ->
                assertTrue(source.addLink("association", a))
                assertFalse(source.addLink("association", a))
                assertTrue(source.deleteLink("association", a))
                assertTrue(source.setLink("association", a))
                assertTrue(source.setLink("association", b))
                assertEquals(b.id, source.getLink("association")!!.id)
                assertTrue(source.setLink("association", null))
                assertNull(source.getLink("association"))
                assertTrue(w.queries > 0)
                assertEquals(0, w.adjacency)
            }
        }
        db.withStoreTx { tx -> assertEquals(0L, tx.g().E().hasLabel(label).count().next()) }
    }

    @Test fun `index free migration skips add lookup and traverses delete and set before index build`() {
        val label = YTDBVertexEntity.edgeClassName("migration")
        schema(label, indexed = false)
        val ids = db.withStoreTx { tx -> List(3) { tx.newEntity(VERTEX).id } }
        db.withStoreTx { tx ->
            val source = tx.getEntity(ids[0]); val a = tx.getEntity(ids[1]); val b = tx.getEntity(ids[2])
            mockkObject(tx)
            try {
                assertTrue(source.addLink("migration", a))
                verify(exactly = 0) { tx.findEdge(any(), any(), any()) }
                assertTrue(source.deleteLink("migration", a))
                assertTrue(source.setLink("migration", a))
                assertTrue(source.setLink("migration", b))
                assertEquals(b.id, source.getLink("migration")!!.id)
                verify(atLeast = 2) { tx.findEdge(label, any(), any()) }
            } finally { unmockkObject(tx) }
        }
        // Populated-class index builds run outside an explicit transaction, as in migration.
        db.withSession { s -> s.schema.getClass(label).createIndex("migration_endpoints", SchemaClass.INDEX_TYPE.UNIQUE, "in", "out") }
        db.withStoreTx { tx -> Work(tx, label).use { w ->
            val source = tx.getEntity(ids[0]); val b = tx.getEntity(ids[2])
            assertFalse(source.addLink("migration", b))
            assertTrue(source.deleteLink("migration", b))
            assertTrue(source.addLink("migration", b))
            assertEquals(b.id, source.getLink("migration")!!.id)
            assertTrue(w.queries >= 3)
        } }
        db.withStoreTx { tx -> assertEquals(1L, tx.g().E().hasLabel(label).count().next()) }
    }

    @Test fun `DNQ flush retry replays indexed link addition in a fresh transaction`() {
        val label = YTDBVertexEntity.edgeClassName("replay")
        schema(label)
        val ids = db.withStoreTx { tx -> List(2) { tx.newEntity(VERTEX).id } }
        val store = com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl().apply { persistentStore = db.store }
        var injected = 0
        var replayed = 0
        var first: YTDBStoreTransactionImpl? = null
        try {
            store.transactional { session ->
                val tx = session.transactionInternal as YTDBStoreTransactionImpl
                first = tx
                mockkObject(tx)
                every { tx.flush() } answers {
                    injected++
                    tx.abort()
                    throw object : com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException("testDB", "injected retry") {}
                }
                session.entitiesUpdater.addChangeAndRun { replayed++; true }
                assertTrue(session.getEntity(ids[0]).addLink("replay", session.getEntity(ids[1])))
            }
        } finally { first?.let { unmockkObject(it) } }
        assertEquals(1, injected)
        assertEquals(2, replayed)
        db.withStoreTx { tx ->
            assertFalse(tx.getEntity(ids[0]).addLink("replay", tx.getEntity(ids[1])))
            assertEquals(1L, tx.g().E().hasLabel(label).count().next())
        }
        println("RETRY injected=$injected replayed=$replayed")
    }

    @Test fun `concurrent duplicate additions resolve to exactly one durable edge`() {
        val label = YTDBVertexEntity.edgeClassName("concurrent")
        schema(label)
        val ids = db.withStoreTx { tx -> List(2) { tx.newEntity(VERTEX).id } }
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val executions = java.util.concurrent.atomic.AtomicInteger()
        try {
            val futures = List(2) {
                pool.submit<Unit> {
                    val store = com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl().apply { persistentStore = db.store }
                    store.transactional { session ->
                        session.entitiesUpdater.addChangeAndRun {
                            executions.incrementAndGet()
                            val tx = session.transactionInternal as YTDBStoreTransactionImpl
                            assertNull((tx.activeYtdbSession().activeTransaction as FrontendTransactionImpl).queryResultCache)
                            true
                        }
                        assertTrue(session.getEntity(ids[0]).addLink("concurrent", session.getEntity(ids[1])))
                        // Both transactions add before either is allowed to commit.
                        barrier.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            }
            futures.forEach { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(executions.get() >= 3, "At least one losing transaction must replay")
        println("CONCURRENCY initial=2 replays=${executions.get() - 2}")
        db.withStoreTx { tx ->
            assertFalse(tx.getEntity(ids[0]).addLink("concurrent", tx.getEntity(ids[1])))
            assertEquals(1L, tx.g().E().hasLabel(label).count().next())
            assertEquals(ids[1], tx.getEntity(ids[0]).getLink("concurrent")!!.id)
        }
    }

    private companion object { const val VERTEX = "WorkVertex"; const val EDGE = "WorkEdge" }
}
