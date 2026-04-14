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
package jetbrains.exodus.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.iterate.EntityIdSet
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.kotlin.notNull
import jetbrains.exodus.query.metadata.ModelMetaData
import mu.KLogging

open class QueryEngine(val modelMetaData: ModelMetaData?, val persistentStore: PersistentEntityStore) : KLogging() {

    private var _sortEngine: SortEngine? = null

    val oStore: YTDBEntityStore = persistentStore as YTDBEntityStore

    open var sortEngine: SortEngine?
        get() = _sortEngine
        set(value) {
            _sortEngine = value.notNull.apply { queryEngine = this@QueryEngine }
        }

    open fun queryGetAll(entityType: String): EntityIterable = queryGetAll(entityType, polymorphic = true)

    // Note: XdQueryEngine overrides only the single-argument queryGetAll and wraps
    // the result. This two-argument version bypasses that wrapping — acceptable since
    // Track 4 will wire the DNQ layer through XdEntityType.all(polymorphic).
    open fun queryGetAll(entityType: String, polymorphic: Boolean): EntityIterable {
        if (modelMetaData != null && modelMetaData.getEntityMetaData(entityType) == null) {
            return YTDBEntityIterable.EMPTY
        }
        val txn = persistentStore.andCheckCurrentTransaction as YTDBStoreTransaction
        return txn.getAll(entityType, polymorphic)
    }

    open fun query(entityType: String, tree: NodeBase): EntityIterable = query(null, entityType, tree)

    open fun query(instance: Iterable<Entity>?, entityType: String, tree: NodeBase): EntityIterable {
        return when {
            modelMetaData != null && modelMetaData.getEntityMetaData(entityType) == null -> YTDBEntityIterable.EMPTY
            instance == null -> tree.instantiate(entityType, this, modelMetaData) as EntityIterable
            instance is EntityIterable -> {
                if (tree is LeafNode && tree.query is GremlinQuery.SortBy) {
                    val sorted = applySort(tree, entityType, instance)
                    sorted as? EntityIterable ?: InMemoryEntityIterable(sorted, txn = persistentStore.andCheckCurrentTransaction, this)
                } else {
                    instance.intersect(
                        tree.instantiate(
                            entityType,
                            this,
                            modelMetaData
                        ) as EntityIterable
                    )
                }
            }
            else -> {
                if (tree is LeafNode && tree.query is GremlinQuery.SortBy) {
                    val sorted = applySort(tree, entityType, instance)
                    InMemoryEntityIterable(sorted, txn = persistentStore.andCheckCurrentTransaction, this)
                } else {
                    intersect(
                        instance,
                        tree.instantiate(entityType, this, modelMetaData)
                    ) as EntityIterable
                }
            }
        }
    }

    private fun applySort(
        tree: LeafNode,
        entityType: String,
        instance: Iterable<Entity>
    ): Iterable<Entity> {
        val sb = tree.query as GremlinQuery.SortBy
        val sbBlock = sb.sortBlock
        val sorted = when (val by = sbBlock.by) {
            is GremlinBlock.Sort.ByProp ->
                sortEngine!!.sort(
                    entityType,
                    by.propName,
                    instance,
                    sbBlock.direction == GremlinBlock.SortDirection.ASC
                )

            is GremlinBlock.Sort.ByLinked ->
                // todo: first parameter is not used
                sortEngine!!.sort(
                    "",
                    by.propName,
                    entityType,
                    by.linkName,
                    instance,
                    sbBlock.direction == GremlinBlock.SortDirection.ASC
                )
        }
        return sorted
    }

    open fun intersect(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        if (left === right) return left
        if (left.isEmpty) return YTDBEntityIterable.EMPTY
        if (right.isEmpty) return YTDBEntityIterable.EMPTY

        return if (canAggregate(left, right))
            (left as EntityIterable).intersect(right as EntityIterable)
        else inMemoryIntersect(left, right)
    }

    open fun union(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        if (left === right) return left
        if (left.isEmpty) return right
        if (right.isEmpty) return left

        return if (canAggregate(left, right))
            (left as EntityIterable).union(right as EntityIterable)
        else inMemoryUnion(left, right)
    }

    open fun concat(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        if (left.isEmpty) return right
        if (right.isEmpty) return left

        return if (canAggregate(left, right))
            (left as EntityIterable).concat(right as EntityIterable)
        else inMemoryConcat(left, right)
    }

    open fun exclude(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        if (left.isEmpty) return YTDBEntityIterable.EMPTY
        if (right.isEmpty) return left
        if (left === right) return YTDBEntityIterable.EMPTY

        return if (canAggregate(left, right)) {
            (left as EntityIterable).minus(right as EntityIterable)
        } else {
            inMemoryExclude(left, right)
        }
    }

    private fun canAggregate(left: Iterable<Entity>, right: Iterable<Entity>): Boolean =
        if (left.isPersistent) right.isPersistent
        else left is EntityIterable && right is EntityIterable

    open fun selectDistinct(it: Iterable<Entity>?, linkName: String): Iterable<Entity> {
        return if (it is EntityIterable) {
            it.selectDistinct(linkName)
        } else {
            it?.let { inMemorySelectDistinct(it, linkName) } ?: YTDBEntityIterable.EMPTY
        }

    }

    open fun selectManyDistinct(it: Iterable<Entity>?, linkName: String): Iterable<Entity> {
        return if (it is EntityIterable) {
            it.selectManyDistinct(linkName)
        } else {
            return it?.let { inMemorySelectManyDistinct(it, linkName) } ?: YTDBEntityIterable.EMPTY
        }
    }

    open fun toEntityIterable(it: Iterable<Entity>): Iterable<Entity> {
        return it
    }

    open fun instantiateGetAll(entityType: String): EntityIterable {
        return instantiateGetAll(persistentStore.andCheckCurrentTransaction, entityType)
    }

    open fun instantiateGetAll(txn: StoreTransaction, entityType: String): EntityIterable {
        return txn.getAll(entityType)
    }

    open fun isPersistentIterable(it: Iterable<Entity>): Boolean = it.isPersistent

    open fun assertOperational() {}

    open fun isWrapped(it: Iterable<Entity>?): Boolean = true

    open fun wrap(entity: Entity): Iterable<Entity> {
        return YTDBEntityIterable.query(
            persistentStore.currentTransaction as YTDBStoreTransaction,
            GremlinQuery.ByIds(listOf((entity.id as YTDBEntityId).asOId()))
        )
        // xodus original code
        // return SingleEntityIterable(persistentStore.andCheckCurrentTransaction, entity.id)
    }

    internal open fun inMemorySelectDistinct(it: Iterable<Entity>, linkName: String): Iterable<Entity> {
        val result = it.asSequence().mapNotNull { it.getLink(linkName) }.distinct()
        return InMemoryEntityIterable(result.asIterable(), txn = persistentStore.andCheckCurrentTransaction, this)
    }

    internal open fun inMemorySelectManyDistinct(it: Iterable<Entity>, linkName: String): Iterable<Entity> {
        val result = it.asSequence().flatMap { it.getLinks(linkName) }.filterNotNull().distinct()
        return InMemoryEntityIterable(result.asIterable(), txn = persistentStore.andCheckCurrentTransaction, this)
    }

    /*
    Warning all data is in memory
     */
    internal open fun inMemoryIntersect(left: Iterable<Entity>, right: Iterable<Entity>): Iterable<Entity> {
        val ids: EntityIdSet
        val sequence: Sequence<Entity>

        val txn = persistentStore.andCheckCurrentTransaction
        if (left is YTDBEntityIterable) {
            //May be rewrite it. Constant from nowhere
            val rightIds = right.asSequence().map { e -> (e.id as YTDBEntityId).asOId() }.take(20).toList()
            if (rightIds.size < 20) {
                return YTDBEntityIterable.query(
                    txn as YTDBStoreTransaction,
                    left.query.intersect(
                        GremlinQuery.ByIds(rightIds)
                    ),
                    left.polymorphic
                )
            } else {
                ids = getAsEntityIdSet(left)
                sequence = right.asSequence()
            }
        } else if (right is YTDBEntityIterable) {
            val leftValues = left.asSequence().map { e -> (e.id as YTDBEntityId).asOId() }.take(20).toList()
            if (leftValues.size < 20) {
                return YTDBEntityIterable.query(
                    txn as YTDBStoreTransaction,
                    GremlinQuery.ByIds(leftValues).intersect(right.query),
                    right.polymorphic
                )
            } else {
                ids = getAsEntityIdSet(left)
                sequence = right.asSequence()
            }
        } else {
            // may be there will be some better optimization here
            ids = getAsEntityIdSet(left)
            sequence = right.asSequence()
        }
        val result = if (ids.isEmpty) sequenceOf() else sequence.filter { it.id in ids }

        return InMemoryEntityIterable(result.asIterable(), txn = txn, this)
    }

    internal open fun inMemoryUnion(left: Iterable<Entity>, right: Iterable<Entity>): EntityIterable {
        val result = left.union(right)
        return InMemoryEntityIterable(result.asIterable(), txn = persistentStore.andCheckCurrentTransaction, this)
    }

    open fun inMemoryConcat(left: Iterable<Entity>, right: Iterable<Entity>): EntityIterable {
        val result = left.toMutableList().apply { addAll(right) }
        return InMemoryEntityIterable(result.asIterable(), txn = persistentStore.andCheckCurrentTransaction, this)
    }

    internal open fun inMemoryExclude(left: Iterable<Entity>, right: Iterable<Entity>): EntityIterable {
        val ids = getAsEntityIdSet(right)
        val result = if (ids.isEmpty) left.asSequence() else left.asSequence().filter { it.id !in ids }
        return InMemoryEntityIterable(result.asIterable(), txn = persistentStore.andCheckCurrentTransaction, this)
    }

    private fun getAsEntityIdSet(entities: Iterable<Entity>): EntityIdSet {
        var ids = EntityIdSetFactory.newSet()
        entities.forEach {
            ids = ids.add(it.id)
        }
        return ids
    }
}

private val Iterable<Entity>?.isEmpty: Boolean
    get() =
        this == null || this === YTDBEntityIterable.EMPTY

private val Iterable<Entity>?.isPersistent: Boolean
    get() =
        this is EntityIterable && this.unwrap() is YTDBEntityIterable
