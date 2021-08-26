/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import jetbrains.exodus.core.dataStructures.hash.LongHashSet
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntity
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.query.StaticTypedEntityIterable

/**
 * @author Vadim.Gurov
 */
object TransientStoreUtil {
    private val POSTPONE_UNIQUE_INDICES = LongHashSet(10)

    @JvmStatic
    var isPostponeUniqueIndexes: Boolean
        get() {
            val id = Thread.currentThread().id
            synchronized(POSTPONE_UNIQUE_INDICES) {
                return POSTPONE_UNIQUE_INDICES.contains(id)
            }
        }
        set(postponeUniqueIndexes) {
            val id = Thread.currentThread().id
            if (postponeUniqueIndexes) {
                synchronized(POSTPONE_UNIQUE_INDICES) {
                    POSTPONE_UNIQUE_INDICES.add(id)
                }
            } else {
                synchronized(POSTPONE_UNIQUE_INDICES) {
                    POSTPONE_UNIQUE_INDICES.remove(id)
                }
            }
        }

    @JvmStatic
    fun getCurrentSession(entity: TransientEntity): TransientStoreSession? {
        return entity.store.currentTransaction as TransientStoreSession?
    }

    /**
     * Attach entity to current session if possible.
     */
    @JvmStatic
    @Deprecated("Use entity.reattach() instead", ReplaceWith("entity?.reattach()", "com.jetbrains.teamsys.dnq.database.TransientEntityUtilKt.reattach"))
    fun reattach(entity: TransientEntity?): TransientEntity? {
        return entity?.reattach()
    }


    /**
     * Checks if entity entity was removed
     *
     * @return true if [entity] was removed, false if it wasn't removed at all
     */
    @JvmStatic
    fun isRemoved(entity: Entity): Boolean {
        return when (entity) {
            is PersistentEntity -> {
                val store = entity.store as PersistentEntityStoreImpl
                store.getLastVersion(store.currentTransactionOrThrow, entity.id) < 0
            }
            is TransientEntity -> entity.store.threadSessionOrThrow.isRemoved(entity)
            else -> throw IllegalArgumentException("Cannot check if entity [$entity] is removed, it is neither TransientEntity nor PersistentEntity")
        }
    }

    @JvmStatic
    fun commit(s: TransientStoreSession?) {
        if (s != null && s.isOpened) {
            try {
                s.commit()
            } catch (e: Throwable) {
                abort(e, s)
            }
        }
    }

    @JvmStatic
    fun abort(session: TransientStoreSession?) {
        if (session != null && session.isOpened) {
            session.abort()
        }
    }

    @JvmStatic
    fun abort(exception: Throwable, s: TransientStoreSession?) {
        abort(s)
        when (exception) {
            is Error -> throw exception
            is RuntimeException -> throw exception
            else -> throw RuntimeException(exception)
        }
    }

    @JvmStatic
    fun getSize(it: Iterable<Entity>?): Int {
        val iterable = if (it is StaticTypedEntityIterable) it.instantiate() else it
        return when {
            iterable == null -> 0
            iterable === EntityIterableBase.EMPTY -> 0
            iterable is EntityIterable -> iterable.size().toInt()
            iterable is Collection<*> -> (iterable as Collection<*>).size
            else -> iterable.count()
        }
    }

    @JvmStatic
    internal fun toString(strings: Set<String>?): String {
        return strings?.joinToString(",").orEmpty()
    }

    @JvmStatic
    internal fun toString(map: Map<*, *>?): String {
        return map?.asSequence()
                ?.joinToString(",") { (key, value) -> "$key:$value" }
                .orEmpty()
    }

}
