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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.SessionPool
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl
import mu.KLogging
import java.io.File
import kotlin.streams.asSequence

//username and password are considered to be same for all databases
//todo this params also should be collected in some config entity
class YTDBDatabaseProviderImpl(
    private val params: YTDBDatabaseParams,
    private val database: YouTrackDBImpl,
//    private val server: YouTrackDBServer?,
) : YTDBDatabaseProvider {

    override var isOpen: Boolean = false
        private set

    companion object : KLogging()

    override val graph: YTDBGraph

    init {
        val userNames = listOf(params.appUser.name) + params.additionalUsers.map { it.name }

        require(userNames.toSet().size == userNames.size) { "User names must be unique" }

        database.createIfNotExists(
            params.databaseName,
            params.databaseType,
            params.appUser.name,
            params.appUser.password,
            "admin"
        )

        val sessionPool = database.cachedPool(
            params.databaseName,
            params.appUser.name,
            params.appUser.password,
            params.youTrackDBConfig
        )
        graph = sessionPool.asGraph()

        if (params.additionalUsers.any()) {
            withSession { session ->
                session.transaction { tx ->
                    val existingNames = tx.query("SELECT name FROM " + SecurityUserImpl.CLASS_NAME)
                        .stream()
                        .map { it.getString("name") }
                        .asSequence()
                        .toSet()

                    params.additionalUsers
                        .asSequence()
                        .filterNot { existingNames.contains(it.name) }
                        .forEach { userDef ->
                            logger.info { "Creating user ${userDef.name} with role ${userDef.role}" }

                            tx.command(
                                "CREATE USER ${userDef.name} IDENTIFIED BY :password ROLE ${userDef.role}",
                                mapOf("password" to userDef.password)
                            )
                        }
                }
            }
        }

        isOpen = true
    }


    override val databaseLocation: String
        get() = File(params.databasePath, params.databaseName).absolutePath

    override fun <R> withSession(block: (DatabaseSessionEmbedded) -> R): R =
        (graph as YTDBGraphEmbedded).acquireSession().use(block)

    // it is always false at the beginning (it is impossible to close the database in the frozen state)
    private var _readOnly: Boolean = false

    override var readOnly: Boolean
        get() = _readOnly
        set(value) {
            if (_readOnly == value) return

            withSession { session ->
                if (value) {
                    // if one tries to write and commit changes, they will get an exception
                    session.freeze(true)
                } else {
                    session.release()
                }
            }
            _readOnly = value
        }

    override fun close() {
        isOpen = false
        // YouTrackDB cannot close the database if it is read-only (frozen)
        readOnly = false
        if (params.closeDatabaseInDbProvider) {
//            if (server != null) {
//                server.shutdown()
//            } else {
                database.close()
//            }
        }
    }
}
