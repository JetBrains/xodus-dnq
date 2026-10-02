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
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.SessionListener
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl
import com.jetbrains.youtrackdb.internal.core.query.ResultSet
import com.jetbrains.youtrackdb.internal.core.tx.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.IdentityHashMap
import kotlin.io.path.absolutePathString

class YTDBDatabaseProviderResourceClosureTest {
    @Test
    fun `additional user initialization closes its query before transaction end`() {
        val before = IdentityHashMap<DatabaseSessionEmbedded, Int>()
        val observations = mutableListOf<Pair<Int, Int>>()
        // Embedded activeQueries uses weak references; retain results so GC cannot hide a leak.
        val opened = mutableListOf<ResultSet>()
        val listener = object : SessionListener {
            override fun onBeforeTxBegin(transaction: Transaction) {
                val session = transaction.databaseSession
                before[session] = session.activeQueries.size
            }

            override fun onCommandStart(database: DatabaseSessionEmbedded, resultSet: ResultSet) {
                opened.add(resultSet)
            }

            override fun onBeforeTxCommit(transaction: Transaction) {
                val session = transaction.databaseSession
                observations.add(before.getValue(session) to session.activeQueries.size)
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

        val provider = YTDBDatabaseProviderFactory.createProvider(params)
        try {
            assertTrue("The initialization query must be observed", opened.isNotEmpty())
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
        } finally {
            provider.close()
        }
    }
}
