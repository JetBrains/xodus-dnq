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

import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.record.ODirection
import com.orientechnologies.orient.core.record.OVertex
import jetbrains.exodus.entitystore.Entity

abstract class XdEntity(val vertex: OVertex) {
    val identity: ORID get() = vertex.identity
    val xdId: String get() = "${identity.clusterId}-${identity.clusterPosition}"

    abstract val entity: Entity

    val isNew: Boolean
        get() = identity.isNew

    val isRemoved: Boolean
        get() = !vertex.exists()

    open fun delete() {
        //TODO remove all edges
        vertex.delete()
    }

    fun reload() = vertex.reload<OVertex>()!!

    open fun beforeFlush() {}

    open fun destructor() {}

    open fun constructor() {}

    override fun equals(other: Any?): Boolean {
        return when {
            this === other -> true
            other !is XdEntity -> false
            else -> vertex.identity == other.vertex.identity
        }
    }

    override fun hashCode() = vertex.hashCode()

    override fun toString(): String {
        return "${vertex.schemaClass?.name} wrapper for $identity"
    }
}
