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
package kotlinx.dnq.link

import com.orientechnologies.orient.core.record.ODirection
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.toXd
import kotlinx.dnq.util.threadSessionOrThrow
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

class XdManyToOneRequiredLink<R : XdEntity, T : XdEntity>(
        oppositeEntityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String?,
        dbOppositePropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy
) : ScalarRequiredLink<R, T>, XdLink<R, T>(
        oppositeEntityType,
        dbPropertyName,
        dbOppositePropertyName,
        AssociationEndCardinality._1,
        AssociationEndType.UndirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        val value = thisRef.reload().getVertices(ODirection.OUT, property.dbName).firstOrNull()?: throw RequiredPropertyUndefinedException(thisRef, property)
        return value.toXd()
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        val vertex = thisRef.vertex
        val currentLink = thisRef.vertex.getVertices(ODirection.OUT, property.dbName).firstOrNull()
        currentLink?.let {
            vertex.deleteEdge(currentLink, property.dbName)
            currentLink.deleteEdge(vertex, property.oppositeDbName)
        }
        vertex.addEdge(value.vertex, property.dbName)
        value.vertex.addEdge(vertex, property.oppositeDbName)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.reload().getEdges(ODirection.OUT, property.dbName).iterator().hasNext()
    }
}

