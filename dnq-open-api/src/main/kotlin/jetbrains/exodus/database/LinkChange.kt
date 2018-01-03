/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package jetbrains.exodus.database

import jetbrains.exodus.core.dataStructures.hash.HashSet

class LinkChange(val linkName: String) {
    private var _addedEntities: MutableSet<TransientEntity>? = null
    val addedEntities get() = _addedEntities
    val addedEntitiesSize: Int
        get() = _addedEntities?.size ?: 0

    private var _removedEntities: MutableSet<TransientEntity>? = null
    val removedEntities get() = _removedEntities
    val removedEntitiesSize: Int
        get() = _removedEntities?.size ?: 0

    private var _deletedEntities: MutableSet<TransientEntity>? = null
    val deletedEntities get() = _deletedEntities
    val deletedEntitiesSize: Int
        get() = _deletedEntities?.size ?: 0

    val changeType: LinkChangeType
        get() {
            val added = addedEntitiesSize
            val removed = removedEntitiesSize + deletedEntitiesSize

            return when {
                added != 0 && removed == 0 -> LinkChangeType.ADD
                added == 0 && removed != 0 -> LinkChangeType.REMOVE
                added != 0 && removed != 0 -> LinkChangeType.ADD_AND_REMOVE
                else -> throw IllegalStateException("No added or removed links.")
            }
        }


    override fun toString() = "$linkName:$changeType"

    fun addAdded(e: TransientEntity) {
        if (_removedEntities?.remove(e) == true) return

        val addedEntities = this._addedEntities ?:
                HashSet<TransientEntity>().also { newSet ->
                    this._addedEntities = newSet
                }
        addedEntities.add(e)
    }

    fun addRemoved(e: TransientEntity) {
        if (_addedEntities?.remove(e) == true) return

        val removedEntities = this._removedEntities ?:
                HashSet<TransientEntity>().also { newSet ->
                    this._removedEntities = newSet
                }
        removedEntities.add(e)
    }

    fun addDeleted(e: TransientEntity) {
        _removedEntities?.remove(e)
        _addedEntities?.remove(e)

        val deletedEntities = this._deletedEntities ?:
                HashSet<TransientEntity>().also { newSet ->
                    this._deletedEntities = newSet
                }
        deletedEntities.add(e)
    }
}
