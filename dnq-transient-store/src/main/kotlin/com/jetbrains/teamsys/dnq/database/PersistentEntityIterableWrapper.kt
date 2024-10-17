/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.orientdb.OEntityIterable
import jetbrains.exodus.entitystore.orientdb.iterate.OEntityIterableBase
import jetbrains.exodus.entitystore.orientdb.query.OSelect


/**
 * Wrapper for persistent iterable. Handles iterator.next and delegates it to transient session.
 *
 * @author Vadim.Gurov
 */
open class PersistentEntityIterableWrapper(
        protected val store: TransientEntityStore,
        wrappedIterable: EntityIterable) :
        EntityIterableWrapper,
        OEntityIterable {

    protected val wrappedIterable: EntityIterable = wrappedIterable.let {
        if (wrappedIterable is PersistentEntityIterableWrapper) {
            throw IllegalArgumentException("Can't wrap transient entity iterable with another transient entity iterable.")
        }
        wrappedIterable.unwrap()
    }

    override fun size() = wrappedIterable.size()

    override fun count() = wrappedIterable.count()

    override fun getRoughCount() = wrappedIterable.roughCount

    override fun getRoughSize() = wrappedIterable.roughSize

    override fun indexOf(entity: Entity) = wrappedIterable.indexOf(entity)

    override fun contains(entity: Entity) = wrappedIterable.contains(entity)

    override fun intersect(right: EntityIterable): EntityIterable {
        right as? EntityIterable ?: throwUnsupported()
        return wrappedIterable.intersect(right.unwrap())
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable {
        right as? EntityIterable ?: throwUnsupported()
        return wrappedIterable.intersectSavingOrder(right.unwrap())
    }

    override fun union(right: EntityIterable): EntityIterable {
        right as? EntityIterable ?: throwUnsupported()
        return wrappedIterable.union(right.unwrap())
    }

    override fun minus(right: EntityIterable): EntityIterable {
        right as? EntityIterable ?: throwUnsupported()
        return wrappedIterable.minus(right.unwrap())
    }

    override fun concat(right: EntityIterable): EntityIterable {
        right as? EntityIterable ?: throwUnsupported()
        return wrappedIterable.concat(right.unwrap())
    }

    override fun skip(number: Int): EntityIterable {
        return wrappedIterable.skip(number)
    }

    override fun take(number: Int): EntityIterable {
        return wrappedIterable.take(number)
    }

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
        //TODO move findLinks to interface
        return (wrappedIterable as? OEntityIterableBase)?.findLinks(entities, linkName) ?: OEntityIterableBase.EMPTY
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

    override fun iterator(): EntityIterator {
        return PersistentEntityIteratorWrapper(wrappedIterable.iterator(), store.threadSessionOrThrow)
    }

    override fun isEmpty() = wrappedIterable.isEmpty

    private fun throwUnsupported(): Nothing = throw UnsupportedOperationException("Should never be called")

    private fun Entity.wrap(store: TransientEntityStore): TransientEntity {
        return store.threadSessionOrThrow.newEntity(this)
    }

    override fun unwrap(): OEntityIterableBase {
        return wrappedIterable as OEntityIterableBase
    }

    override fun query(): OSelect {
        return wrappedIterable.asOQueryIterable().query()
    }

    override fun getTransaction(): StoreTransaction {
        return if (wrappedIterable == OEntityIterableBase.EMPTY){
            store.currentTransaction ?: throw IllegalStateException("EntityStore: current transaction is not set")
        } else {
            (wrappedIterable as OEntityIterable).transaction
        }
    }

}
