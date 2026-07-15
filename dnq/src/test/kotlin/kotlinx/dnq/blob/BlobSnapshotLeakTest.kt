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
package kotlinx.dnq.blob

import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.util.reattach
import kotlinx.dnq.xdBlobStringProp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the blob-change markers (`TransientEntitiesUpdaterImpl.NULL_BLOB` /
 * `NOT_NULL_BLOB`) leaking out of the snapshot entity's read API.
 *
 * A blob mutation records an internal marker object as the property "old value" in the change
 * tracker. When a listener read that old value from the snapshot entity, [ReadonlyTransientEntity]
 * returned the marker verbatim:
 *  - `getProperty(name)` returned the raw marker object — a non-`String`, non-`null` `Comparable`, so
 *    any consumer doing `value as String` on a text-blob property hit a
 *    `ClassCastException`;
 *  - `getBlobString(name)` returned `marker.toString()` = `"Empty Binary Data"` / `"Binary Data"` —
 *    a debug string that is never valid blob content.
 *
 * The snapshot must instead behave like Xodus: expose the real pre-transaction value and never an
 * internal marker. These tests clear/replace a blob through the raw `deleteBlob`/`setBlob` API (the
 * paths that record a marker) and assert the snapshot reads are clean.
 */
class BlobSnapshotLeakTest : DBTest() {

    class Doc(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Doc>()

        var content by xdBlobStringProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Doc)
    }

    /** Captures snapshot reads for the target entity during beforeFlush (a listener read of the old value). */
    private class SnapshotProbe(private val targetId: () -> EntityId?) : TransientStoreSessionListener {
        var captured = false
        var scalarOldValue: Comparable<*>? = null
        var blobStringOldValue: String? = null
        var castSucceeded = false
        val errors = mutableListOf<Throwable>()

        override fun beforeFlushBeforeConstraints(
            session: TransientStoreSession,
            changedEntities: Set<TransientEntityChange>
        ) {
            val id = targetId() ?: return
            val change = changedEntities.find { it.transientEntity.id == id } ?: return
            try {
                val snapshot = change.snapshotEntity
                scalarOldValue = snapshot.getProperty("content")
                // Reproduces the leak: the scalar old value used to be the internal marker, so this
                // cast threw ClassCastException.
                @Suppress("UNUSED_VARIABLE")
                val asString: String? = scalarOldValue as String?
                castSucceeded = true
                blobStringOldValue = snapshot.getBlobString("content")
                captured = true
            } catch (e: Throwable) {
                errors.add(e)
            }
        }

        override fun flushed(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) {}

        override fun afterConstraintsFail(
            session: TransientStoreSession,
            exceptions: Set<DataIntegrityViolationException>
        ) {
        }
    }

    @Test
    fun `snapshot must not leak the blob marker when a blob is cleared via deleteBlob`() {
        val doc = store.transactional { Doc.new { content = "not empty" } }

        val probe = SnapshotProbe { doc.entityId }
        store.addListener(probe)
        // Raw deleteBlob records the NULL_BLOB marker as the change's old value (the path that
        // produced the ClassCastException). The XdBlobStringProp `content = null` setter takes a
        // different path (setBlobString(null)) that records the old string, so it must be the raw
        // API here to reproduce the marker.
        store.transactional { doc.reattach().deleteBlob("content") }

        assertTrue(probe.captured, "beforeFlush probe did not observe the change")
        assertTrue(probe.errors.isEmpty(), "snapshot read threw: ${probe.errors.firstOrNull()}")
        assertTrue(probe.castSucceeded, "old value could not be cast to String (marker leaked)")
        // getProperty must not expose the marker: a blob has no scalar value → null.
        assertEquals(null, probe.scalarOldValue)
        // getBlobString must return the real pre-transaction content, not "Empty Binary Data".
        assertEquals("not empty", probe.blobStringOldValue)
    }

    @Test
    fun `snapshot must not leak the blob marker when a blob is replaced via setBlob`() {
        val doc = store.transactional { Doc.new { content = "not empty" } }

        val probe = SnapshotProbe { doc.entityId }
        store.addListener(probe)
        // Replacing an existing blob records the NOT_NULL_BLOB marker.
        store.transactional { doc.reattach().setBlob("content", "replaced".byteInputStream()) }

        assertTrue(probe.captured, "beforeFlush probe did not observe the change")
        assertTrue(probe.errors.isEmpty(), "snapshot read threw: ${probe.errors.firstOrNull()}")
        assertTrue(probe.castSucceeded, "old value could not be cast to String (marker leaked)")
        assertEquals(null, probe.scalarOldValue)
        // The snapshot exposes the ORIGINAL (pre-transaction) content, not the replacement.
        assertEquals("not empty", probe.blobStringOldValue)
    }
}
