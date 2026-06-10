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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.ExodusException
import jetbrains.exodus.entitystore.EntityId

/**
 * A *resolved* [YTDBEntityId]: a logical `(typeId, localId)` pair plus the physical record id
 * ([oId]) it was resolved to.
 *
 * Identity follows the universal `EntityId` contract — an id is identified by `(typeId, localId)`
 * alone — so [equals]/[hashCode]/[compareTo]/[toString] ignore [oId] and [schemaClassName] and
 * compare against *any* [EntityId] by its logical id. A resolved [RIDEntityId] is therefore
 * interchangeable with a logical [jetbrains.exodus.entitystore.PersistentEntityId] for the same
 * entity, including as a key in hash-based collections. That cross-type invariant — and the fact
 * that this id and `PersistentEntityId` independently reproduce the same `(typeId, localId)` hash
 * and ordering formulas — is pinned by `EntityIdContractTest`. Both parts must be non-negative.
 */
class RIDEntityId(
    private val classId: Int,
    private val localEntityId: Long,
    private val oId: RID,
    private val schemaClassName: String?
) : YTDBEntityId {

    init {
        if (classId < 0) throw ExodusException("TypeId can't be negative: $classId")
        if (localEntityId < 0) throw ExodusException("LocalId can't be negative: $localEntityId")
    }

    companion object {
        fun fromVertex(vertex: YTDBVertex): RIDEntityId {
            val oClass = vertex.requireSchemaClass()
            val classId = oClass.requireClassId()
            val localEntityId = vertex.requireLocalEntityId()
            return RIDEntityId(classId, localEntityId, vertex.id(), oClass.name)
        }
    }

    override fun getTypeId(): Int = classId

    override fun getLocalId(): Long = localEntityId

    override fun asOId(): RID = oId

    override fun getTypeName(): String = schemaClassName ?: "typeNotFound"

    // (typeId, localId) identity, compared against any EntityId regardless of concrete class. The
    // hash and ordering formulas mirror PersistentEntityId's (the only other EntityId impl) so the
    // two stay interchangeable — kept honest by EntityIdContractTest. The 32-bit shift on the int
    // typeId (not a Long shift) is part of the formula and must not change.
    override fun hashCode(): Int = ((classId shl 20).toLong() xor localEntityId).toInt()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntityId) return false
        return localEntityId == other.localId && classId == other.typeId
    }

    override fun toString(): String = "$classId-$localEntityId"

    override fun compareTo(other: EntityId): Int =
        if (classId != other.typeId) classId.compareTo(other.typeId)
        else localEntityId.compareTo(other.localId)
}
