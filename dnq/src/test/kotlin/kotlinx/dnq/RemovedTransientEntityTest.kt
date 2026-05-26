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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import kotlinx.dnq.listener.XdEntityListener
import kotlinx.dnq.listener.addListener
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemovedTransientEntityTest : DBTest() {

    @Test
    fun `getPropertyOldValue on snapshot of removed entity returns pre-removal value`() {
        val user = store.transactional {
            User.new {
                login = "alice"
                skill = 1
            }
        }

        store.transactional {
            val transientEntity = user.entity as TransientEntity
            user.delete()
            val snapshot = transientEntity.store.threadSessionOrThrow
                .transientChangesTracker
                .getSnapshotEntity(transientEntity)
            assertTrue(snapshot.isRemoved)
            assertEquals("alice", snapshot.getPropertyOldValue("login"))
        }
    }

    /**
     * Reproducer for XD-1272.
     *
     * A flushed-sync listener that reads a blob field on the [RemovedTransientEntity] snapshot
     * triggers `RecordAbstract.checkForBinding` and throws
     * `Record #<rid> is not bound to the current session` when the underlying YTDB
     * `RecordBytes` was never loaded into memory before the entity was removed.
     *
     * Production stack (Hub pre-merge):
     *   YTDBVertexEntity.getBlob -> RecordBytes.toStream -> checkForBinding (NOT_LOADED) -> throw
     *   <- RemovedTransientEntity.getBlob
     *   <- reattachAndGetBlob (XdTextProperty.isDefined / XdURLAvatar.getAvatarURL)
     *   <- JetPassEventListener.handleChange (flushed listener)
     *   <- TransientSessionImpl.notifyFlushedListeners -> flush()
     */
    @Test
    fun `flushed listener can read blob bytes from removed entity snapshot`() {
        val image = store.transactional {
            Image.new {
                content = "hello"
            }
        }

        val captured = AtomicReference<String?>()
        Image.addListener(store, object : XdEntityListener<Image> {
            override fun removedSync(removed: Image) {
                // Reads the blob through the RemovedTransientEntity snapshot:
                //   XdTextProperty.getValue -> reattachAndGetBlobString -> RemovedTransientEntity.getBlobString
                //   -> YTDBVertexEntity.getBlobString -> RecordBytes.toStream
                captured.set(removed.content)
            }
        })

        // Re-attach in a new session so the blob's RecordBytes stays NOT_LOADED
        // until the listener fires after flush.
        store.transactional {
            image.delete()
        }

        assertEquals("hello", captured.get())
    }

    /**
     * Same flushed-listener scenario as [flushed listener can read blob bytes from removed entity snapshot],
     * but for a regular (non-blob) String property. Regular properties are stored inline on the
     * vertex (no separate `RecordBytes`), so this scenario should NOT hit the "not bound to session"
     * error — the snapshot must return the pre-removal value.
     */
    @Test
    fun `flushed listener can read regular String property from removed entity snapshot`() {
        val user = store.transactional {
            User.new {
                login = "alice"
                skill = 1
            }
        }

        val captured = AtomicReference<String?>()
        User.addListener(store, object : XdEntityListener<User> {
            override fun removedSync(removed: User) {
                captured.set(removed.login)
            }
        })

        store.transactional {
            user.delete()
        }

        assertEquals("alice", captured.get())
    }
}
