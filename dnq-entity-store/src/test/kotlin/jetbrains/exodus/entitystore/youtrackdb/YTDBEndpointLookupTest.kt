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

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import com.jetbrains.youtrackdb.internal.core.query.ResultSet
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl
import com.jetbrains.youtrackdb.internal.core.sql.executor.cache.CachedEntry
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import io.mockk.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class YTDBEndpointLookupTest {
    @Rule @JvmField
    val db = InMemoryYouTrackDB(initializeIssueSchema = false, autoInitializeSchemaBuddy = false)

    private fun schema(name: String = EDGE, indexed: Boolean = true,
                       fields: Array<String> = arrayOf("in", "out"), unique: Boolean = true) {
        db.withTxSession { s ->
            if (s.schema.getClass(VERTEX) == null) s.createVertexClassWithClassId(VERTEX)
            val c = s.schema.createEdgeClass(name)
            c.createProperty("marker", PropertyType.STRING)
            if (indexed) c.createIndex("${name.hashCode().toUInt()}_endpoints",
                if (unique) SchemaClass.INDEX_TYPE.UNIQUE else SchemaClass.INDEX_TYPE.NOTUNIQUE, *fields)
        }
    }

    private fun pair(): Pair<RID, RID> = db.withStoreTx { tx ->
        tx.newEntity(VERTEX, 1).vertex.id() to tx.newEntity(VERTEX, 2).vertex.id()
    }

    private fun add(tx: YTDBStoreTransactionImpl, ids: Pair<RID, RID>, name: String = EDGE) =
        tx.getVertex(ids.first).addEdge(name, tx.getVertex(ids.second), "marker", "present")

    private fun lookup(tx: YTDBStoreTransactionImpl, ids: Pair<RID, RID>, name: String = EDGE) =
        tx.findEdge(name, ids.first, ids.second)

    private fun closed(s: DatabaseSessionEmbedded,
                       cacheOn: Boolean = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean(),
                       requireEntry: Boolean = false) {
        assertEquals(0, s.activeQueries.size)
        val cache = (s.activeTransaction as FrontendTransactionImpl).queryResultCache
        if (!cacheOn) {
            assertNull(cache, "Cache-off transaction must not have a cache")
            return
        }
        assertNotNull(cache, "Cache-on transaction must have a cache")
        val f = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }
        val entries = (f.get(cache) as Map<*, *>).values
        if (requireEntry) assertTrue(entries.isNotEmpty(), "Resource checks require a retained cache entry")
        for (entry in entries) for (field in listOf("stream", "plan")) {
            val resource = entry!!.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(entry)
            assertNull(resource, "Cached $field must be released")
        }
    }

    @Test fun `indexed hits misses direction label and removal after closure`() {
        schema()
        schema("OtherEndpointEdge")
        val ids = pair()
        db.withStoreTx { add(it, ids) }
        db.withStoreTx { tx ->
            val edge = assertNotNull(lookup(tx, ids))
            closed(tx.activeYtdbSession())
            assertEquals(EDGE, edge.label())
            assertEquals(ids.first, edge.outVertex().id())
            assertEquals(ids.second, edge.inVertex().id())
            assertEquals("present", edge.value<String>("marker"))
            assertNull(lookup(tx, ids.second to ids.first))
            assertNull(lookup(tx, ids, "OtherEndpointEdge"))
            closed(tx.activeYtdbSession())
            edge.remove()
            assertNull(lookup(tx, ids))
            closed(tx.activeYtdbSession())
        }
        db.withStoreTx { assertNull(lookup(it, ids)) }
    }

    @Test fun `visibility agrees with cache off and on past invalidation threshold`() {
        schema()
        val persisted = pair()
        val flag = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED
        for (cacheOn in listOf(false, true)) {
            val previous = flag.getValueAsBoolean()
            flag.setValue(cacheOn)
            try { db.withStoreTx { tx ->
                val s = tx.activeYtdbSession()
                closed(s, cacheOn)
                for (temporary in listOf(false, true)) {
                    val ids = if (temporary) tx.newEntity(VERTEX, 3).vertex.id() to
                        tx.newEntity(VERTEX, 4).vertex.id() else persisted
                    repeat(6) {
                        assertNull(lookup(tx, ids))
                        closed(s, cacheOn, requireEntry = cacheOn && !temporary && it == 0)
                        add(tx, ids)
                        assertNotNull(lookup(tx, ids))
                        closed(s, cacheOn, requireEntry = cacheOn && !temporary && it == 0)
                        assertNotNull(lookup(tx, ids)).remove()
                        assertNull(lookup(tx, ids))
                        closed(s, cacheOn)
                        add(tx, ids)
                        assertNotNull(lookup(tx, ids)).remove()
                        assertNull(lookup(tx, ids))
                        closed(s, cacheOn)
                    }
                }
            } } finally { flag.setValue(previous) }
        }
    }

    @Test fun `flush commit and rollback preserve visibility`() {
        schema()
        val ids = pair()
        db.withStoreTx { tx ->
            add(tx, ids)
            assertNotNull(lookup(tx, ids))
            tx.flush()
            assertNotNull(lookup(tx, ids)).remove()
            assertNull(lookup(tx, ids))
            tx.revert()
            assertNotNull(lookup(tx, ids))
        }
        db.withStoreTx { tx ->
            assertNotNull(lookup(tx, ids)).remove()
            tx.flush()
            assertNull(lookup(tx, ids))
            add(tx, ids)
            tx.revert()
            assertNull(lookup(tx, ids))
        }
    }

    @Test fun `missing unindexed and hostile names never issue endpoint SELECT`() {
        val names = listOf("UnindexedEdge", "tick`_link", "Docs\\update_link",
            "victim\\u0060 LET \$a = \\u0060x_link", "invalid\\uZZZZ_link", "space name_link", "é_link")
        names.forEach { schema(it, indexed = it != "UnindexedEdge") }
        val ids = pair()
        db.withStoreTx { tx ->
            names.forEach { add(tx, ids, it) }
            val s = tx.activeYtdbSession()
            mockkObject(s)
            try {
                every { s.query(any<String>(), any<Map<*, *>>()) } throws AssertionError("Unexpected SELECT")
                names.forEach { assertNotNull(lookup(tx, ids, it)) }
                assertNull(lookup(tx, ids, "MissingEdge"))
                assertNull(s.schema.getClass("MissingEdge"))
                verify(exactly = 0) { s.query(any<String>(), any<Map<*, *>>()) }
            } finally { unmockkObject(s) }
        }
    }

    @Test fun `Country Region sanitized name uses bound SQL`() {
        val name = YTDBVertexEntity.edgeClassName("__CUSTOM_FIELD__Country/Region_227")
        assertEquals("__CUSTOM_FIELD__Country_Region_227_link", name)
        schema(name)
        val ids = pair()
        db.withStoreTx { tx ->
            add(tx, ids, name)
            val s = tx.activeYtdbSession()
            mockkObject(s)
            try {
                assertNotNull(lookup(tx, ids, name))
                verify(exactly = 1) { s.query(
                    "SELECT FROM `$name` WHERE outV() = :outId AND inV() = :inId LIMIT 1",
                    mapOf("outId" to ids.first, "inId" to ids.second)) }
            } finally { unmockkObject(s) }
        }
    }

    @Test fun `direct reversed nonunique and longer indexes use the add rule`() {
        val layouts = listOf(arrayOf("in", "out"), arrayOf("out", "in"), arrayOf("in", "out", "marker"))
        for ((i, fields) in layouts.withIndex()) {
            val name = "Layout${i}Edge"
            schema(name, fields = fields, unique = i != 1)
            val ids = pair()
            db.withStoreTx { add(it, ids, name) }
            db.withStoreTx { tx ->
                val s = tx.activeYtdbSession()
                assertTrue((s.schema.getClass(name) as SchemaClassInternal).areIndexed(s, "in", "out"))
                mockkObject(s)
                try {
                    assertNotNull(lookup(tx, ids, name))
                    verify(exactly = 1) { s.query(any<String>(), any<Map<*, *>>()) }
                } finally { unmockkObject(s) }
            }
        }
    }

    @Test fun `inherited index preserves parent and child label semantics`() {
        schema()
        db.withTxSession { s -> s.schema.createClass("ChildEdge", s.schema.getClass(EDGE)) }
        val parent = pair()
        val child = pair()
        db.withStoreTx { tx -> add(tx, parent); add(tx, child, "ChildEdge") }
        db.withStoreTx { tx ->
            val s = tx.activeYtdbSession()
            assertTrue((s.schema.getClass("ChildEdge") as SchemaClassInternal).areIndexed(s, "in", "out"))
            mockkObject(s)
            try {
                assertEquals("ChildEdge", assertNotNull(lookup(tx, child)).label())
                assertEquals("ChildEdge", assertNotNull(lookup(tx, child, "ChildEdge")).label())
                assertNull(lookup(tx, parent, "ChildEdge"))
                assertEquals(EDGE, assertNotNull(lookup(tx, parent)).label())
                verify(exactly = 4) { s.query(any<String>(), any<Map<*, *>>()) }
            } finally { unmockkObject(s) }
        }
    }

    @Test fun `transaction created and dropped indexes change the guard without stale decisions`() {
        schema(indexed = false)
        val ids = pair()
        fun check(tx: YTDBStoreTransactionImpl, indexed: Boolean, present: Boolean = true) {
            val s = tx.activeYtdbSession()
            assertEquals(indexed, (s.schema.getClass(EDGE) as SchemaClassInternal).areIndexed(s, "in", "out"))
            mockkObject(s)
            try {
                assertEquals(present, lookup(tx, ids) != null)
                verify(exactly = if (indexed) 1 else 0) {
                    s.query(match { it.startsWith("SELECT FROM `$EDGE`") }, any<Map<*, *>>())
                }
            } finally { unmockkObject(s) }
        }
        db.withStoreTx { tx ->
            check(tx, false, present = false)
            // Transactional index creation requires an empty committed source collection.
            tx.activeYtdbSession().schema.getClass(EDGE).createIndex("changing", SchemaClass.INDEX_TYPE.UNIQUE, "in", "out")
            check(tx, false, present = false)
        }
        db.withStoreTx { add(it, ids) }
        db.withStoreTx { tx ->
            check(tx, true)
            tx.activeYtdbSession().command("DROP INDEX changing")
            check(tx, false)
        }
        db.withStoreTx { check(it, false) }
    }

    @Test fun `null ignoring longer index records accepted SELECT miss`() {
        schema(indexed = false)
        db.withTxSession { s -> s.schema.getClass(EDGE).createIndex("partial", "UNIQUE", null,
            mapOf<String, Any>("ignoreNullValues" to true), *arrayOf("in", "out", "marker")) }
        val ids = pair()
        db.withStoreTx { tx -> tx.getVertex(ids.first).addEdge(EDGE, tx.getVertex(ids.second)) }
        db.withStoreTx { tx ->
            val s = tx.activeYtdbSession()
            assertTrue((s.schema.getClass(EDGE) as SchemaClassInternal).areIndexed(s, "in", "out"))
            assertTrue(tx.getVertex(ids.first).edges(org.apache.tinkerpop.gremlin.structure.Direction.OUT, EDGE).hasNext())
            // Custom null-ignoring longer indexes can omit an edge from SELECT.
            assertNull(lookup(tx, ids))
        }
    }

    @Test fun `repeated indexed additions return false and keep one edge`() {
        val name = YTDBVertexEntity.edgeClassName("repeated")
        schema(name)
        val ids = db.withStoreTx { tx ->
            val source = tx.newEntity(VERTEX, 1)
            val target = tx.newEntity(VERTEX, 2)
            assertTrue(source.addLink("repeated", target))
            repeat(3) { assertFalse(source.addLink("repeated", target)) }
            source.id to target.id
        }
        db.withStoreTx { tx ->
            val source = tx.getEntity(ids.first)
            assertFalse(source.addLink("repeated", tx.getEntity(ids.second)))
            assertEquals(1L, tx.g().V((ids.first as YTDBEntityId).asOId()).outE(name).count().next())
        }
    }

    @Test fun `real uncached stream and plan close before hit miss and failure return`() {
        val flag = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED
        val previous = flag.getValueAsBoolean()
        flag.setValue(false)
        try {
            schema()
            val ids = pair()
            db.withStoreTx { add(it, ids) }
            db.withStoreTx { tx ->
                val s = tx.activeYtdbSession()
                closed(s, cacheOn = false)
                val original = IllegalArgumentException("uncached stream sentinel")
                for (mode in listOf("hit", "miss", "failure")) {
                    var stream: ExecutionStream? = null
                    var plan: com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan? = null
                    mockkObject(s)
                    try {
                        every { s.query(any<String>(), any<Map<*, *>>()) } answers {
                            val result = callOriginal() as LocalResultSet
                            plan = result.internalExecutionPlan.also { mockkObject(it) }
                            stream = (result.javaClass.getDeclaredField("stream").apply { isAccessible = true }
                                .get(result) as ExecutionStream).also { mockkObject(it) }
                            if (mode == "failure") every { stream!!.hasNext(any()) } throws original
                            result
                        }
                        when (mode) {
                            "hit" -> assertNotNull(lookup(tx, ids))
                            "miss" -> assertNull(lookup(tx, ids.second to ids.first))
                            else -> assertSame(original, assertFails { lookup(tx, ids) })
                        }
                        verify(exactly = 1) { stream!!.close(any()) }
                        verify(exactly = 1) { plan!!.close() }
                        closed(s, cacheOn = false)
                    } finally {
                        stream?.let { unmockkObject(it) }
                        plan?.let { unmockkObject(it) }
                        unmockkObject(s)
                    }
                }
            }
        } finally { flag.setValue(previous) }
    }

    @Test fun `iteration failure closes results and reaches transactional caller during replay`() {
        schema()
        val ids = pair()
        val store = TransientEntityStoreImpl().apply { persistentStore = db.store }
        val original = IllegalArgumentException("iteration sentinel")
        var queryCalls = 0
        var replayCalls = 0
        val result = mockk<ResultSet>()
        every { result.hasNext() } throws original
        every { result.close() } just Runs
        var session: DatabaseSessionEmbedded? = null
        val failure = assertFails {
            store.transactional { transient ->
                transient.entitiesUpdater.addChangeAndRun { replayCalls++; true }
                transient.entitiesUpdater.addChange {
                    val tx = transient.transactionInternal as YTDBStoreTransactionImpl
                    val s = tx.activeYtdbSession()
                    session = s
                    mockkObject(s)
                    every { s.query(any<String>(), any<Map<*, *>>()) } answers {
                        queryCalls++
                        result
                    }
                    lookup(tx, ids)
                    true
                }
                transient.entitiesUpdater.replayChanges()
            }
        }
        session?.let { unmockkObject(it) }
        assertTrue(generateSequence(failure) { it.cause }.any { it === original }, failure.toString())
        assertEquals(2, replayCalls)
        assertEquals(1, queryCalls)
        verify(exactly = 1) { result.close() }
    }

    @Test fun `real cached read failure reaches caller and releases resources at transaction end`() {
        val flag = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED
        val previous = flag.getValueAsBoolean()
        // The core reads the global flag instead of the database option.
        flag.setValue(true)
        try {
            schema()
            val ids = pair()
            db.withStoreTx { add(it, ids) }
            var retained: CachedEntry? = null
            db.withStoreTx { tx ->
                val s = tx.activeYtdbSession()
                assertNotNull((s.activeTransaction as FrontendTransactionImpl).queryResultCache)
                val original = IllegalArgumentException("real stream sentinel")
                var stream: ExecutionStream? = null
                mockkObject(s)
                try {
                    every { s.query(any<String>(), any<Map<*, *>>()) } answers {
                        val result = callOriginal() as ResultSet
                        val cache = assertNotNull((s.activeTransaction as FrontendTransactionImpl).queryResultCache)
                        val field = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }
                        val entry = (field.get(cache) as Map<*, *>).values.single() as CachedEntry
                        retained = entry
                        val liveStream = assertNotNull(entry.stream, "Live stream missing before injection")
                        assertNotNull(entry.plan, "Live plan missing before injection")
                        stream = liveStream
                        mockkObject(liveStream)
                        every { liveStream.hasNext(any()) } throws original
                        result
                    }
                    val failure = assertFails { lookup(tx, ids) }
                    if (failure !== original) throw failure
                    assertSame(original, failure)
                    verify(exactly = 1) { s.query(any<String>(), any<Map<*, *>>()) }
                    // A failed cached read retains resources until the transaction ends.
                    assertNotNull(assertNotNull(retained).stream)
                    assertNotNull(assertNotNull(retained).plan)
                    assertEquals(0, assertNotNull(retained).liveViewCount)
                } finally {
                    stream?.let { unmockkObject(it) }
                    unmockkObject(s)
                }
            }
            assertNull(assertNotNull(retained).stream)
            assertNull(assertNotNull(retained).plan)
        } finally { flag.setValue(previous) }
    }

    @Test fun `query call rollback records wrapper limit during replay`() {
        schema()
        val ids = pair()
        val store = TransientEntityStoreImpl().apply { persistentStore = db.store }
        var original: Throwable? = null
        var queryCalls = 0
        var session: DatabaseSessionEmbedded? = null
        val failure = assertFails {
            store.transactional { transient ->
                val tx = transient.transactionInternal as YTDBStoreTransactionImpl
                val s = tx.activeYtdbSession()
                session = s
                mockkObject(s)
                every { s.query(match { it.startsWith("SELECT FROM `$EDGE`") }, any<Map<*, *>>()) } answers {
                    queryCalls++
                    try {
                        s.query("SELECT FROM `MissingFailureClass`", emptyMap<String, Any>())
                    } catch (e: Throwable) {
                        original = e
                        throw e
                    }
                }
                transient.entitiesUpdater.addChange { lookup(tx, ids); true }
                transient.entitiesUpdater.replayChanges()
            }
        }
        session?.let { unmockkObject(it) }
        assertNotNull(original)
        assertEquals(1, queryCalls)
        // Existing DNQ wrapper limit shared by all DNQ SQL queries.
        assertIs<IllegalStateException>(failure)
        assertEquals("The transaction is finished", failure.message)
        assertFalse(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
    }

    private companion object {
        const val VERTEX = "EndpointVertex"
        const val EDGE = "EndpointEdge"
    }
}
