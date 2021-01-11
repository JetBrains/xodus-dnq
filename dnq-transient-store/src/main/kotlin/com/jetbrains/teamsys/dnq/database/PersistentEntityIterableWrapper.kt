/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import jetbrains.exodus.entitystore.iterate.EntityIterableBase


/**
 * Wrapper for persistent iterable. Handles iterator.next and delegates it to transient session.
 *
 * @author Vadim.Gurov
 */
open class PersistentEntityIterableWrapper(
        protected val store: TransientEntityStore,
        wrappedIterable: EntityIterable) :
        EntityIterableWrapper,
        EntityIterableBase(
                (wrappedIterable as EntityIterableBase).source
                        .takeIf { it !== EMPTY }
                        ?.transaction) {

    protected val wrappedIterable: EntityIterableBase = wrappedIterable.let {
        if (wrappedIterable is PersistentEntityIterableWrapper) {
            throw IllegalArgumentException("Can't wrap transient entity iterable with another transient entity iterable.")
        }
        (wrappedIterable as EntityIterableBase).source
    }

    override fun size() = wrappedIterable.size()

    override fun count() = wrappedIterable.count()

    override fun getRoughCount() = wrappedIterable.roughCount

    override fun getRoughSize() = wrappedIterable.roughSize

    override fun indexOf(entity: Entity) = wrappedIterable.indexOf(entity)

    override fun contains(entity: Entity) = wrappedIterable.contains(entity)

    public override fun getHandleImpl() = wrappedIterable.handle

    override fun intersect(right: EntityIterable): EntityIterable {
        right as? EntityIterableBase ?: throwUnsupported()
        return wrappedIterable.intersect(right.source)
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable {
        right as? EntityIterableBase ?: throwUnsupported()
        return wrappedIterable.intersectSavingOrder(right.source)
    }

    override fun union(right: EntityIterable): EntityIterable {
        right as? EntityIterableBase ?: throwUnsupported()
        return wrappedIterable.union(right.source)
    }

    override fun minus(right: EntityIterable): EntityIterable {
        right as? EntityIterableBase ?: throwUnsupported()
        return wrappedIterable.minus(right.source)
    }

    override fun concat(right: EntityIterable): EntityIterable {
        right as? EntityIterableBase ?: throwUnsupported()
        return wrappedIterable.concat(right.source)
    }

    override fun take(number: Int): EntityIterable {
        return wrappedIterable.take(number)
    }

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
        return wrappedIterable.findLinks(entities, linkName)
    }

    override fun distinct(): EntityIterable {
        return wrappedIterable.distinct()
    }

    override fun selectDistinct(linkName: String): EntityIterable {
        return wrappedIterable.selectDistinct(linkName)
    }

    override fun selectManyDistinct(linkName: String): EntityIterable {
        return wrappedIterable.selectManyDistinct(linkName)
    }

    override fun getFirst() = wrappedIterable.first?.wrap(store)

    override fun getLast() = wrappedIterable.last?.wrap(store)

    override fun reverse(): EntityIterable {
        return wrappedIterable.reverse()
    }

    override fun isSortResult() = wrappedIterable.isSortResult

    override fun asSortResult(): EntityIterable =
            PersistentEntityIterableWrapper(store, wrappedIterable.asSortResult())

    override fun getSource(): EntityIterableBase {
        return wrappedIterable
    }

    override fun iterator(): EntityIterator {
        return PersistentEntityIteratorWrapper(wrappedIterable.iterator(), store.threadSessionOrThrow)
    }

    override fun getIteratorImpl(txn: PersistentStoreTransaction): EntityIterator {
        throw UnsupportedOperationException("Should never be called")
    }

    override fun isEmpty() = wrappedIterable.isEmpty

    private fun throwUnsupported(): Nothing = throw UnsupportedOperationException("Should never be called")

    private fun Entity.wrap(store: TransientEntityStore): TransientEntity {
        return store.threadSessionOrThrow.newEntity(this)
    }
}
