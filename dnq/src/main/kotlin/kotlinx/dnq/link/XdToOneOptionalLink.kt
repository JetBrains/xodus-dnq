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
import kotlin.reflect.KProperty

class XdToOneOptionalLink<in R : XdEntity, T : XdEntity>(
        oppositeEntityType: XdEntityType<T>,
        dbPropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy
) : ScalarOptionalLink<R, T>, XdLink<R, T>(
        oppositeEntityType,
        dbPropertyName,
        null,
        AssociationEndCardinality._0_1,
        AssociationEndType.DirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): T? {
        val entity = thisRef.reload().getVertices(ODirection.OUT, property.dbName).firstOrNull()
        return entity?.let {
            oppositeEntityType.wrap(it)
        }
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T?) {
        val oldValue = thisRef.vertex.getVertices(ODirection.OUT, property.dbName).firstOrNull()
        if (oldValue != null) {
            thisRef.vertex.deleteEdge(oldValue, property.dbName)
        }
        if (value != null) {
            thisRef.vertex.addEdge(value.vertex, property.dbName)
        }
        thisRef.vertex.save<OVertex>()
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.vertex.edgeNames.contains(property.dbName)
    }
}
