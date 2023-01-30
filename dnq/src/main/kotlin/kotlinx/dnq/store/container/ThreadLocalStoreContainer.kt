/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.QueryCancellingPolicy

object ThreadLocalStoreContainer : StoreContainer {
    private val storeThreadLocal = ThreadLocal<TransientEntityStore>()

    override val store: TransientEntityStore
        get() = storeThreadLocal.get() ?: throw IllegalStateException("Current store is undefined")

    fun <T> withStore(store: TransientEntityStore, body: () -> T): T {
        val oldStore = storeThreadLocal.get()
        storeThreadLocal.set(store)
        try {
            return body()
        } finally {
            if (oldStore != null) {
                storeThreadLocal.set(oldStore)
            } else {
                storeThreadLocal.remove()
            }
        }
    }

    fun <T> transactional(
            store: TransientEntityStore,
            readonly: Boolean = false,
            queryCancellingPolicy: QueryCancellingPolicy? = null,
            isNew: Boolean = false, block: (TransientStoreSession) -> T
    ): T = withStore(store) {
        store.transactional(readonly, queryCancellingPolicy, isNew, block)
    }
}
