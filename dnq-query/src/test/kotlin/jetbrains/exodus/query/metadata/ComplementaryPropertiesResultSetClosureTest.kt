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

import jetbrains.exodus.entitystore.youtrackdb.getTargetLocalEntityIds
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.testutil.QueryResourceRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComplementaryPropertiesResultSetClosureTest {
    @Rule
    @JvmField
    val youTrackDb = InMemoryYouTrackDB(initializeIssueSchema = false)

    @Test
    fun `backfill closes class query before the outer transaction commits`() {
        youTrackDb.withSession { session ->
            session.applySchemaInTx(model {
                entity("type2")
                entity("type1")
                association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
            })
        }
        val (ownerId, firstTargetId, secondTargetId) = youTrackDb.withStoreTx { tx ->
            val owner = createVertexAndSetLocalEntityId(tx, "type1")
            val firstTarget = createVertexAndSetLocalEntityId(tx, "type2")
            val secondTarget = createVertexAndSetLocalEntityId(tx, "type2")
            owner.addSimpleEdge("ass1", firstTarget)
            owner.addSimpleEdge("ass1", secondTarget)
            Triple(owner.id(), firstTarget.id(), secondTarget.id())
        }
        val newIndexedLinks = youTrackDb.withSession { session ->
            session.applySchemaInTx(model {
                entity("type2")
                entity("type1") {
                    index(IndexedField("ass1", isProperty = false))
                }
                association("type1", "ass1", "type2", AssociationEndCardinality._0_n)
            }).newIndexedLinks
        }

        youTrackDb.withTxSession { session ->
            val tx = session.activeTransaction
            val owner = tx.loadVertex(ownerId)
            val firstTarget = tx.loadVertex(firstTargetId)
            val secondTarget = tx.loadVertex(secondTargetId)
            assertEquals(0, owner.getTargetLocalEntityIds("ass1").size())
            val before = session.getActiveQueries().size
            QueryResourceRecorder().use { recorder ->
                recorder.attach(session)
                // begin/commit nest in YTDB. One owner stays below the batching threshold, so
                // backfill's terminal commit must leave this outer transaction alive.
                session.initializeComplementaryPropertiesForNewIndexedLinks(newIndexedLinks)
                assertTrue("The backfill class query must be captured", recorder.resources.isNotEmpty())
                assertSame(tx, session.activeTransaction)
                assertTrue(tx.isActive)
                val targetIds = owner.getTargetLocalEntityIds("ass1")
                assertEquals(2, targetIds.size())
                assertTrue(targetIds.contains(firstTarget.identity))
                assertTrue(targetIds.contains(secondTarget.identity))
                assertEquals("Backfill must close its class result set before commit", before, session.getActiveQueries().size)
            }
        }
    }
}
