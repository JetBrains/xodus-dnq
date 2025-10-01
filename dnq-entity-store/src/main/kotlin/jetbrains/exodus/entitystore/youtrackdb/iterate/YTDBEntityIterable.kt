/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.asYTDBIterable
import jetbrains.exodus.entitystore.util.unsupported
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal

interface YTDBEntityIterable : EntityIterable {
    companion object {
        @JvmStatic
        fun where(entityType: String, tx: YTDBStoreTransaction, condition: GremlinBlock): YTDBEntityIterable =
            query(
                tx,
                GremlinQuery.all
                    .then(condition)
                    .then(GremlinBlock.HasLabel(entityType))
            )

        @JvmStatic
        fun query(tx: YTDBStoreTransaction, query: GremlinQuery) =
            YTDBEntityIterableImpl(tx, query)

        val EMPTY = object : YTDBEntityIterable {

            override fun iterator(): EntityIterator = YTDBEntityIterator.EMPTY
            override fun selectMany(linkName: String): EntityIterable = this
            override val query: GremlinQuery get() = unsupported { "Should never be called" }
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
        }
    }

    fun selectMany(linkName: String): EntityIterable

    val query: GremlinQuery
}

class YTDBEntityIterableImpl(
    private val tx: YTDBStoreTransaction,
    override val query: GremlinQuery
) : YTDBEntityIterable {

    private val oStore: YTDBEntityStore = tx.getStore()

    @Volatile
    private var cachedSize: Long = -1

    private fun modify(block: GremlinBlock): YTDBEntityIterableImpl =
        YTDBEntityIterableImpl(tx, this.query.then(block))

    private fun iterator(traversal: GraphTraversal<*, YTDBVertex>): YTDBEntityIterator =
        YTDBEntityIterator.of(traversal, oStore)

    private fun traversal(): GraphTraversal<*, YTDBVertex> =
        query.start(oStore.requireActiveTransaction().g())

    override fun iterator(): YTDBEntityIterator = iterator(traversal())

    override fun getTransaction(): StoreTransaction = oStore.requireActiveTransaction()

    override fun isEmpty(): Boolean {
        val iter = iterator()
        try {
            return !iter.hasNext()
        } finally {
            iter.dispose()
        }
    }

    override fun size(): Long {
        cachedSize = traversal().count().use { it.next() }
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

    override fun contains(entity: Entity): Boolean = traversal()
        .hasId((entity.id as YTDBEntityId).asOId())
        .use { it.hasNext() }

    override fun intersect(right: EntityIterable): EntityIterable =
        if (right === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterableImpl(tx, query.intersect(right.asYTDBIterable().query))

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable = intersect(right)

    override fun union(right: EntityIterable): EntityIterable =
        if (right === YTDBEntityIterable.EMPTY) this
        else YTDBEntityIterableImpl(tx, query.union(right.asYTDBIterable().query))

    override fun minus(right: EntityIterable): EntityIterable =
        if (right === YTDBEntityIterable.EMPTY) this
        else YTDBEntityIterableImpl(tx, query.difference(right.asYTDBIterable().query))

    override fun concat(right: EntityIterable): EntityIterable =
        if (right === YTDBEntityIterable.EMPTY) this
        else YTDBEntityIterableImpl(tx, query.unionAll(right.asYTDBIterable().query))

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
            tx,
            GremlinQuery.FollowLink(
                this.query,
                GremlinQuery.LinkDirection.OUT,
                linkName,
            )
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

    override fun findLinks(
        entities: EntityIterable,
        linkName: String
    ): EntityIterable =
        if (entities === YTDBEntityIterable.EMPTY) YTDBEntityIterable.EMPTY
        else YTDBEntityIterableImpl(
            this.tx,
            entities
                .asYTDBIterable()
                .query
                .then(GremlinBlock.InLink(linkName))
        ).distinct()
}