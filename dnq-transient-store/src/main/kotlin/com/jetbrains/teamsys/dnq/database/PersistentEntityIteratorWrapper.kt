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

import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterator

class PersistentEntityIteratorWrapper(
        protected val source: EntityIterator,
        private val session: TransientStoreSession) : EntityIterator {

    override fun hasNext(): Boolean {
        return source.hasNext()
    }

    override fun next(): Entity? {
        //TODO: do not save in session?
        val persistentEntity = source.next() ?: return null
        return session.newEntity(persistentEntity)
    }

    override fun remove() {
        source.remove()
    }

    override fun nextId(): EntityId? {
        return source.nextId()
    }

    override fun dispose(): Boolean {
        return source.dispose()
    }

    override fun skip(number: Int): Boolean {
        return source.skip(number)
    }

    override fun shouldBeDisposed(): Boolean {
        //TODO: revisit EntityIterator interface and remove these stub method
        return source.shouldBeDisposed()
    }
}
