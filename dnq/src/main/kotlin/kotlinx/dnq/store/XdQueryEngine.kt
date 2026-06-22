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
package kotlinx.dnq.store

import com.jetbrains.teamsys.dnq.database.EntityIterableWrapper
import com.jetbrains.teamsys.dnq.database.PersistentEntityIterableWrapper
import com.jetbrains.teamsys.dnq.database.reattach
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.asYTDBTransaction
import jetbrains.exodus.entitystore.youtrackdb.YTDBPersistentEntityStore
import jetbrains.exodus.entitystore.youtrackdb.resolveTypeName
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.query.InMemoryEntityIterable
import jetbrains.exodus.query.NodeBase
import jetbrains.exodus.query.QueryEngine


class XdQueryEngine(val store: TransientEntityStore) :
        QueryEngine(store.modelMetaData, store.persistentStore as YTDBPersistentEntityStore) {

    private val session get() = store.threadSessionOrThrow

    override fun queryGetAll(entityType: String, polymorphic: Boolean): EntityIterable {
        return wrap(super.queryGetAll(entityType, polymorphic))
    }

    override fun query(entityType: String, tree: NodeBase): EntityIterable {
        return wrap(super.query(entityType, tree))
    }

    override fun query(instance: Iterable<Entity>?, entityType: String, tree: NodeBase): EntityIterable {
        return wrap(super.query(instance, entityType, tree))
    }

    override fun intersect(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        val intersectResult = super.intersect(left, right)
        return wrap(intersectResult as EntityIterable)
    }

    override fun union(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        return wrap(super.union(left, right) as EntityIterable)
    }

    override fun concat(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        return wrap(super.concat(left, right) as EntityIterable)
    }

    override fun exclude(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        return wrap(super.exclude(left, right) as EntityIterable)
    }

    override fun selectDistinct(it: Iterable<Entity>?, linkName: String): Iterable<Entity> {
        return wrap(super.selectDistinct(it, linkName) as EntityIterable)
    }

    override fun selectManyDistinct(it: Iterable<Entity>?, linkName: String): Iterable<Entity> {
        return wrap(super.selectManyDistinct(it, linkName) as EntityIterable)
    }

    override fun toEntityIterable(it: Iterable<Entity>): Iterable<Entity> {
        return wrap(super.toEntityIterable(it) as EntityIterable)
    }

    override fun instantiateGetAll(entityType: String): EntityIterable {
        return wrap(super.instantiateGetAll(entityType))
    }

    override fun instantiateGetAll(txn: StoreTransaction, entityType: String): EntityIterable {
        return wrap(super.instantiateGetAll(txn, entityType))
    }

    fun wrap(it: Iterable<Entity>): EntityIterable {
        return if (it is EntityIterable){
            session.createPersistentEntityIterableWrapper(it)
        } else {
            session.createPersistentEntityIterableWrapper(InMemoryEntityIterable(it, session, this))
        }
    }

    override fun isWrapped(it: Iterable<Entity>?): Boolean {
        return it is EntityIterableWrapper
    }

    override fun isPersistentIterable(it: Iterable<Entity>): Boolean {
        return super.isPersistentIterable(it) || (isWrapped(it) && super.isPersistentIterable(((it as PersistentEntityIterableWrapper).unwrap())))
    }

    // ToDo: check if this is needed
    override fun wrap(entity: Entity): Iterable<Entity> {
        return (entity as? TransientEntity)
            ?.takeIf { it.isSaved }
            ?.reattach()
            ?.takeUnless { session.isRemoved(it) }
            ?.takeIf { it.isSaved }
            ?.let {
                YTDBEntityIterable.query(
                    session.transactionInternal.asYTDBTransaction().getStore(),
                    GremlinQuery.ByIds(
                        listOf(it.entity.id.asOId()),
                        it.entity.id.resolveTypeName(session.transactionInternal.asYTDBTransaction())
                    )
                )
            } ?: throw IllegalArgumentException()
    }
}
