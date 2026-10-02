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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.SessionListener
import com.jetbrains.youtrackdb.internal.core.query.ResultSet
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class YTDBTraversalResourceTest {

    @Rule
    @JvmField
    val youTrackDb = InMemoryYouTrackDB(initializeIssueSchema = false, autoInitializeSchemaBuddy = false)

    @Test
    fun `loadVertexOrNull preserves active query count and returns a usable vertex`() {
        createSchema()
        val (presentId, removedId) = youTrackDb.withStoreTx { tx ->
            val present = tx.newEntity(VERTEX_CLASS, 11L)
            present.setProperty("name", "present")
            val removed = tx.newEntity(VERTEX_CLASS, 12L)
            present.id as YTDBEntityId to (removed.id as YTDBEntityId)
        }
        youTrackDb.withTxSession { session -> session.loadVertex(removedId.asOId()).delete() }

        youTrackDb.withStoreTx { tx ->
            val session = tx.activeYtdbSession()
            withNativeQueries(session) {
                val before = session.getActiveQueries().size
                assertEquals(0, before)
                val vertex = assertNotNull(tx.loadVertexOrNull(presentId.asOId()))
                assertEquals(before, session.getActiveQueries().size)
                assertEquals(presentId.asOId(), vertex.id())
                assertEquals(VERTEX_CLASS, vertex.label())
                assertEquals("present", vertex.value<String>("name"))
                assertNull(tx.loadVertexOrNull(removedId.asOId()))
                assertEquals(before, session.getActiveQueries().size)
            }
        }
    }

    @Test
    fun `findEdge preserves active query count and returns a usable matching edge`() {
        createSchema()
        val (outId, inId) = youTrackDb.withStoreTx { tx ->
            val out = tx.newEntity(VERTEX_CLASS, 21L)
            val target = tx.newEntity(VERTEX_CLASS, 22L)
            val other = tx.newEntity(VERTEX_CLASS, 23L)
            out.vertex.addEdge(EDGE_CLASS, target.vertex, "marker", "match")
            out.vertex.addEdge(EDGE_CLASS, other.vertex, "marker", "other target")
            out.vertex.addEdge(OTHER_EDGE_CLASS, other.vertex, "marker", "other label")
            out.id as YTDBEntityId to (target.id as YTDBEntityId)
        }

        youTrackDb.withStoreTx { tx ->
            val session = tx.activeYtdbSession()
            withNativeQueries(session) {
                val before = session.getActiveQueries().size
                assertEquals(0, before)
                val edge = assertNotNull(tx.findEdge(EDGE_CLASS, outId.asOId(), inId.asOId()))
                assertEquals(before, session.getActiveQueries().size)
                assertEquals(EDGE_CLASS, edge.label())
                assertEquals(outId.asOId(), edge.outVertex().id())
                assertEquals(inId.asOId(), edge.inVertex().id())
                assertEquals("match", edge.value<String>("marker"))
                assertNull(tx.findEdge(EDGE_CLASS, inId.asOId(), outId.asOId()))
                assertEquals(before, session.getActiveQueries().size)
                assertNull(tx.findEdge(OTHER_EDGE_CLASS, outId.asOId(), inId.asOId()))
                assertEquals(before, session.getActiveQueries().size)
            }
        }
    }

    @Test
    fun `resolveEntityIdOrNull closes its query before returning a usable entity id`() {
        createSchema()
        // Collection IDs are final only after the schema transaction commits.
        youTrackDb.withSession { youTrackDb.schemaBuddy.initialize(it) }
        val expected = youTrackDb.withStoreTx { tx ->
            val entity = tx.newEntity(VERTEX_CLASS, 31L)
            entity.setProperty("name", "resolved")
            tx.newEntity(VERTEX_CLASS, 32L).setProperty("name", "another")
            entity.id as YTDBEntityId
        }

        youTrackDb.withStoreTx { tx ->
            val session = tx.activeYtdbSession()
            withNativeQueries(session) {
                val before = session.getActiveQueries().size
                assertEquals(0, before)
                val resolved = assertNotNull(youTrackDb.schemaBuddy.resolveEntityIdOrNull(
                    session, expected.typeId, expected.localId
                ))
                assertEquals(before, session.getActiveQueries().size)
                assertEquals(expected.typeId, resolved.typeId)
                assertEquals(expected.localId, resolved.localId)
                assertEquals(expected.asOId(), resolved.asOId())
                assertEquals(VERTEX_CLASS, resolved.getTypeName())
                assertEquals("resolved", session.loadVertex(resolved.asOId()).getProperty<String>("name"))
                assertNull(youTrackDb.schemaBuddy.resolveEntityIdOrNull(session, expected.typeId, 33L))
                assertEquals(before, session.getActiveQueries().size)
                assertNull(youTrackDb.schemaBuddy.resolveEntityIdOrNull(session, Int.MAX_VALUE, expected.localId))
                assertEquals(before, session.getActiveQueries().size)
            }
        }
    }

    private fun createSchema() {
        youTrackDb.withTxSession { session ->
            session.createVertexClassWithClassId(VERTEX_CLASS)
            session.schema.createEdgeClass(EDGE_CLASS)
            session.schema.createEdgeClass(OTHER_EDGE_CLASS)
        }
    }

    private fun withNativeQueries(session: DatabaseSessionEmbedded, block: () -> Unit) {
        // The translator reads the session context, so a scoped override avoids changing global state.
        val flag = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED
        val configuration = requireNotNull(session.configuration)
        val previous = configuration.setValue(flag, false)
        // Active-query values are weak: retain the real result sets until after all assertions.
        val retained = ArrayList<ResultSet>()
        val listener = object : SessionListener {
            override fun onCommandStart(database: DatabaseSessionEmbedded, resultSet: ResultSet) {
                retained.add(resultSet)
            }
        }
        session.registerListener(listener)
        try {
            assertFalse(configuration.getValueAsBoolean(flag))
            block()
        } finally {
            session.unregisterListener(listener)
            try {
                retained.forEach { it.close() }
            } finally {
                configuration.setValue(flag, previous)
            }
        }
    }

    private companion object {
        const val VERTEX_CLASS = "TraversalResourceVertex"
        const val EDGE_CLASS = "TraversalResourceEdge"
        const val OTHER_EDGE_CLASS = "OtherTraversalResourceEdge"
    }
}
