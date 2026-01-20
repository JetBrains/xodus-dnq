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

import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterator

class PersistentEntityIteratorWrapper(
    private val source: EntityIterator,
    private val session: TransientStoreSession
) : EntityIterator {

    private val changes: TransientChangesTracker = session.transientChangesTracker

    private var _current: Entity? = null

    private fun initCurrent(): Entity? {

        while (_current == null && source.hasNext()) {
            val n = source.next()
            if (!changes.isRemoved(n.id)) {
                _current = n
            }
        }
        return _current
    }

    private fun consumeCurrent(): Entity =
        initCurrent()
            ?.also { _current = null }
            ?: throw NoSuchElementException()

    override fun hasNext(): Boolean {
        return initCurrent() != null
    }

    override fun next(): Entity {
        //TODO: do not save in session?
        return session.newEntity(consumeCurrent())
    }

    override fun remove() {
        consumeCurrent()
        source.remove()
    }

    override fun nextId(): EntityId {
        return consumeCurrent().id
    }

    override fun dispose(): Boolean {
        return source.dispose()
    }

    override fun skip(number: Int): Boolean {

        repeat(number) {
            if (initCurrent() == null) {
                return false
            }
            consumeCurrent()
        }
        return true
    }

    override fun shouldBeDisposed(): Boolean {
        //TODO: revisit EntityIterator interface and remove these stub method
        return source.shouldBeDisposed()
    }
}
