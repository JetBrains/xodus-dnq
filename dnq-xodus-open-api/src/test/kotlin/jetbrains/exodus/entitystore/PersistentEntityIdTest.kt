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
package jetbrains.exodus.entitystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64

/**
 * Pins [PersistentEntityId] as a drop-in Java-serialization replacement for the classic Xodus
 * `jetbrains.exodus.entitystore.PersistentEntityId`: consumers (e.g. Hub event-change `Data`)
 * durably persist these ids, and blobs written before the YouTrackDB migration must keep
 * deserializing after it.
 *
 * The golden blobs below were produced by the *real* Xodus class compiled from the
 * `release-3.1` sources (`entity-store/src/main/java/jetbrains/exodus/entitystore/
 * PersistentEntityId.java`, `serialVersionUID = -3875948066835180514L`, fields
 * `entityTypeId`/`entityLocalId`). The byte-identity test additionally guarantees the reverse
 * direction: blobs written by this class deserialize with the classic one (rolling upgrade).
 *
 * If this test fails, do NOT regenerate the blobs — the class has broken compatibility with
 * already-persisted data (name, serialVersionUID, field layout, or superclass hierarchy changed).
 */
class PersistentEntityIdTest {

    /** `(typeId, localId, base64 of the classic-Xodus serialized form)`. */
    private val goldenBlobs = listOf(
        Triple(
            42, 123456789L,
            "rO0ABXNyAC9qZXRicmFpbnMuZXhvZHVzLmVudGl0eXN0b3JlLlBlcnNpc3RlbnRFbnRpdHlJZMo13cTUbVQeAgACSgANZW50aXR5TG9jYWxJZEkADGVudGl0eVR5cGVJZHhwAAAAAAdbzRUAAAAq"
        ),
        Triple(
            0, 0L,
            "rO0ABXNyAC9qZXRicmFpbnMuZXhvZHVzLmVudGl0eXN0b3JlLlBlcnNpc3RlbnRFbnRpdHlJZMo13cTUbVQeAgACSgANZW50aXR5TG9jYWxJZEkADGVudGl0eVR5cGVJZHhwAAAAAAAAAAAAAAAA"
        ),
        Triple(
            Int.MAX_VALUE, Long.MAX_VALUE,
            "rO0ABXNyAC9qZXRicmFpbnMuZXhvZHVzLmVudGl0eXN0b3JlLlBlcnNpc3RlbnRFbnRpdHlJZMo13cTUbVQeAgACSgANZW50aXR5TG9jYWxJZEkADGVudGl0eVR5cGVJZHhwf/////////9/////"
        ),
    )

    @Test
    fun `deserializes blobs written by the classic Xodus PersistentEntityId`() {
        for ((typeId, localId, base64) in goldenBlobs) {
            val id = deserialize(Base64.getDecoder().decode(base64))
            assertEquals("typeId of $typeId-$localId", typeId, id.typeId)
            assertEquals("localId of $typeId-$localId", localId, id.localId)
        }
    }

    @Test
    fun `serialized form is byte-identical to the classic Xodus class`() {
        for ((typeId, localId, base64) in goldenBlobs) {
            assertArrayEquals(
                "stream of $typeId-$localId",
                Base64.getDecoder().decode(base64),
                serialize(PersistentEntityId(typeId, localId))
            )
        }
    }

    @Test
    fun `survives a serialization round-trip`() {
        val id = PersistentEntityId(7, 42L)
        assertEquals(id, deserialize(serialize(id)))
    }

    @Test
    fun `copy constructor takes the logical id of any EntityId`() {
        val copy = PersistentEntityId(PersistentEntityId(7, 42L) as EntityId)
        assertEquals(7, copy.typeId)
        assertEquals(42L, copy.localId)
    }

    @Test
    fun `toEntityId parses the toString representation`() {
        val id = PersistentEntityId.toEntityId("7-42")
        assertEquals(7, id.typeId)
        assertEquals(42L, id.localId)
        assertEquals("7-42", id.toString())
    }

    @Test
    fun `toEntityId rejects malformed representations with IllegalArgumentException like classic Xodus`() {
        // Classic EntityIdCache threw IllegalArgumentException / NumberFormatException; consumers
        // (e.g. Hub's resolveEntityID) catch IllegalArgumentException to map malformed ids to 404.
        for (bad in listOf("", "7", "-7", "7-", "7-42-1", "7--42", "a-b", "7_42")) {
            assertThrows("'$bad' must not parse", IllegalArgumentException::class.java) {
                PersistentEntityId.toEntityId(bad)
            }
        }
    }

    private fun serialize(id: PersistentEntityId): ByteArray {
        val bos = ByteArrayOutputStream()
        ObjectOutputStream(bos).use { it.writeObject(id) }
        return bos.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): PersistentEntityId =
        ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as PersistentEntityId
}
