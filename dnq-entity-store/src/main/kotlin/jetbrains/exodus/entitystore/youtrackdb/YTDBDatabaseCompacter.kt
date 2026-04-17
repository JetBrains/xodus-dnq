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

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport
import mu.KLogging
import java.io.File

class YTDBDatabaseCompacter(
    private val database: YouTrackDBImpl,
    private val params: YTDBDatabaseParams
) {
    companion object : KLogging()

    private fun <R> withSession(block: (DatabaseSessionEmbedded) -> R): R =
        database.cachedPool(
            params.databaseName,
            params.appUser.name,
            params.appUser.password,
            params.youTrackDBConfig
        ).acquire().use(block)

    fun compactDatabase() {
        val databaseLocation = File(params.databasePath, params.databaseName)
        val backupFile = File(databaseLocation, "temp${System.currentTimeMillis()}")
        backupFile.parentFile.mkdirs()
        val listener = CommandOutputListener { iText -> logger.info("Compacting database: $iText") }

        withSession { session ->
            val exporter = DatabaseExport(
                session,
                backupFile.outputStream(),
                listener
            )
            logger.info("Dumping database...")
            exporter.exportDatabase()
        }

        logger.info("Dropping existing database...")
        database.drop(params.databaseName)

        database.create(
            params.databaseName,
            params.databaseType,
            params.appUser.name,
            params.appUser.password,
            "admin"
        )

        withSession { session ->
            logger.info("Importing database from dump")
            val importer = DatabaseImport(
                session,
                backupFile.inputStream(),
                listener
            )
            importer.importDatabase()
        }
        backupFile.delete()
    }
}
