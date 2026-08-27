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
import com.jetbrains.youtrackdb.internal.common.util.Sizeable
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.util.unsupported
import jetbrains.exodus.entitystore.youtrackdb.RIDEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.entitystore.youtrackdb.resolveTypeName
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import org.apache.commons.collections4.IterableUtils

class YTDBVertexEntityIterable(
    private val tx: YTDBStoreTransaction,
    private val vertices: Iterable<YTDBVertex>,
    private val store: YTDBEntityStore,
    private val linkName: String,
    private val targetEntityID: YTDBEntityId
) : EntityIterable {

    override fun iterator() = object : EntityIterator {

        private val iterator = vertices.iterator()

        /**
         * Skips up to [number] entities and returns the value of `hasNext()`, per the
         * [EntityIterator.skip] contract (Xodus: `while (number-- > 0 && hasNextImpl()) nextIdImpl();
         * return hasNextImpl()`).
         *
         * A non-positive [number] consumes nothing. Note this must *consume* — the previous form
         * counted `hasNext()` (which consumes nothing) and then consumed exactly one element
         * unconditionally, which is what made [getLast] return the wrong element or throw.
         */
        override fun skip(number: Int): Boolean {
            var left = number
            while (left-- > 0 && iterator.hasNext()) {
                iterator.next()
            }
            return iterator.hasNext()
        }

        override fun nextId() = RIDEntityId.fromVertex(iterator.next())

        /**
         * Nothing is held — [vertices] is an already-materialised `List` — so nothing can be
         * released, and `dispose()`'s contract is "`true` if the `EntityIterator` was actually
         * disposed". Keep this consistent with [shouldBeDisposed].
         */
        override fun dispose() = false

        override fun shouldBeDisposed() = false

        override fun hasNext() = iterator.hasNext()

        override fun next() = YTDBVertexEntity(iterator.next(), store)

        override fun remove() = unsupported()
    }

    override fun getTransaction() = tx

    override fun isEmpty() = !vertices.iterator().hasNext()

    override fun size() = when (vertices) {
        is Collection<*> -> vertices.size.toLong()
        is Sizeable -> vertices.size().toLong()
        else -> IterableUtils.size(vertices).toLong()
    }

    override fun count() = when (vertices) {
        is Collection<*> -> vertices.size.toLong()
        is Sizeable -> vertices.size().toLong()
        else -> -1
    }

    override fun getRoughCount() = count()

    override fun getRoughSize() = count()

    /**
     * Compares [jetbrains.exodus.entitystore.EntityId]s, matching the sibling
     * [YTDBEntityIterableImpl.indexOf] and Xodus's `EntityIterableBase.indexOfImpl`.
     *
     * The previous commons-collections form (`IteratorUtils.indexOf(iterator(),
     * EqualPredicate.equalPredicate(entity))`) compared *objects*, and argument-first at that, so a
     * foreign `Entity` implementation carrying a member's id missed: `YTDBVertexEntity.equals` has a
     * `javaClass` check and `TransientEntityImpl.equals` rejects any non-`TransientEntity` before it
     * ever looks at the id. Id comparison is safe across implementations — `RIDEntityId.equals`
     * compares `(typeId, localId)` against any [jetbrains.exodus.entitystore.EntityId].
     */
    override fun indexOf(entity: Entity): Int {
        val id = entity.id
        val it = iterator()
        var i = 0
        while (it.hasNext()) {
            if (it.nextId() == id) return i
            ++i
        }
        return -1
    }

    /** Per the `EntityIterable.contains` contract: "just returns `indexOf(entity) != -1`". */
    override fun contains(entity: Entity) = indexOf(entity) != -1

    override fun intersect(right: EntityIterable) = asQueryIterable().intersect(right)

    override fun intersectSavingOrder(right: EntityIterable) = asQueryIterable().intersectSavingOrder(right)

    override fun union(right: EntityIterable) = asQueryIterable().union(right)

    override fun minus(right: EntityIterable) = asQueryIterable().minus(right)

    override fun concat(right: EntityIterable) = asQueryIterable().concat(right)

    //Here we may optimize it somehow, but have to store skip and so-on
    override fun skip(number: Int) = asQueryIterable().skip(number)

    override fun take(number: Int) = asQueryIterable().take(number)

    override fun distinct() = asQueryIterable().distinct()

    override fun selectDistinct(linkName: String) = asQueryIterable().selectDistinct(linkName)

    override fun selectManyDistinct(linkName: String) = asQueryIterable().selectManyDistinct(linkName)

    override fun getFirst() = iterator().run { if (hasNext()) next() else null }

    /**
     * Walks to the last element. Deliberately independent of [count] (which returns `-1` for a
     * non-`Collection`, non-`Sizeable` source) and of [skip].
     */
    override fun getLast(): Entity? {
        var last: Entity? = null
        val it = iterator()
        while (it.hasNext()) {
            last = it.next()
        }
        return last
    }

    override fun reverse() = asQueryIterable().reverse()

    override fun isSortResult() = false

    override fun asSortResult() = this

    override fun unwrap() = asQueryIterable()

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
        return asQueryIterable().findLinks(entities, linkName)
    }

    /**
     * The query form of this link read, used by [unwrap] and by every derived operation
     * (intersect/union/skip/take/...).
     *
     * [vertices] is already ordered by entity id (`YTDBVertexEntity.getLinks` sorts it to reproduce
     * the Xodus link contract), so the query must declare the same order — otherwise merely
     * unwrapping a link read, which callers do routinely, silently loses it. `SortBy` is understood
     * by the optimizer: intersect/difference keep the left operand's sort, union strips it.
     */
    private fun asQueryIterable() = YTDBEntityIterable.query(
        tx.getStore(),
        GremlinQuery.ByIds(listOf(targetEntityID.asOId()), targetEntityID.resolveTypeName(tx))
            .then(GremlinBlock.OutLink(linkName))
            .then(GremlinBlock.LocalIdAsc)
    )
}