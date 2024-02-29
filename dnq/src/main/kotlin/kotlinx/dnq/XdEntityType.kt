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

import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.db.OrientDB
import com.orientechnologies.orient.core.record.OVertex
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.XdQueryImpl
import kotlinx.dnq.store.container.StoreContainer
import kotlin.streams.asSequence


abstract class XdEntityType<out T : XdEntity>(val storeContainer: StoreContainer) {
    abstract val entityType: String
    val database: OrientDB
        get() = storeContainer.database

    fun all(): XdQuery<T> {
        val query = "SELECT FROM $entityType"
        val database = database
        val resultSet = database.execute(query)
        return XdQueryImpl(
            object : Iterable<OVertex> {
                override fun iterator(): Iterator<OVertex> {
                    return resultSet.vertexStream()    .iterator()
                }
            }, this
        )
    }


    open fun new(init: (T.() -> Unit) = {}): T {
        val session = ODatabaseSession.getActiveSession()
        val vertex = session.newVertex(entityType)
        return vertex.toXd<T>().apply {
            constructor()
            init()
        }
    }

    open fun wrap(vertex: OVertex) = vertex.toXd<T>()
}
