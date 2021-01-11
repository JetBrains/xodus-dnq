/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.LinkEqual
import jetbrains.exodus.query.TreeKeepingEntityIterable
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.isNotEmpty
import kotlinx.dnq.util.reattach
import kotlinx.dnq.util.threadSessionOrThrow
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
        return object : XdMutableQuery<T>(oppositeEntityType) {
            override val entityIterable: Iterable<Entity>
                get() = try {
                    val queryEngine = oppositeEntityType.entityStore.queryEngine
                    val oppositeType = oppositeEntityType.entityType
                    if (queryEngine.modelMetaData.getEntityMetaData(oppositeType)?.hasSubTypes() == true) {
                        thisRef.reattach().getLinks(property.dbName)
                    } else {
                        TreeKeepingEntityIterable(null, oppositeType, LinkEqual(oppositeField.oppositeDbName, thisRef.reattach()), queryEngine)
                    }
                } catch (_: UnsupportedOperationException) {
                    // to support weird FakeTransientEntity
                    thisRef.reattach().getLinks(property.dbName)
                }

            override fun add(entity: T) {
                val session = thisRef.threadSessionOrThrow
                thisRef.reattach(session).addChild(property.dbName, oppositeField.oppositeDbName, entity.reattach(session))
            }

            override fun remove(entity: T) {
                entity.reattach().removeFromParent(property.dbName, oppositeField.oppositeDbName)
            }

            override fun clear() {
                thisRef.reattach().clearChildren(property.dbName)
            }
        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return getValue(thisRef, property).isNotEmpty
    }
}