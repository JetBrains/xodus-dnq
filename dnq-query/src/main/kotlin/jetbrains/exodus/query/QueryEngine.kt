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
import jetbrains.exodus.entitystore.youtrackdb.resolveTypeName
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

    open fun queryGetAll(entityType: String, polymorphic: Boolean = true): EntityIterable {
        if (modelMetaData != null && modelMetaData.getEntityMetaData(entityType) == null) {
            return YTDBEntityIterable.EMPTY
        }
        // Build a lazy iterable WITHOUT requiring an active transaction at construction.
        // The iterable re-resolves the current active transaction at iteration time
        // (YTDBEntityIterableImpl.traversal -> oStore.requireActiveTransaction()), restoring
        // Xodus-like semantics: a query may be constructed outside a txn but iterated inside one.
        return YTDBEntityIterable.where(entityType, oStore, GremlinBlock.All, polymorphic)
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
                    val polymorphic = (instance.unwrap() as? YTDBEntityIterable)?.polymorphic ?: true
                    val rightIterable = tree.instantiate(
                        entityType,
                        this,
                        modelMetaData,
                        polymorphic
                    ) as EntityIterable
                    // JT-95690: delegate to the binary intersect, which already routes
                    // persistent-on-persistent to DB-side intersect and falls back to
                    // inMemoryIntersect when the left side is not DB-backed
                    // (e.g. TransientEntityIterable).
                    intersect(instance, rightIterable) as EntityIterable
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

        // XD-1278: restore the Xodus GetAll-identity short-circuit. Intersecting GetAll(type) with
        // another iterable must preserve that other iterable's iteration order. The general/DB
        // intersect path imposes entity-id order (e.g. it rewrites a relevance-ordered by-ids
        // operand into g.V().hasId(within(ids)).hasLabel(type), which loses the relevance order),
        // and inMemoryIntersect only preserves the LEFT operand's order. Applied symmetrically:
        // GetAll ∩ x → x and x ∩ GetAll → x, each filtered to live entities of the type.
        intersectWithGetAll(getAll = left, other = right)?.let { return it }
        intersectWithGetAll(getAll = right, other = left)?.let { return it }

        return if (canAggregate(left, right))
            (left as EntityIterable).intersect(right as EntityIterable)
        else inMemoryIntersect(left, right)
    }

    private data class GetAllInfo(val type: String, val polymorphic: Boolean)

    /**
     * If [getAll] is a `GetAll(type)` iterable (an unwrapped [YTDBEntityIterable] whose query is
     * `Labeled(Where(All), type)`), return [other] filtered to the live entities of that type,
     * preserving [other]'s iteration order. Returns `null` when [getAll] is not a GetAll, or when
     * the case is better handled by the regular intersect path (so the caller falls through).
     */
    private fun intersectWithGetAll(getAll: Iterable<Entity>, other: Iterable<Entity>): Iterable<Entity>? {
        val info = getAll.asGetAll() ?: return null

        val otherUnwrapped = (other as? EntityIterable)?.unwrap()
        if (otherUnwrapped is YTDBEntityIterable && otherUnwrapped !== YTDBEntityIterable.EMPTY) {
            val query = otherUnwrapped.query
            when {
                // `other` is already a DB query of exactly this type with the same polymorphism:
                // GetAll(type) ∩ other == other (other only yields live, type-compatible entities,
                // in its own order). Returned as the unwrapped iterable so the common
                // `all().query { cond }` path keeps its lazy DB iterable and is not materialized.
                // (We must return the unwrapped form: callers such as XdQueryEngine re-wrap the
                // result, and wrapping an already-wrapped transient iterable is rejected.)
                query is GremlinQuery.Labeled && query.label == info.type &&
                        otherUnwrapped.polymorphic == info.polymorphic -> return otherUnwrapped
                // `other` is a relevance/explicitly-ordered by-ids operand (e.g. full-text search
                // hits). It is bounded, so filter it in memory below, preserving its order.
                query is GremlinQuery.ByIds -> Unit
                // `other` is some other DB-backed query (e.g. a typed query of a different label):
                // let the regular DB intersect handle it rather than materialising a possibly-large
                // set. Order is not meaningful across unrelated types.
                else -> return null
            }
        }

        // Filter `other` to live entities of `type`, preserving order. Membership is resolved with a
        // single bounded query over `other`'s ids (V(ids).hasLabel(type)) instead of materialising
        // the whole GetAll set; this also reproduces the dead/stale-entity exclusion (stale ids are
        // simply absent from the membership set).
        val txn = persistentStore.andCheckCurrentTransaction
        val materialized = other.toList()
        if (materialized.isEmpty()) return YTDBEntityIterable.EMPTY

        val rids = materialized.map { (it.id as YTDBEntityId).asOId() }
        val liveOfType = YTDBEntityIterable
            .query(
                oStore,
                GremlinQuery.ByIds(rids).then(GremlinBlock.HasLabel(info.type)),
                info.polymorphic
            )
            .idSet()
        val result = materialized.filter { it.id in liveOfType }
        return InMemoryEntityIterable(result, txn = txn, this)
    }

    private fun Iterable<Entity>.asGetAll(): GetAllInfo? {
        val unwrapped = (this as? EntityIterable)?.unwrap()
        if (unwrapped !is YTDBEntityIterable || unwrapped === YTDBEntityIterable.EMPTY) return null
        val query = unwrapped.query as? GremlinQuery.Labeled ?: return null
        val inner = query.inner
        if (inner !is GremlinQuery.Where || inner.block !is GremlinBlock.All) return null
        return GetAllInfo(query.label, unwrapped.polymorphic)
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

    // JT-95690: was previously `if (left.isPersistent) right.isPersistent else left is EntityIterable && right is EntityIterable`.
    // The else-branch fell through to `(left as EntityIterable).intersect(right)` for any non-persistent
    // EntityIterable left side, including TransientEntityIterable whose intersect throws.
    // Mirrors xodus-master `intersectNonTrees`: only delegate to source.intersect when both sides
    // are DB-backed; otherwise route through inMemoryIntersect.
    private fun canAggregate(left: Iterable<Entity>, right: Iterable<Entity>): Boolean =
        left.isPersistent && right.isPersistent

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
            it?.let { inMemorySelectManyDistinct(it, linkName) } ?: YTDBEntityIterable.EMPTY
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
            oStore,
            GremlinQuery.ByIds(
                listOf((entity.id as YTDBEntityId).asOId()),
                (entity.id as YTDBEntityId).resolveTypeName(oStore)
            )
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
        // XD-1275: always intersect in memory — build an id set from the right side and
        // filter the left side by membership, which preserves the left-hand side order. The
        // previous implementation issued a GremlinQuery.ByIds DB query whenever one side had
        // fewer than 20 elements, which produced surprising behavior (results re-resolved
        // against the DB) and extra round-trips.
        val txn = persistentStore.andCheckCurrentTransaction
        val ids = getAsEntityIdSet(right)
        val result = if (ids.isEmpty) sequenceOf() else left.asSequence().filter { it.id in ids }

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
