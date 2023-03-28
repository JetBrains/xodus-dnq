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
package jetbrains.exodus.entitystore

internal class FullEntityId(store: EntityStore, id: EntityId) {

    private val storeHashCode: Int = System.identityHashCode(store)
    private val entityTypeId: Int = id.typeId
    private val entityLocalId: Long = id.localId

    override fun equals(other: Any?) = (this === other) ||
            (other is FullEntityId && storeHashCode == other.storeHashCode &&
                    entityLocalId == other.entityLocalId && entityTypeId == other.entityTypeId)

    override fun hashCode(): Int {
        var result = storeHashCode
        result = 31 * result + entityTypeId + 1
        result = 31 * result + ((entityLocalId + 1) xor (entityLocalId shr 32)).toInt()
        return result
    }

    override fun toString() = buildString(20) {
        toString(this)
    }

    fun toString(builder: StringBuilder) =
            builder.append(entityTypeId).append('-').append(entityLocalId).append('@').append(storeHashCode)
}

