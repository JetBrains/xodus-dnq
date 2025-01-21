/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import com.jetbrains.teamsys.dnq.database.TransientSortEngineImpl
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.YTDBPersistentEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddyImpl
import jetbrains.exodus.query.metadata.YTDBModelMetaData
import kotlinx.dnq.store.XdQueryEngine

fun createTransientEntityStore(
    databaseProvider: YTDBDatabaseProvider,
    databaseName:String
): TransientEntityStoreImpl {
    val schemaBuddy = YTDBSchemaBuddyImpl(databaseProvider, false)
    return TransientEntityStoreImpl().apply {
        val store = this
        val oStore = YTDBPersistentEntityStore(
            databaseProvider,
            databaseName,
            schemaBuddy = schemaBuddy
        )
        this.persistentStore = oStore
        this.modelMetaData = YTDBModelMetaData(databaseProvider, schemaBuddy)
        this.queryEngine = XdQueryEngine(store).apply {
            this.sortEngine = TransientSortEngineImpl(store, this)
        }
    }
}
