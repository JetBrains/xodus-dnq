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
import com.orientechnologies.orient.core.record.OVertex
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class XdManyToManyLink<R : XdEntity, T : XdEntity>(
        oppositeEntityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String?,
        dbOppositePropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy,
        required: Boolean
) : VectorLink<R, T>, XdLink<R, T>(
        oppositeEntityType,
        dbPropertyName,
        dbOppositePropertyName,
        if (required) AssociationEndCardinality._1_n else AssociationEndCardinality._0_n,
        AssociationEndType.UndirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): XdMutableQuery<T> {
        return object : XdMutableQuery<T>(oppositeEntityType) {
            override val entityIterable: Iterable<OVertex>
                get() = thisRef.reload().getVertices(ODirection.OUT, property.dbName)


            override fun add(entity: T) {
                thisRef.vertex.addEdge(entity.vertex, property.dbName)
                entity.vertex.addEdge(thisRef.vertex, property.oppositeDbName)
            }

            override fun remove(entity: T) {
                removeLinkToVertex(entity.vertex)
            }

            private fun removeLinkToVertex(likedVertex:OVertex){
                thisRef.vertex.deleteEdge(likedVertex, property.dbName)
                likedVertex.deleteEdge(thisRef.vertex, property.oppositeDbName)
           }

            override fun clear() {
                thisRef.vertex.getVertices(ODirection.OUT, property.dbName).forEach {
                    removeLinkToVertex(it)
                }
            }

        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.reload().getEdges(ODirection.OUT, property.dbName).iterator().hasNext()
    }
}
