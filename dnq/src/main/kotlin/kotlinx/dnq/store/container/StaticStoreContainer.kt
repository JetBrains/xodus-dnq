/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
import com.orientechnologies.orient.core.db.ODatabaseType
import com.orientechnologies.orient.core.db.OrientDB
import com.orientechnologies.orient.core.db.OrientDBConfig
import com.orientechnologies.orient.core.db.OrientDBConfigBuilder
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.orientdb.ODatabaseProviderImpl
import jetbrains.exodus.env.EnvironmentConfig
import java.io.File

object StaticStoreContainer : StoreContainer {
    private var _store: TransientEntityStore? = null

    override var store: TransientEntityStore
        get() {
            return _store ?: throw IllegalStateException("Transient store is not initialized")
        }
        set(value) {
            this._store = value
        }

    fun init(dbFolder: File, entityStoreName: String, primary: Boolean = true, configure: EnvironmentConfig.() -> Unit = {}): TransientEntityStoreImpl {
        //TODO use dbFolder
        val db = OrientDB("memory", OrientDBConfig.defaultConfig())
        val databaseType = ODatabaseType.MEMORY
        db.createIfNotExists(entityStoreName, databaseType,"admin", "password", "admin")
        val dbProvider = ODatabaseProviderImpl(
            db,
            entityStoreName,
            "admin",
            "password",
            databaseType,
        )
        val store = createTransientEntityStore(dbProvider, entityStoreName)
        this.store = store
        return store
    }
}
