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

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.entitystore.iterate.EntityIteratorBase
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId
import jetbrains.exodus.entitystore.iterate.PropertyValueIterator

object UniversalEmptyEntityIterable : EntityIterableBase(null) {

    override fun iterator() = Iterator

    override fun getIteratorImpl(txn: PersistentStoreTransaction) = Iterator

    override fun isEmpty() = true

    override fun size() = 0L

    override fun count() = 0L

    override fun getRoughCount() = 0L

    override fun contains(entity: Entity) = false

    override fun getHandleImpl() = EntityIterableBase.EMPTY.handle

    override fun indexOf(entity: Entity) = -1

    override fun countImpl(txn: PersistentStoreTransaction) = 0L

    override fun canBeCached() = false

    override fun getSource(): EntityIterableBase = EntityIterableBase.EMPTY

    object Iterator : EntityIteratorBase(UniversalEmptyEntityIterable), EntityIteratorWithPropId, PropertyValueIterator {
        override fun currentLinkName() = null
        override fun hasNextImpl() = false
        override fun nextIdImpl(): EntityId? = null
        override fun remove() = throw UnsupportedOperationException()
        override fun shouldBeDisposed() = false
        override fun currentValue(): Comparable<Any>? = null
    }

}
