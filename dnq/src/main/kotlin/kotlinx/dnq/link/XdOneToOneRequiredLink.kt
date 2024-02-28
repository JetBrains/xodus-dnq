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
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.util.reattach
import kotlinx.dnq.util.reattachAndGetLink
import kotlinx.dnq.util.threadSessionOrThrow
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1


class XdOneToOneRequiredLink<R : XdEntity, T : XdEntity>(
        oppositeEntityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>,
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
        val entity = thisRef.reload().getVertices(ODirection.OUT, property.dbName).firstOrNull() ?: throw RequiredPropertyUndefinedException(thisRef, property)
        return oppositeEntityType.wrap(entity)
    }


    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        val oldValue = thisRef.vertex.getVertices(ODirection.OUT, property.dbName).firstOrNull()
        oldValue?.let {
            thisRef.vertex.deleteEdge(oldValue, property.dbName)
            oldValue.deleteEdge(thisRef.vertex, property.oppositeDbName)
            oldValue.save<OVertex>()
        }
        thisRef.vertex.addEdge(value.vertex, property.dbName)
        value.vertex.addEdge(thisRef.vertex, property.oppositeDbName)
        thisRef.vertex.save<OVertex>()
        value.vertex.save<OVertex>()
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.reload().getEdges(ODirection.OUT, property.dbName).iterator().hasNext()
    }
}

