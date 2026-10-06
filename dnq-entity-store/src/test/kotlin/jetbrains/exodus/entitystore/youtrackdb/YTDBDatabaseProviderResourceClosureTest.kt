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

import YTDBDatabaseProviderFactory
import YouTrackDBFactory
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.SessionListener
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl
import com.jetbrains.youtrackdb.internal.core.tx.Transaction
import jetbrains.exodus.entitystore.youtrackdb.testutil.QueryResourceRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.IdentityHashMap
import kotlin.io.path.absolutePathString

class YTDBDatabaseProviderResourceClosureTest {
    @Test
    fun `additional user initialization closes its query before transaction end`() {
        QueryResourceRecorder().use { recorder ->
            val before = IdentityHashMap<DatabaseSessionEmbedded, Int>()
            val observations = mutableListOf<Pair<Int, Int>>()
            val instrumentationFailures = mutableListOf<Throwable>()
            val listener = object : SessionListener {
                override fun onBeforeTxBegin(transaction: Transaction) {
                    try {
                        val session = transaction.databaseSession
                        recorder.attach(session)
                        before[session] = session.activeQueries.size
                    } catch (failure: Throwable) {
                        // YTDB logs and swallows listener failures. Assert them outside the callback.
                        instrumentationFailures.add(failure)
                    }
                }

                override fun onBeforeTxCommit(transaction: Transaction) {
                    try {
                        val session = transaction.databaseSession
                        observations.add(before.getValue(session) to session.activeQueries.size)
                    } catch (failure: Throwable) {
                        instrumentationFailures.add(failure)
                    }
                }
            }
            val params = YTDBDatabaseParams.builder()
                .withDatabaseType(DatabaseType.MEMORY)
                .withDatabasePath(Files.createTempDirectory("YTDB_resource_closure").absolutePathString())
                .withDatabaseName("testDB")
                .withAppUser("admin", "password")
                .withAdditionalUsers(listOf(YTDBUser("reader", "reader_password", "reader")))
                .withConfigBuilder { addSessionListener(listener) }
                .build()

            // Own the database even if provider initialization fails before returning a provider.
            YouTrackDBFactory.createEmbedded(params).use { database ->
                val provider = YTDBDatabaseProviderFactory.createProvider(params, database)
                instrumentationFailures.firstOrNull()?.let { throw AssertionError("Query instrumentation failed", it) }
                assertTrue("The initialization query must be captured", recorder.resources.isNotEmpty())
                assertTrue("Check before transaction cleanup, not after it", observations.isNotEmpty())
                observations.forEach { (initial, final) ->
                    assertEquals("Initialization must close results before committing", initial, final)
                }
                provider.withSession { session ->
                    session.transaction { tx ->
                        val names = tx.query("SELECT name FROM " + SecurityUserImpl.CLASS_NAME).use { rs ->
                            rs.stream().map { it.getString("name") }.toList().toSet()
                        }
                        assertTrue(names.containsAll(setOf("admin", "reader")))
                    }
                }
                instrumentationFailures.firstOrNull()?.let { throw AssertionError("Query instrumentation failed", it) }
            }
        }
    }
}
