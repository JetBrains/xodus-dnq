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
import jetbrains.exodus.entitystore.AbstractEntityId

/**
 * A *resolved* [YTDBEntityId]: a logical `(typeId, localId)` pair (validated and compared by
 * [AbstractEntityId]) plus the physical record id ([oId]) it was resolved to. Equality and hashing
 * intentionally ignore [oId] and [schemaClassName] — an id is identified by `(typeId, localId)`
 * alone, so a resolved id is interchangeable with the logical id types for the same entity.
 */
class RIDEntityId(
    classId: Int,
    localEntityId: Long,
    private val oId: RID,
    private val schemaClassName: String?
) : AbstractEntityId(classId, localEntityId), YTDBEntityId {

    companion object {
        fun fromVertex(vertex: YTDBVertex): RIDEntityId {
            val oClass = vertex.requireSchemaClass()
            val classId = oClass.requireClassId()
            val localEntityId = vertex.requireLocalEntityId()
            return RIDEntityId(classId, localEntityId, vertex.id(), oClass.name)
        }
    }

    override fun asOId(): RID {
        return oId
    }

    override fun getTypeName(): String {
        return schemaClassName ?: "typeNotFound"
    }
}
