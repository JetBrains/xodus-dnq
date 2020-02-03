/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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

import jetbrains.exodus.database.LinkChange
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId

internal open class AddedOrRemovedLinksFromSetTransientEntityIterable(
        values: Set<TransientEntity>,
        private val removed: Boolean,
        private val linkNames: Set<String>,
        private val changesLinks: Map<String, LinkChange>) : TransientEntityIterable(values) {

    override fun iterator(): EntityIterator {
        val linkNamesIterator = linkNames.iterator()

        return object : EntityIteratorWithPropId {
            private var currentLinkName: String? = null
            private var currentIterator: Iterator<TransientEntity>? = null

            override fun currentLinkName(): String? {
                return currentLinkName
            }

            override fun hasNext(): Boolean {
                val currentI = currentIterator
                if (currentI != null && currentI.hasNext()) {
                    return true
                }
                while (linkNamesIterator.hasNext()) {
                    val linkName = linkNamesIterator.next()
                    val linkChange = changesLinks[linkName]
                    if (linkChange != null) {
                        val changedEntities = if (removed) {
                            linkChange.removedEntities
                        } else {
                            linkChange.addedEntities
                        }
                        if (changedEntities != null) {
                            val itr = changedEntities.iterator()
                            if (itr.hasNext()) {
                                currentLinkName = linkName
                                currentIterator = itr
                                return true
                            }
                        }
                    }
                }
                return false
            }

            override fun next(): Entity {
                val iterator = currentIterator
                return if (hasNext() && iterator != null) {
                    iterator.next()
                } else {
                    throw NoSuchElementException()
                }
            }

            override fun nextId() = next().id

            override fun skip(number: Int): Boolean {
                var itemsToSkipLeft = number
                while (itemsToSkipLeft > 0) {
                    if (hasNext()) {
                        next()
                        --itemsToSkipLeft
                    } else {
                        return false
                    }
                }
                return true
            }

            override fun shouldBeDisposed() = false

            override fun dispose(): Boolean {
                throw UnsupportedOperationException("Transient iterator does not support disposing")
            }

            override fun remove() {
                throw UnsupportedOperationException("Remove from iterator is not supported by transient iterator")
            }
        }
    }

    override fun size() = values.size.toLong()

    override fun count() = values.size.toLong()

    companion object {

        @JvmStatic
        fun get(changesLinks: Map<String, LinkChange>,
                linkNames: Set<String>,
                removed: Boolean): EntityIterable {
            val changedEntities = linkNames
                    .asSequence()
                    .mapNotNull { changesLinks[it] }
                    .mapNotNull { if (removed) it.removedEntities else it.addedEntities }
                    .flatten()
                    .toSet()
            return if (!changedEntities.isEmpty()) {
                AddedOrRemovedLinksFromSetTransientEntityIterable(changedEntities, removed, linkNames, changesLinks)
            } else {
                UniversalEmptyEntityIterable
            }
        }
    }
}
