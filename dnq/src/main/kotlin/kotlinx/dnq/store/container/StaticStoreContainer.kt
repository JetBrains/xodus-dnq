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
import jetbrains.exodus.database.TransientEntityStore
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

    fun init(dbFolder: File, environmentName: String, configure: EnvironmentConfig.() -> Unit = {}): TransientEntityStoreImpl {
        val store = createTransientEntityStore(dbFolder, environmentName, configure)
        this.store = store
        return store
    }
}