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
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
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

    override fun indexOf(entity: Entity) = values.indexOf(entity)

    operator override fun contains(entity: Entity) = values.contains(entity)

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

    override fun skip(number: Int): EntityIterable {
        if (number == 0) return this

        return TransientEntityIterable(
                values.asSequence()
                        .drop(number)
                        .toSet()
        )
    }

    override fun take(number: Int): EntityIterable {
        if (number == 0) return EntityIterableBase.EMPTY

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
