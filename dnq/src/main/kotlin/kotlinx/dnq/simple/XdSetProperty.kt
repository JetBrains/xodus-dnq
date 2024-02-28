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
package kotlinx.dnq.simple

import com.orientechnologies.orient.core.record.ORecord
import jetbrains.exodus.bindings.ComparableSet
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdSetProperty<in R : XdEntity, T : Comparable<T>>(
    dbPropertyName: String?,
    val clazz: Class<T>
) :
    XdMutableConstrainedProperty<R, Set<T>>(
        dbPropertyName,
        emptyList(),
        XdPropertyRequirement.OPTIONAL,
        PropertyType.PRIMITIVE
    ) {

    override fun getValue(thisRef: R, property: KProperty<*>): Set<T> {
        val value = thisRef.reload().getProperty<ComparableSet<T>>(property.dbName)
        return value?.toSet() ?: emptySet()
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: Set<T>) {
        val comparableSet = value
                .takeIf { it.isNotEmpty() }
                ?.let { ComparableSet(it) }
        thisRef.vertex.setProperty(property.dbName, comparableSet)
        thisRef.vertex.save<ORecord>()
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.vertex.hasProperty(property.dbName)
    }
}
