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
package jetbrains.exodus.entitystore.youtrackdb.iterate

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.asYTDBIterable
import jetbrains.exodus.entitystore.iterate.EntityIdSet
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import jetbrains.exodus.entitystore.util.unsupported
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.resolveTypeName
import jetbrains.exodus.entitystore.youtrackdb.gremlin.FullScanDetection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryShape
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal

interface YTDBEntityIterable : EntityIterable {
    companion object {
        // All factories build a lazy iterable from the STORE — no active transaction is required at
        // construction. The active txn is resolved at iteration time (see YTDBEntityIterableImpl.traversal),
        // so a query may be constructed outside a txn but must be iterated inside one.
        @JvmStatic
        @JvmOverloads
        fun where(
            entityType: String,
            store: YTDBEntityStore,
            condition: GremlinBlock,
            polymorphic: Boolean = true
        ): YTDBEntityIterable =
            query(
                store,
                GremlinQuery.all
                    .then(condition)
                    .then(GremlinBlock.HasLabel(entityType)),
                polymorphic
            )

        @JvmStatic
        @JvmOverloads
        fun query(store: YTDBEntityStore, query: GremlinQuery, polymorphic: Boolean = true) =
            YTDBEntityIterableImpl(store, query, polymorphic)

        @JvmStatic
        fun empty() = EMPTY

        @JvmStatic
        fun single(store: YTDBEntityStore, entityId: EntityId): YTDBEntityIterable {
            val ytdbId = entityId as YTDBEntityId
            // Direct by-id access: `g.V(rid)` (id on the GraphStep → getElementsByIds, O(1) positional
            // load) instead of `g.V().hasId(rid)`, which the planner runs as a class scan. The resolved
            // type is kept only as a residual label filter.
            return query(store, GremlinQuery.ByIds(listOf(ytdbId.asOId()), ytdbId.resolveTypeName(store)))
        }

        val EMPTY = object : YTDBEntityIterable {

            override fun iterator(): EntityIterator = YTDBEntityIterator.EMPTY
            override fun selectMany(linkName: String): EntityIterable = this
            override val query: GremlinQuery get() = unsupported { "Should never be called" }
            override fun traversal(): GraphTraversal<*, YTDBVertex> = unsupported { "Should never be called" }
            override fun unwrap(): EntityIterable = this
            override fun getTransaction(): StoreTransaction = unsupported { "Should never be called" }
            override fun isEmpty(): Boolean = true
            override fun indexOf(entity: Entity): Int = -1
            override fun contains(entity: Entity): Boolean = false
            override fun isSortResult(): Boolean = true
            override fun asSortResult(): EntityIterable = this
            override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable = this
            override fun size() = 0L
            override fun getRoughSize() = 0L
            override fun count() = 0L
            override fun getRoughCount() = 0L
            override fun union(right: EntityIterable) = right
            override fun concat(right: EntityIterable) = right
            override fun skip(number: Int): EntityIterable = this
            override fun take(number: Int): EntityIterable = this
            override fun intersect(right: EntityIterable) = this
            override fun intersectSavingOrder(right: EntityIterable): EntityIterable = this
            override fun distinct() = this
            override fun minus(right: EntityIterable) = this
            override fun selectManyDistinct(linkName: String) = this
            override fun getFirst(): Entity? = null
            override fun getLast(): Entity? = null
            override fun reverse(): EntityIterable = this
            override fun selectDistinct(linkName: String) = this
            override fun idSet(): EntityIdSet = EntityIdSetFactory.newSet()
        }
    }

    fun selectMany(linkName: String): EntityIterable

    val query: GremlinQuery

    val polymorphic: Boolean get() = true

    fun traversal(): GraphTraversal<*, YTDBVertex>

    fun idSet(): EntityIdSet = this.fold(EntityIdSetFactory.newSet()) { acc, e -> acc.add(e.id) }
}

class YTDBEntityIterableImpl(
    private val oStore: YTDBEntityStore,
    override val query: GremlinQuery,
    override val polymorphic: Boolean = true
) : YTDBEntityIterable {

    @Volatile
    private var cachedSize: Long = -1

    // XD-1281: DNQ-level, value-anonymized description ferried into the query via
    // YTDBQueryConfigParam.querySummary so the full-scan listener can log a human-readable
    // summary. Computed lazily because traversal() runs on every terminal op.
    private val querySummary: String by lazy { GremlinQueryShape.of(query) }

    private fun modify(block: GremlinBlock): YTDBEntityIterableImpl =
        YTDBEntityIterableImpl(oStore,this.query.then(block), polymorphic)

    private fun iterator(traversal: GraphTraversal<*, YTDBVertex>): YTDBEntityIterator =
        YTDBEntityIterator.of(traversal, oStore)

    override fun traversal(): GraphTraversal<*, YTDBVertex> {
        // `with(...)` clones the whole traversal source (bytecode + strategies), so skip it for the
        // common polymorphic=true case: when no `polymorphicQuery` option is set, YTDB falls back to
        // GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, which is true (and YTDBDatabaseParams
        // sets it true explicitly). Only the non-polymorphic case needs the explicit override.
        val base = oStore.requireActiveTransaction().g()
        var gs = if (polymorphic) base else base.with(YTDBQueryConfigParam.polymorphicQuery, false)
        if (FullScanDetection.enabled) {
            gs = gs.with(YTDBQueryConfigParam.querySummary, querySummary)
        }
        return query.start(gs)
    }

    override fun iterator(): YTDBEntityIterator = iterator(traversal())

    override fun getTransaction(): StoreTransaction = oStore.requireActiveTransaction()

    /** Same query with the result order dropped — see [GremlinQuery.withoutResultOrder]. */
    private fun unordered(): YTDBEntityIterableImpl {
        val unordered = query.withoutResultOrder()
        return if (unordered === query) this else YTDBEntityIterableImpl(oStore, unordered, polymorphic)
    }

    override fun isEmpty(): Boolean {
        val iter = unordered().iterator()
        try {
            return !iter.hasNext()
        } finally {
            iter.dispose()
        }
    }

    override fun size(): Long {
        cachedSize = unordered().traversal().count().use { it.next() }
        return cachedSize
    }

    override fun count(): Long = size()

    override fun getRoughCount(): Long = roughSize

    override fun getRoughSize(): Long {
        val size = cachedSize
        return if (size != -1L) size else size()
    }

    override fun indexOf(entity: Entity): Int {
        val entityId = entity.id
        var result = 0
        val it = iterator()
        try {
            while (it.hasNext()) {
                if (it.nextId() == entityId) {
                    return result
                }
                ++result
            }
        } finally {
            it.dispose()
        }
        return -1
    }

    override fun contains(entity: Entity): Boolean {
        val oid = (entity.id as YTDBEntityId).asOId()
        // Prefer a direct by-id membership check: g.V(oid) (positional load) + this iterable's own
        // filter, instead of g.V().hasLabel(T).hasId(oid), which the planner runs as a class-extent
        // scan (SELECT FROM T WHERE @rid). Only filter-shaped queries can be soundly restricted;
        // links/aggregates/pagination fall back to scanning the full traversal and filtering by id.
        query.restrictToId(oid)?.let { membership ->
            return !YTDBEntityIterableImpl(oStore, membership, polymorphic).isEmpty()
        }
        return unordered().traversal()
            .hasId(oid)
            .use { it.hasNext() }
    }

    override fun intersect(right: EntityIterable): EntityIterable {
        val rightIterable = right.operand()
        if (rightIterable === YTDBEntityIterable.EMPTY) return YTDBEntityIterable.EMPTY
        requirePolymorphicMatch(rightIterable)
        return YTDBEntityIterableImpl(oStore, query.intersect(rightIterable.query), polymorphic)
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable = intersect(right)

    override fun union(right: EntityIterable): EntityIterable {
        val rightIterable = right.operand()
        if (rightIterable === YTDBEntityIterable.EMPTY) return this
        requirePolymorphicMatch(rightIterable)
        return YTDBEntityIterableImpl(oStore, query.union(rightIterable.query), polymorphic)
    }

    override fun minus(right: EntityIterable): EntityIterable {
        val rightIterable = right.operand()
        if (rightIterable === YTDBEntityIterable.EMPTY) return this
        requirePolymorphicMatch(rightIterable)
        return YTDBEntityIterableImpl(oStore, query.difference(rightIterable.query), polymorphic)
    }

    override fun concat(right: EntityIterable): EntityIterable {
        val rightIterable = right.operand()
        if (rightIterable === YTDBEntityIterable.EMPTY) return this
        requirePolymorphicMatch(rightIterable)
        return YTDBEntityIterableImpl(oStore, query.unionAll(rightIterable.query), polymorphic)
    }

    /**
     * Normalises an operand to its query form. Wrappers — `PersistentEntityIterableWrapper`,
     * `SnapshotEntityIterable`, `YTDBVertexEntityIterable` — expose that form only through [unwrap];
     * casting before unwrapping fails in `asYTDBIterable` for the ones that are not themselves a
     * [YTDBEntityIterable].
     *
     * **Call this BEFORE the `=== EMPTY` identity guard, never after.** A wrapper around EMPTY is not
     * identical to EMPTY, so a guard on the raw argument misses it and then reads `EMPTY.query`, which
     * is `unsupported`. Unwrapping first is safe for EMPTY itself: `EMPTY.unwrap()` returns EMPTY and
     * EMPTY is a [YTDBEntityIterable], so the cast passes and the guard still fires.
     *
     * One helper for all five operand sites, so they cannot drift apart again.
     */
    private fun EntityIterable.operand(): YTDBEntityIterable = unwrap().asYTDBIterable()

    private fun requirePolymorphicMatch(right: YTDBEntityIterable) {
        require(polymorphic == right.polymorphic) {
            "Cannot combine a ${if (polymorphic) "polymorphic" else "non-polymorphic"} iterable " +
                    "with a ${if (right.polymorphic) "polymorphic" else "non-polymorphic"} one. " +
                    "Both operands must have the same polymorphic flag."
        }
    }

    override fun skip(number: Int): EntityIterable =
        if (number == 0) this
        else modify(GremlinBlock.Skip(number.toLong()))

    override fun take(number: Int): EntityIterable =
        if (number == 0) YTDBEntityIterable.EMPTY
        else modify(GremlinBlock.Limit(number.toLong()))

    override fun distinct(): EntityIterable = modify(GremlinBlock.Dedup)

    override fun selectDistinct(linkName: String): EntityIterable = selectManyDistinct(linkName)

    override fun selectMany(linkName: String): EntityIterable =
        YTDBEntityIterable.query(
            oStore,
            GremlinQuery.FollowLink(
                this.query,
                GremlinQuery.LinkDirection.OUT,
                linkName,
            ),
            polymorphic
        )

    override fun selectManyDistinct(linkName: String): EntityIterable =
        selectMany(linkName).distinct()

    override fun getFirst(): Entity? =
        iterator(traversal().limit(1)).use {
            if (it.hasNext()) it.next() else null
        }

    override fun getLast(): Entity? =
        iterator(traversal().tail()).use {
            if (it.hasNext()) return it.next() else null
        }

    override fun reverse(): EntityIterable = modify(GremlinBlock.Reverse)

    override fun isSortResult(): Boolean = false

    override fun asSortResult(): EntityIterable = this

    override fun unwrap(): EntityIterable = this

    // Xodus contract (EntityIterableBase.findLinks -> FilterLinksIterable): the result is the subset
    // of THIS iterable whose `linkName` link points into `entities` — not every entity that links into
    // `entities`. Intersecting the receiver with the in-link traversal keeps the receiver's filters;
    // building the traversal alone (as this used to) silently dropped them and returned a superset.
    //
    // The result carries the RECEIVER's `polymorphic` flag — it is a subset of the receiver. The two
    // queries are combined at the GremlinQuery level rather than through the public
    // intersect(EntityIterable) overload, because that overload's requirePolymorphicMatch would turn a
    // flag mismatch between receiver and argument into an exception.
    //
    // distinct() is still required: several entities of `entities` may be linked from the same
    // receiver entity, and the intersection may compile to a form driven by the in-link traversal
    // (`in()` is many-out-per-in), which would then emit that entity once per edge.
    //
    // The EMPTY short-circuit must be kept (EMPTY.query is `unsupported`) and must test the UNWRAPPED
    // argument: a wrapper around EMPTY is not identical to EMPTY, and unwrap() is identity for both
    // EMPTY and YTDBEntityIterableImpl. See operand() for why the order matters.
    override fun findLinks(
        entities: EntityIterable,
        linkName: String
    ): EntityIterable {
        val operand = entities.operand()
        if (operand === YTDBEntityIterable.EMPTY) return YTDBEntityIterable.EMPTY
        val byLink = operand.query.then(GremlinBlock.InLink(linkName))
        return YTDBEntityIterableImpl(oStore, query.intersect(byLink), polymorphic).distinct()
    }

    override fun idSet(): EntityIdSet =
        this.fold(EntityIdSetFactory.newSet()) { acc, e -> acc.add(e.id) }
}