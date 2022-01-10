/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.XdQueryImpl
import kotlinx.dnq.store.container.StoreContainer

abstract class XdEntityType<out T : XdEntity>(val storeContainer: StoreContainer) {
    abstract val entityType: String
    val entityStore: TransientEntityStore
        get() = storeContainer.store

    fun all(): XdQuery<T> {
        return XdQueryImpl(entityStore.queryEngine.queryGetAll(entityType), this)
    }

    open fun new(init: (T.() -> Unit) = {}): T {
        val transaction = (entityStore.threadSession
                ?: throw IllegalStateException("New entities can be created only in transactional block"))
        return wrap(transaction.newEntity(entityType)).apply {
            constructor()
            init()
        }
    }

    open fun wrap(entity: Entity) = entity.toXd<T>()
}
