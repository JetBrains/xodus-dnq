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
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import kotlinx.dnq.query.first
import kotlinx.dnq.query.toList
import org.junit.Test

class NewTransactionTest : DBTest() {

    @Test(expected = EntityRemovedInDatabaseException::class)
    fun `inner revert without isNew destroys outer transaction`() {
        transactional { outerTxn ->
            val user = User.new {
                login = "outer-user"
                skill = 1
            }

            // Without isNew, inner block reuses the outer session —
            // revert wipes out the outer transaction's entity.
            transactional { innerTxn ->
                (innerTxn.transactionInternal as YTDBStoreTransaction).revert()
            }

            // This fails because the entity was reverted along with the outer session.
            user.login
        }
    }

    @Test
    fun `inner isNew transaction revert does not affect outer transaction`() {
        transactional { outerTxn ->
            val user = User.new {
                login = "outer-user"
                skill = 1
            }

            store.transactional(isNew = true) { innerTxn ->
                (innerTxn.transactionInternal as YTDBStoreTransaction).revert()
            }

            // The outer entity must survive the inner revert.
            assertThat(user.login).isEqualTo("outer-user")
        }
    }

    @Test
    fun `inner isNew transaction abort does not affect outer transaction`() {
        transactional { outerTxn ->
            val user = User.new {
                login = "outer-user"
                skill = 1
            }

            store.transactional(isNew = true) { innerTxn ->
                User.new {
                    login = "inner-user"
                    skill = 2
                }
                innerTxn.abort()
            }

            assertThat(user.login).isEqualTo("outer-user")
        }
    }

    @Test
    fun `inner isNew transaction commits independently of outer abort`() {
        transactional { outerTxn ->
            User.new {
                login = "outer-user"
                skill = 1
            }

            store.transactional(isNew = true) { innerTxn ->
                User.new {
                    login = "inner-user"
                    skill = 2
                }
                // inner commits on block exit
            }

            // abort the outer transaction — inner commit must survive
            outerTxn.abort()
        }

        transactional {
            val logins = User.all().toList().map { it.login }
            assertThat(logins).containsExactly("inner-user")
        }
    }

    @Test
    fun `double-nested isNew — second level abort does not affect first or third`() {
        transactional { level1 ->
            User.new { login = "level1"; skill = 1 }

            store.transactional(isNew = true) { level2 ->
                User.new { login = "level2"; skill = 2 }

                store.transactional(isNew = true) { level3 ->
                    User.new { login = "level3"; skill = 3 }
                    // level3 commits
                }

                // abort level2 — level1 and level3 must be unaffected
                level2.abort()
            }

            assertThat(User.new { login = "level1-after"; skill = 4 }.login).isEqualTo("level1-after")
        }

        transactional {
            val logins = User.all().toList().map { it.login }.sorted()
            assertThat(logins).containsExactly("level1", "level1-after", "level3")
        }
    }

    @Test
    fun `double-nested isNew — third level commits, all levels independent`() {
        transactional { level1 ->
            User.new { login = "level1"; skill = 1 }

            store.transactional(isNew = true) { level2 ->
                User.new { login = "level2"; skill = 2 }

                store.transactional(isNew = true) { level3 ->
                    User.new { login = "level3"; skill = 3 }
                    // level3 commits normally
                }

                // level2 commits normally
            }

            User.new { login = "level1-after"; skill = 4 }
        }

        transactional {
            val logins = User.all().toList().map { it.login }.sorted()
            assertThat(logins).containsExactly("level1", "level1-after", "level2", "level3")
        }
    }

    @Test
    fun `double-nested isNew — third level abort does not affect first or second`() {
        transactional { level1 ->
            User.new { login = "level1"; skill = 1 }

            store.transactional(isNew = true) { level2 ->
                User.new { login = "level2"; skill = 2 }

                store.transactional(isNew = true) { level3 ->
                    User.new { login = "level3"; skill = 3 }
                    // abort level3 — level1 and level2 must be unaffected
                    level3.abort()
                }

                assertThat(User.new { login = "level2-after"; skill = 4 }.login).isEqualTo("level2-after")
                // level2 commits
            }

            assertThat(User.new { login = "level1-after"; skill = 5 }.login).isEqualTo("level1-after")
        }

        transactional {
            val logins = User.all().toList().map { it.login }.sorted()
            assertThat(logins).containsExactly("level1", "level1-after", "level2", "level2-after")
        }
    }

    @Test
    fun `isNew without outer transaction works normally`() {
        store.transactional(isNew = true) { txn ->
            User.new {
                login = "standalone"
                skill = 1
            }
        }

        transactional {
            val user = User.all().first()
            assertThat(user.login).isEqualTo("standalone")
        }
    }
}
