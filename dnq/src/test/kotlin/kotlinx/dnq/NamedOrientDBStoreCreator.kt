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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import com.orientechnologies.orient.core.db.ODatabaseType
import com.orientechnologies.orient.core.db.OrientDB
import com.orientechnologies.orient.core.db.OrientDBConfig
import com.orientechnologies.orient.core.db.OrientDBConfigBuilder
import jetbrains.exodus.entitystore.orientdb.ODatabaseProviderImpl
import kotlinx.dnq.store.container.createTransientEntityStore
import kotlinx.dnq.util.initMetaData

fun createStore(name: String): TransientEntityStoreImpl {

    val db = OrientDB("memory", OrientDBConfig.defaultConfig())
    val config = OrientDBConfigBuilder()
        .addGlobalUser("admin","password","admin")
        .build()
    val databaseType = ODatabaseType.MEMORY
    db.createIfNotExists(name, databaseType,
        config)
    val dbProvider = ODatabaseProviderImpl(
        db,
        name,
        "admin",
        "password",
        databaseType,
    )

    val store = createTransientEntityStore(dbProvider, name, hashMapOf())
    initMetaData(XdModel.hierarchy, store)
    return store
}
