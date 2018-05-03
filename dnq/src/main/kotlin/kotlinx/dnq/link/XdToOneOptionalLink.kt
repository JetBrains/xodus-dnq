/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.link

import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.util.reattach
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
        return thisRef.reattach().getLink(property.dbName)?.let { value ->
            oppositeEntityType.wrap(value)
        }
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T?) {
        thisRef.reattach().setToOne(property.dbName, value?.reattach())
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}