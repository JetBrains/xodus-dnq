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
import jetbrains.exodus.entitystore.PersistentEntityId
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.createIssue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

class RIDEntityIdTest {
    @Rule
    @JvmField
    val youTrackDb = InMemoryYouTrackDB()

    @Test
    fun `require both classId and localEntityId to create an instance`() {
        youTrackDb.provider.withSession { oSession ->
            oSession.schema.createVertexClass("type1")
        }
        val vertex: YTDBVertex = youTrackDb.provider.graph.addVertex("type1")
        assertFailsWith<IllegalStateException> {
            RIDEntityId.fromVertex(vertex)
        }

        youTrackDb.provider.withSession { oSession ->
            val oClass = oSession.schema.getClass("type1")
            oClass.setCustom(YTDBVertexEntity.CLASS_ID_CUSTOM_PROPERTY_NAME, 300.toString())
        }
        assertFailsWith<IllegalStateException> {
            RIDEntityId.fromVertex(vertex)
        }

        vertex.property(YTDBVertexEntity.LOCAL_ENTITY_ID_PROPERTY_NAME, 200L)
        RIDEntityId.fromVertex(vertex)
    }

    @Test
    fun `id representation is the same as for PersistentEntityId`() {
        val id = youTrackDb.createIssue("trista").id
        val legacyId = PersistentEntityId(id.typeId, id.localId)
        val idRepresentation = id.toString()
        val legacyIdRepresentation = legacyId.toString()

        assertEquals(legacyIdRepresentation, idRepresentation)
    }
}
