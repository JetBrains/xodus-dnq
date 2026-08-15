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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import mu.KLogging

/**
 * Date: 28.12.2006
 * Time: 13:10:48
 *
 * @author Vadim.Gurov
 */
open class TransientEntityIterable(protected val values: Set<TransientEntity>) : EntityIterableWrapper {
    companion object : KLogging()

    override fun size(): Long {
        logger.warn { "size() is requested from TransientEntityIterable!" }
        return values.size.toLong()
    }

    override fun count(): Long {
        logger.warn { "count() is requested from TransientEntityIterable!" }
        return values.size.toLong()
    }

    override fun getRoughCount(): Long {
        logger.warn { "getRoughCount() is requested from TransientEntityIterable!" }
        return values.size.toLong()
    }

    override fun getRoughSize(): Long {
        logger.warn { "getRoughSize() is requested from TransientEntityIterable!" }
        return values.size.toLong()
    }

    /**
     * Compares [jetbrains.exodus.entitystore.EntityId]s, analogous to Xodus's
     * `EntityIterableBase.indexOfImpl`.
     *
     * The previous `values.indexOf(entity)` missed whenever the argument was the *persistent* entity
     * for the same id — which is exactly what `XdQuery.contains`/`indexOf` can hand down. The
     * mechanism is **argument-first**: [values] is a `Set`, not a `List`, so Kotlin's
     * `Iterable<T>.indexOf` falls through to its scan, which evaluates `element == item`
     * (stdlib 2.1.0 `commonMain/generated/_Collections.kt:322-332`, the comparison at `:327`) — i.e.
     * it runs the *argument's* `equals`. For a `YTDBVertexEntity` argument that is
     * `YTDBVertexEntity.equals`, which returns `false` at its `other !is YTDBEntity` check because
     * `TransientEntity` extends `Entity`, not `YTDBEntity`; the elements' `TransientEntityImpl.equals`
     * is never reached. Id comparison is safe across implementations: `RIDEntityId.equals` tests
     * `(typeId, localId)` against any `EntityId`.
     *
     * Note the index of a `Set`-backed iterable has always been iteration-order dependent; that is
     * unchanged.
     */
    override fun indexOf(entity: Entity): Int {
        val id = entity.id
        return values.indexOfFirst { it.id == id }
    }

    /**
     * `indexOf(entity) != -1`, but keeping the O(1) hash lookup as a fast path: [values] is a `Set`,
     * so the common same-type case must not become a linear scan. The two are consistent —
     * `TransientEntityImpl.equals` implies equal ids — and the hash lookup cannot succeed for a
     * foreign wrapper anyway (`TransientEntityImpl.hashCode` is `id.hashCode() +
     * persistentStore.hashCode()`, `YTDBVertexEntity.hashCode` is `id.hashCode()`).
     */
    operator override fun contains(entity: Entity) =
            values.contains(entity) || values.any { it.id == entity.id }

    override fun intersect(right: EntityIterable): EntityIterable =
            throw UnsupportedOperationException("Not supported by TransientEntityIterable")

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
        throw UnsupportedOperationException("Not supported by TransientEntityIterable")
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable =
            throw UnsupportedOperationException("Not supported by TransientEntityIterable")

    override fun union(right: EntityIterable): EntityIterable =
            throw UnsupportedOperationException("Not supported by TransientEntityIterable")

    override fun minus(right: EntityIterable): EntityIterable =
            throw UnsupportedOperationException("Not supported by TransientEntityIterable")

    override fun concat(right: EntityIterable): EntityIterable {
        if (right !is TransientEntityIterable) throw UnsupportedOperationException("Not supported by TransientEntityIterable")

        return TransientEntityIterable(values + right.values)
    }

    /**
     * `Sequence.drop` opens with `require(n >= 0)`, so a negative [number] would throw where Xodus's
     * `EntityIterableBase.skip` returns the receiver. Widening the existing `== 0` guard to `<= 0` is a
     * negative-only change: the guard already returned `this` at `0`.
     */
    override fun skip(number: Int): EntityIterable {
        if (number <= 0) return this

        return TransientEntityIterable(
                values.asSequence()
                        .drop(number)
                        .toSet()
        )
    }

    /**
     * `Sequence.take` opens with `require(n >= 0)`, so a negative [number] would throw where Xodus's
     * `EntityIterableBase.take` returns the empty iterable. As in [skip], widening `== 0` to `<= 0`
     * changes nothing at `0`.
     */
    override fun take(number: Int): EntityIterable {
        if (number <= 0) return YTDBEntityIterable.EMPTY

        return TransientEntityIterable(
                values.asSequence()
                        .take(number)
                        .toSet()
        )
    }

    override fun distinct() = this

    override fun selectDistinct(linkName: String): EntityIterable =
            throw UnsupportedOperationException("Not supported by TransientEntityIterable")

    override fun selectManyDistinct(linkName: String): EntityIterable =
            throw UnsupportedOperationException("Not supported by TransientEntityIterable")

    override fun getFirst() = values.firstOrNull()

    override fun getLast() = values.lastOrNull()

    override fun reverse(): EntityIterable {
        throw UnsupportedOperationException("Not supported by TransientEntityIterable")
    }

    override fun isSortResult(): Boolean {
        throw UnsupportedOperationException("Not supported by TransientEntityIterable")
    }

    override fun asSortResult(): EntityIterable {
        throw UnsupportedOperationException("Not supported by TransientEntityIterable")
    }

    override fun iterator(): EntityIterator {
        logger.trace { "New iterator requested for transient iterable ${this}" }
        return TransientEntityIterator(values.iterator())
    }

    override fun getTransaction(): StoreTransaction {
        throw UnsupportedOperationException("Not supported by TransientEntityIterable")
    }

    override fun isEmpty() = values.isEmpty()
}
