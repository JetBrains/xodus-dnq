/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import jetbrains.exodus.entitystore.PersistentEntityStoreConfig
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.exodus.env.EnvironmentConfig
import jetbrains.exodus.env.Environments
import jetbrains.exodus.query.metadata.ModelMetaDataImpl
import kotlinx.dnq.store.DummyTransientChangesMultiplexer
import kotlinx.dnq.store.XdQueryEngine
import java.io.File

fun createTransientEntityStore(dbFolder: File,
                               entityStoreName: String,
                               configure: EnvironmentConfig.() -> Unit = {}): TransientEntityStoreImpl {
    return createTransientEntityStore(dbFolder, entityStoreName, true, configure)
}

fun createTransientEntityStore(dbFolder: File,
                               entityStoreName: String,
                               primary: Boolean,
                               configure: EnvironmentConfig.() -> Unit = {}): TransientEntityStoreImpl {
    return TransientEntityStoreImpl().apply {
        val store = this
        val environment = Environments.newInstance(dbFolder, EnvironmentConfig().apply(configure).apply { isManagementEnabled = primary })
        val persistentStore = PersistentEntityStoreImpl(
                PersistentEntityStoreConfig().apply { isManagementEnabled = primary },
                environment,
                null,
                entityStoreName)
        this.persistentStore = persistentStore
        this.modelMetaData = ModelMetaDataImpl()
        this.changesMultiplexer = DummyTransientChangesMultiplexer
        this.queryEngine = XdQueryEngine(store).apply {
            this.sortEngine = TransientSortEngineImpl(store, this)
        }
    }
}
