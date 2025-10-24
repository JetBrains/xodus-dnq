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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import java.util.*
import java.util.function.Consumer

internal class SnapshotEntityIterable(
    val original: EntityIterable,
    val store: TransientEntityStore,
) : EntityIterable by original {
    override fun iterator(): EntityIterator = SnapshotEntityIterator(original.iterator(), store)

    private fun getOriginal(entityIterable: EntityIterable): EntityIterable =
        (entityIterable as? SnapshotEntityIterable)?.original ?: entityIterable

    private fun wrap(entityIterable: EntityIterable): EntityIterable =
        entityIterable as? SnapshotEntityIterable ?: SnapshotEntityIterable(entityIterable, store)

    override fun intersect(right: EntityIterable): EntityIterable =
        wrap(original.intersect(getOriginal(right)))

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable =
        wrap(original.intersectSavingOrder(getOriginal(right)))

    override fun union(right: EntityIterable): EntityIterable =
        wrap(original.union(getOriginal(right)))

    override fun minus(right: EntityIterable): EntityIterable =
        wrap(original.minus(getOriginal(right)))

    override fun concat(right: EntityIterable): EntityIterable =
        wrap(original.minus(getOriginal(right)))

    override fun skip(number: Int): EntityIterable = wrap(original.skip(number))

    override fun take(number: Int): EntityIterable = wrap(original.take(number))

    override fun distinct(): EntityIterable = wrap(original.distinct())

    override fun selectDistinct(linkName: String): EntityIterable = wrap(original.selectDistinct(linkName))

    override fun selectManyDistinct(linkName: String): EntityIterable = wrap(original.selectManyDistinct(linkName))

    override fun getFirst(): Entity? = original.first?.let { SnapshotEntityIterator.wrapEntity(it, store) }

    override fun getLast(): Entity? = original.last?.let { SnapshotEntityIterator.wrapEntity(it, store) }

    override fun reverse(): EntityIterable = wrap(original.reverse())

    override fun isSortResult(): Boolean = original.isSortResult

    override fun asSortResult(): EntityIterable = wrap(original.asSortResult())

    override fun unwrap(): EntityIterable = wrap(original.unwrap())

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable =
        wrap(original.findLinks(entities, linkName))

    override fun forEach(action: Consumer<in Entity>) {
        original.forEach(action)
    }

    override fun spliterator(): Spliterator<Entity> {
        return original.spliterator()
    }

}

internal class SnapshotEntityIterator(
    val original: EntityIterator,
    val store: TransientEntityStore
) : EntityIterator by original {

    companion object {

        fun wrapEntity(entity: Entity, store: TransientEntityStore) = when (entity) {
            is YTDBVertexEntityRemoved -> RemovedTransientEntity(entity, store)
            is YTDBVertexEntity -> ReadonlyTransientEntity(entity, store)
            else -> entity
        }
    }

    override fun next(): Entity = wrapEntity(original.next(), store)
    override fun forEachRemaining(action: Consumer<in Entity>) = original.forEachRemaining(action)
}

