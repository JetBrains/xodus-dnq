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
package kotlinx.dnq.store.container

import YouTrackDBFactory
import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.api.YouTrackDB
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfigBuilder
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.youtrackdb.*
import java.io.File

object StaticStoreContainer : StoreContainer {
    private var _store: TransientEntityStore? = null
    var dbProvider: YTDBDatabaseProvider? = null
    var db: YouTrackDB? = null


    override var store: TransientEntityStore
        get() {
            return _store ?: throw IllegalStateException("Transient store is not initialized")
        }
        set(value) {
            this._store = value
        }

    fun init(
        dbFolder: File,
        entityStoreName: String,
        databaseType: DatabaseType = DatabaseType.MEMORY,
        configure: YouTrackDBConfigBuilder.() -> Unit = {}
    ): TransientEntityStoreImpl {
        val params = YTDBDatabaseParams.builder()
            .withAppUser("admin", "admin")
            .withDatabaseType(databaseType)
            .withDatabasePath(dbFolder.absolutePath)
            .withDatabaseName("memory")
            .withConfigBuilder(configure)
            .build()
        //TODO use dbFolder
        db = YouTrackDBFactory.createEmbedded(params)

        dbProvider = YTDBDatabaseProviderImpl(params, db!!, null)
        val store = createTransientEntityStore(dbProvider!!, entityStoreName)
        this.store = store
        return store
    }

}
