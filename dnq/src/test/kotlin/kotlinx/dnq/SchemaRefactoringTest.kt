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

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.PersistentEntityStore
import kotlinx.dnq.query.size
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Entity-type refactorings (`renameEntityTypeRefactoring` / `deleteEntityTypeRefactoring`) reach
 * YTDB's schema through the business transaction they were called in (XD-1283 site 6). These
 * tests pin the two properties that the join brings:
 *
 * 1. a transaction whose ONLY change is such a refactoring is committed, not silently aborted -
 *    in-tx DDL leaves no entity changes, so the flush path had to stop treating "no tracked
 *    changes" as "nothing to commit": the refactorings API is rescued by the DDL-aware
 *    idempotency check in `flushChanges()`, DDL that queues no change at all (schema operations
 *    joining the caller's transaction directly) by the same check in the `flush()` gate;
 * 2. the DDL is rolled back with its transaction instead of leaking out of it.
 */
class SchemaRefactoringTest : DBTest() {

    private val persistentStore: PersistentEntityStore
        get() = store.persistentStore

    private fun entityTypeExists(typeName: String) = transactional {
        persistentStore.getEntityTypeId(typeName) >= 0
    }

    @Test
    fun `renameEntityTypeRefactoring is committed when it is the only change in the transaction`() {
        transactional {
            store.renameEntityTypeRefactoring(Image.entityType, "RenamedImage")
        }

        assertThat(entityTypeExists("RenamedImage")).isTrue()
        assertThat(entityTypeExists(Image.entityType)).isFalse()
    }

    @Test
    fun `deleteEntityTypeRefactoring is committed when it is the only change in the transaction`() {
        transactional {
            store.deleteEntityTypeRefactoring(Image.entityType)
        }

        assertThat(entityTypeExists(Image.entityType)).isFalse()
    }

    @Test
    fun `renameEntityTypeRefactoring is discarded when the transaction is rolled back`() {
        assertFailsWith<IllegalStateException> {
            transactional {
                store.renameEntityTypeRefactoring(Image.entityType, "RenamedImage")
                throw IllegalStateException("boom")
            }
        }

        assertThat(entityTypeExists("RenamedImage")).isFalse()
        assertThat(entityTypeExists(Image.entityType)).isTrue()
    }

    @Test
    fun `deleteEntityTypeRefactoring is discarded when the transaction is rolled back`() {
        val imageId = transactional { Image.new { content = "content" }.entityId }

        assertFailsWith<IllegalStateException> {
            transactional {
                store.deleteEntityTypeRefactoring(Image.entityType)
                throw IllegalStateException("boom")
            }
        }

        assertThat(entityTypeExists(Image.entityType)).isTrue()
        transactional {
            assertThat(Image.all().toList().map { it.entityId }).containsExactly(imageId)
        }
    }

    @Test
    fun `schema and data changes made in one transaction are committed together`() {
        transactional {
            User.new {
                login = "zeckson"
                skill = 1
            }
            store.renameEntityTypeRefactoring(Image.entityType, "RenamedImage")
        }

        assertThat(entityTypeExists("RenamedImage")).isTrue()
        transactional {
            assertThat(User.all().size()).isEqualTo(1)
        }
    }

    @Test
    fun `schema changes not registered as transient changes are committed too`() {
        // The DDL-blind flush gate (XD-1283, AD7/AD15): DDL that does not go through the
        // entities updater - the refactorings' change queue - leaves the session with no
        // transient changes at all. The queued-changes check alone would skip the flush and
        // have closePersistentSession() abort the DDL. This is the shape of the AD3 guard
        // branch, where schema operations join the caller's transaction directly.
        //
        // Calling the persistent store directly is a mechanism stand-in for that shape, not a
        // supported application API: unlike the refactorings, such DDL is not in the replay
        // queue, so a NeedRetryException replay would silently drop it (the AD3 guard branch
        // itself is replay-safe - it is re-entered from the replayed change closures).
        transactional {
            persistentStore.renameEntityType(Image.entityType, "RenamedImage")
        }

        assertThat(entityTypeExists("RenamedImage")).isTrue()
        assertThat(entityTypeExists(Image.entityType)).isFalse()
    }

    @Test
    fun `schema and data changes made in one transaction are rolled back together`() {
        assertFailsWith<IllegalStateException> {
            transactional {
                User.new {
                    login = "zeckson"
                    skill = 1
                }
                store.renameEntityTypeRefactoring(Image.entityType, "RenamedImage")
                throw IllegalStateException("boom")
            }
        }

        assertThat(entityTypeExists("RenamedImage")).isFalse()
        assertThat(entityTypeExists(Image.entityType)).isTrue()
        transactional {
            assertThat(User.all().size()).isEqualTo(0)
        }
    }

    @Test
    fun `schema changes are rejected in a readonly transaction`() {
        // A readonly business transaction is never flushed and always reports itself idempotent,
        // so DDL joining it would be discarded without a trace (XD-1283 site 6).
        assertFailsWith<IllegalStateException> {
            transactional(readonly = true) {
                persistentStore.renameEntityType(Image.entityType, "RenamedImage")
            }
        }

        assertThat(entityTypeExists("RenamedImage")).isFalse()
        assertThat(entityTypeExists(Image.entityType)).isTrue()
    }

    @Test
    fun `a session with only schema changes is not idempotent`() {
        transactional { session ->
            assertThat(session.isIdempotent).isTrue()
            store.renameEntityTypeRefactoring(Image.entityType, "RenamedImage")
            assertThat(session.isIdempotent).isFalse()
        }
    }
}
