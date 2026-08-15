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

import jetbrains.exodus.entitystore.*

open class InMemoryEntityIterable(
    override val iterable: Iterable<Entity>,
    txn: StoreTransaction,
    queryEngine: QueryEngine,) : AbstractInMemoryEntityIterable(txn, queryEngine) {
}

abstract class AbstractInMemoryEntityIterable(
    private val txn: StoreTransaction,
    private val queryEngine: QueryEngine
) : EntityIterable {

    abstract val iterable: Iterable<Entity>

    override fun iterator(): EntityIterator {
        return InMemoryEntityIterator(iterable.iterator())
    }

    override fun getTransaction(): StoreTransaction {
        return txn
    }

    override fun isEmpty(): Boolean {
        return !iterable.iterator().hasNext()
    }

    override fun size(): Long {
        return iterable.count().toLong()
    }

    override fun count(): Long {
        return iterable.count().toLong()
    }

    override fun getRoughCount(): Long {
        return iterable.count().toLong()
    }

    override fun getRoughSize(): Long {
        return iterable.count().toLong()
    }

    /**
     * Compares [EntityId]s, analogous to Xodus's `EntityIterableBase.indexOfImpl`.
     *
     * The previous `iterable.indexOf(entity)` used `Any.equals`, **argument-first**: Kotlin's
     * `Iterable<T>.indexOf` evaluates `element == item` (stdlib 2.1.0
     * `commonMain/generated/_Collections.kt:322-332`, comparison at `:327`), and `List.indexOf`
     * likewise calls `o.equals(elementData[i])`. So a foreign wrapper carrying a member's id missed on
     * the argument's own `equals`: `YTDBVertexEntity.equals` bails at `other !is YTDBEntity` /
     * `javaClass != other.javaClass`, and `TransientEntityImpl.equals` at `other !is TransientEntity`.
     * Id comparison is safe across implementations: `RIDEntityId.equals` tests `(typeId, localId)`
     * against any [EntityId]. Complexity is unchanged — both forms are linear scans over [iterable].
     */
    override fun indexOf(entity: Entity): Int {
        val id = entity.id
        var i = 0
        for (e in iterable) {
            if (e.id == id) return i
            ++i
        }
        return -1
    }

    /**
     * Per the `EntityIterable.contains` contract, "just returns `indexOf(entity) != -1`" — but keeping
     * the backing collection's own membership test as a fast path where it is better than linear.
     *
     * [iterable] really can be hash-backed on a production path: `QueryEngine.inMemoryUnion` builds it
     * with Kotlin's `union`, i.e. a `LinkedHashSet`, and `Set.asIterable()` is the identity, so
     * `x in (qA union qB)` (via `XdQuery.contains` → `PersistentEntityIterableWrapper.contains`) used to
     * be an O(1) hash probe. Dropping that would regress it to O(n) for hits *and* misses.
     *
     * The probe can only produce **false negatives** — object/hash equality is strictly narrower than
     * id equality, and a positive means some element `equals` (or, for a sorted set, compares equal to)
     * the argument, which implies equal ids — so a miss falls through to the id scan, which is what
     * fixes the cross-wrapper-type case. The gate is `Set` rather than `Collection` on purpose: for a
     * `List` the old `contains` was already O(n), so a fast path there would only add a second scan.
     */
    override fun contains(entity: Entity): Boolean {
        if (iterable is Set<*> && iterable.contains(entity)) return true
        return indexOf(entity) != -1
    }

    override fun intersect(right: EntityIterable): EntityIterable {
        return InMemoryEntityIterable(queryEngine.inMemoryIntersect(this, right), txn, queryEngine)
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable {
        //TODO this is may be wrong
        return InMemoryEntityIterable(queryEngine.inMemoryIntersect(this, right), txn, queryEngine)
    }

    override fun union(right: EntityIterable): EntityIterable {
        return InMemoryEntityIterable(queryEngine.inMemoryUnion(this, right), txn, queryEngine)
    }

    override fun minus(right: EntityIterable): EntityIterable {
        return InMemoryEntityIterable(queryEngine.inMemoryExclude(this, right), txn, queryEngine)
    }

    override fun concat(right: EntityIterable): EntityIterable {
        return InMemoryEntityIterable(queryEngine.inMemoryConcat(this, right), txn, queryEngine)
    }

    override fun skip(number: Int): EntityIterable {
        val skipIterator = Iterable {
            val i = iterator()
            i.skip(number)
            i
        }
        return InMemoryEntityIterable(skipIterator, txn, queryEngine)
    }

    /**
     * Kotlin's `Iterable.take` opens with `require(n >= 0)`, so a negative [number] would throw where
     * Xodus's `EntityIterableBase.take` returns the empty iterable. Clamp negatives only: `take(0)`
     * already goes through `iterable.take(0)` == `emptyList()`.
     *
     * [skip] needs no such guard — its `InMemoryEntityIterator.skip` loops over `0..<number`, an empty
     * range for negatives, so every element survives. That is **content**-equivalent to Xodus's
     * `skip(n <= 0)`, not identical to it: Xodus returns the receiver, whereas [skip] here always
     * allocates a new iterable around a re-iterating `Iterable {}`. The difference is recorded as an
     * accepted risk (AR1) rather than fixed — nothing compares a `skip` result against its own receiver.
     */
    override fun take(number: Int): EntityIterable {
        if (number < 0) return InMemoryEntityIterable(emptyList(), txn, queryEngine)
        return InMemoryEntityIterable(iterable.take(number), txn, queryEngine)
    }

    override fun distinct(): EntityIterable {
        return InMemoryEntityIterable(iterable.distinct(), txn, queryEngine)
    }

    override fun selectDistinct(linkName: String): EntityIterable {
        val values = iterable.asSequence().mapNotNull {
            it.getLink(linkName)
        }.distinct()
        return InMemoryEntityIterable(values.asIterable(), txn, queryEngine)
    }

    override fun selectManyDistinct(linkName: String): EntityIterable {
        val values = iterable.asSequence().map { it.getLinks(linkName) }.flatten().distinct()
        return InMemoryEntityIterable(values.asIterable(), txn, queryEngine)
    }

    override fun getFirst(): Entity? {
        return iterable.firstOrNull()
    }

    override fun getLast(): Entity? {
        return iterable.lastOrNull()
    }

    override fun reverse(): EntityIterable {
        return InMemoryEntityIterable(iterable.reversed(), txn, queryEngine)
    }

    override fun isSortResult(): Boolean {
        return false
    }

    override fun asSortResult(): EntityIterable {
        throw NotImplementedError()
    }

    override fun unwrap(): EntityIterable {
        return this
    }

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
        throw NotImplementedError()
    }
}

internal class InMemoryEntityIterator(val iterator: Iterator<Entity>) : EntityIterator {
    override fun remove() {
        throw UnsupportedOperationException()
    }

    override fun hasNext() = iterator.hasNext()

    override fun next() = iterator.next()

    /**
     * Skips up to [number] entities and returns the value of [hasNext], per the
     * `EntityIterator.skip` contract ("Skips specified number of entities and returns the value of
     * `hasNext()`") — not "did I manage to skip that many".
     */
    override fun skip(number: Int): Boolean {
        for (i in 0..<number) {
            if (iterator.hasNext()) {
                iterator.next()
            } else {
                return false
            }
        }
        return iterator.hasNext()
    }

    override fun nextId(): EntityId {
        return next().id
    }

    /**
     * Nothing is held — [iterator] is a plain `Iterator` over an already-materialised `Iterable` — so
     * nothing can be released, and `dispose()`'s contract is "`true` if the `EntityIterator` was
     * actually disposed". Kept as `false` rather than the throwing form used by the two transient
     * iterators, because `dispose()` is routinely called in `finally` blocks that ignore its answer.
     */
    override fun dispose() = false

    override fun shouldBeDisposed() = false

}
