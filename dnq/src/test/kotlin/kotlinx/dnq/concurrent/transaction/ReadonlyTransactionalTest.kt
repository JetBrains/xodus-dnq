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
package kotlinx.dnq.concurrent.transaction

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.env.ReadonlyTransactionException
import kotlinx.dnq.DBTest
import kotlinx.dnq.creator.findOrNew
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Covers the restored `transactional(readonly = true)` semantics:
 * requested-readonly sessions, write rejection, mismatch-suspend nesting,
 * and requested-readonly persistent-transaction lifecycle across flush/revert.
 */
class ReadonlyTransactionalTest : DBTest() {

    @Test
    fun `readonly session reports isReadonly and opens a readonly persistent transaction`() {
        transactional(readonly = true) { txn ->
            assertThat(txn.isReadonly).isTrue()
            assertThat(txn.transactionInternal.isReadonly).isTrue()
        }
        transactional { txn ->
            assertThat(txn.isReadonly).isFalse()
            assertThat(txn.transactionInternal.isReadonly).isFalse()
        }
    }

    @Test
    fun `write inside readonly throws and session stays usable after catch`() {
        transactional {
            User.new { login = "existing"; skill = 1 }
        }

        transactional(readonly = true) { txn ->
            assertFailsWith<ReadonlyTransactionException> {
                User.new { login = "rejected"; skill = 1 }
            }

            // the session must still be open, readonly and usable
            assertThat(txn.isOpened).isTrue()
            assertThat(txn.isReadonly).isTrue()
            assertThat(txn.hasChanges()).isFalse()
            assertQuery(User.all()).hasSize(1)

            // property writes are also rejected, again without corrupting the session
            val user = User.all().first()
            assertFailsWith<ReadonlyTransactionException> {
                user.name = "new name"
            }
            assertThat(user.login).isEqualTo("existing")
        }

        transactional {
            assertThat(User.all().toList().map { it.login }).containsExactly("existing")
        }
    }

    @Test
    fun `no-op link mutation inside readonly also throws`() {
        transactional {
            User.new { login = "existing"; skill = 1 }
        }

        transactional(readonly = true) {
            val user = User.all().first()
            assertThat(user.supervisor).isNull()
            // setting an absent link to null is a logical no-op, but the write attempt
            // is rejected eagerly (accepted AD-2 strictness)
            assertFailsWith<ReadonlyTransactionException> {
                user.supervisor = null
            }
        }
    }

    @Test
    fun `readonly outer with plain inner suspends into an independent committing transaction`() {
        var innerSession: TransientStoreSession? = null

        transactional(readonly = true) { outer ->
            transactional { inner ->
                innerSession = inner
                assertThat(inner).isNotSameInstanceAs(outer)
                assertThat(inner.isReadonly).isFalse()
                User.new { login = "inner-user"; skill = 1 }
                // inner commits independently on block exit
            }

            // the outer readonly session is restored and still rejects writes
            assertThat(store.threadSession).isSameInstanceAs(outer)
            assertThat(outer.isReadonly).isTrue()
            assertFailsWith<ReadonlyTransactionException> {
                User.new { login = "outer-user"; skill = 1 }
            }
        }

        assertThat(innerSession).isNotNull()
        transactional {
            assertThat(User.all().toList().map { it.login }).containsExactly("inner-user")
        }
    }

    @Test
    fun `plain outer with readonly inner suspends into an independent readonly transaction`() {
        transactional { outer ->
            User.new { login = "outer-user"; skill = 1 }

            transactional(readonly = true) { inner ->
                assertThat(inner).isNotSameInstanceAs(outer)
                assertThat(inner.isReadonly).isTrue()
                assertFailsWith<ReadonlyTransactionException> {
                    User.new { login = "inner-user"; skill = 1 }
                }
            }

            // the outer session is restored and still writable
            assertThat(store.threadSession).isSameInstanceAs(outer)
            User.new { login = "outer-user-2"; skill = 2 }
        }

        transactional {
            assertThat(User.all().toList().map { it.login })
                .containsExactly("outer-user", "outer-user-2")
        }
    }

    @Test
    fun `flush does not make a nested plain transactional suspend`() {
        transactional { txn ->
            User.new { login = "before-flush"; skill = 1 }
            txn.flush()
            // flush flips the mutable readonly flag; the session was requested writable though
            assertThat(txn.isReadonly).isTrue()

            transactional { inner ->
                // no mismatch: the session is reused, not suspended
                assertThat(inner).isSameInstanceAs(txn)
                User.new { login = "after-flush"; skill = 1 }
            }
        }

        transactional {
            assertThat(User.all().toList().map { it.login })
                .containsExactly("before-flush", "after-flush")
        }
    }

    @Test
    fun `findOrNew found in readonly session - flush reopens a readonly persistent transaction`() {
        transactional {
            User.new { login = "existing"; skill = 1 }
        }

        transactional(readonly = true) { txn ->
            val found = User.findOrNew { login = "existing"; skill = 1 }
            assertThat(found.login).isEqualTo("existing")
            // the found-branch queues a change without hitting the readonly check
            assertThat(txn.hasChanges()).isTrue()

            txn.flush()

            assertThat(txn.hasChanges()).isFalse()
            assertThat(txn.transactionInternal.isReadonly).isTrue()
        }
    }

    @Test
    fun `findOrNew found in readonly session - revert clears changes and stays readonly`() {
        transactional {
            User.new { login = "existing"; skill = 1 }
        }

        transactional(readonly = true) { txn ->
            User.findOrNew { login = "existing"; skill = 1 }
            assertThat(txn.hasChanges()).isTrue()

            txn.revert()

            assertThat(txn.hasChanges()).isFalse()
            assertThat(txn.transactionInternal.isReadonly).isTrue()
        }
    }

    @Test
    fun `findOrNew found in readonly session - implicit end-of-block commit completes`() {
        transactional {
            User.new { login = "existing"; skill = 1 }
        }

        transactional(readonly = true) { txn ->
            val found = User.findOrNew { login = "existing"; skill = 1 }
            assertThat(found.login).isEqualTo("existing")
            assertThat(txn.hasChanges()).isTrue()
            // no explicit flush()/revert(): the block ends normally and commit()
            // drives the flush loop over the pending queued change
        }

        transactional {
            // no duplicate was created and nothing leaked from the readonly session
            assertThat(User.all().toList().map { it.login }).containsExactly("existing")
        }
    }

    @Test
    fun `entity captured in readonly outer is mutated via the suspended-into plain inner and persists`() {
        transactional {
            User.new { login = "captured"; skill = 1 }
        }

        transactional(readonly = true) {
            // capture an entity in the readonly outer session
            val captured = User.all().first()

            // the plain inner block suspends into an independent RW transaction;
            // the captured entity is mutated from inside that inner context
            transactional {
                captured.skill = 42
            }
        }

        // the inner transaction committed independently: the write must be durable
        transactional {
            val user = User.all().first()
            assertThat(user.skill).isEqualTo(42)
        }
    }

    @Test
    fun `beginReadonlyTransaction returns the existing requested-readonly session`() {
        transactional(readonly = true) { txn ->
            assertThat(store.beginReadonlyTransaction()).isSameInstanceAs(txn)
        }
    }

    @Test
    fun `beginReadonlyTransaction fails fast on an existing writable session`() {
        transactional { txn ->
            assertFailsWith<IllegalStateException> {
                store.beginReadonlyTransaction()
            }
            // the writable session is untouched
            assertThat(store.threadSession).isSameInstanceAs(txn)
        }
    }
}
