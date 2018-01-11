/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package kotlinx.dnq.store

import com.jetbrains.teamsys.dnq.database.EntityIterableWrapper
import com.jetbrains.teamsys.dnq.database.reattach
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import jetbrains.exodus.entitystore.iterate.SingleEntityIterable
import jetbrains.exodus.query.QueryEngine


class XdQueryEngine(val store: TransientEntityStore) :
        QueryEngine(store.modelMetaData, store.persistentStore as PersistentEntityStoreImpl) {

    private val session get() = store.threadSessionOrThrow

    override fun isWrapped(it: Iterable<Entity>?): Boolean {
        return it is EntityIterableWrapper
    }

    override fun wrap(it: EntityIterable): EntityIterable {
        return session.createPersistentEntityIterableWrapper(it)
    }

    override fun wrap(entity: Entity): Iterable<Entity>? {
        return (entity as? TransientEntity)
                ?.takeIf { it.isSaved }
                ?.reattach()
                ?.takeUnless { session.isRemoved(it) }
                ?.takeIf { it.isSaved }
                ?.let { SingleEntityIterable(session.persistentTransaction as PersistentStoreTransaction, it.id) }
    }
}
