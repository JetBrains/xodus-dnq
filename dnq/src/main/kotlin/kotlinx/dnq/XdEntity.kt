/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.EntityOperations
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.util.reattach

abstract class XdEntity(val entity: Entity) {
    val entityId: EntityId get() = entity.id
    val xdId: String get() = entityId.toString()

    val isNew: Boolean
        get() = reattach().isNew

    val isRemoved: Boolean
        get() = (entity as TransientEntity).store.threadSessionOrThrow.isRemoved(entity)

    open fun delete() {
        EntityOperations.remove(entity)
    }

    open fun beforeFlush() {}

    open fun destructor() {}

    open fun constructor() {}

    override fun equals(other: Any?): Boolean {
        return when {
            this === other -> true
            other !is XdEntity -> false
            else -> this.entity == other.entity
        }
    }

    override fun hashCode() = entity.hashCode()

    override fun toString(): String {
        return "${this::class.simpleName} wrapper for $entity"
    }
}
