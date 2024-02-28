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

open class XdParentToManyChildrenLink<R : XdEntity, T : XdEntity>(
        oppositeEntityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>,
        dbPropertyName: String?,
        dbOppositePropertyName: String?,
        required: Boolean
) : VectorLink<R, T>, XdLink<R, T>(
        oppositeEntityType,
        dbPropertyName,
        dbOppositePropertyName,
        if (required) AssociationEndCardinality._1_n else AssociationEndCardinality._0_n,
        AssociationEndType.ParentEnd,
        onDelete = OnDeletePolicy.CASCADE,
        onTargetDelete = OnDeletePolicy.CLEAR
) {

    override fun getValue(thisRef: R, property: KProperty<*>): XdMutableQuery<T> {
        val vertex = thisRef.reload()
        return object : XdMutableQuery<T>(oppositeEntityType) {
            override val entityIterable: Iterable<OVertex> get() {
                return vertex.getVertices(ODirection.OUT, property.dbName)
                // TreeKeepingEntityIterable(null, oppositeType, LinkEqual(oppositeField.oppositeDbName, thisRef.reattach()), queryEngine)
            }

            override fun add(entity: T) {
                vertex.addEdge(entity.vertex, property.dbName)
                entity.vertex.addEdge(entity.vertex, property.oppositeDbName)

            }

            override fun remove(entity: T) {
                vertex.deleteEdge(entity.vertex, property.dbName)
                //Do we really need it?
                entity.vertex.delete()
            }

            override fun clear() {
                vertex.getEdges(ODirection.OUT, property.dbName).forEach { edge ->
                    vertex.deleteEdge(edge.to, property.dbName)
                    edge.to.delete()
                }
            }
        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.vertex.edgeNames.contains(property.dbName)
    }
}
