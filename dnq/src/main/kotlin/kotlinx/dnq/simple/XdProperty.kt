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

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import kotlinx.dnq.simple.custom.type.XdCustomTypeProperty
import kotlinx.dnq.util.reattachAndGetPrimitiveValue
import kotlinx.dnq.util.reattachAndSetPrimitiveValue
import kotlin.reflect.KProperty

class XdProperty<in R : XdEntity, T : Comparable<T>>(
        val clazz: Class<T>,
        dbPropertyName: String?,
        constraints: List<PropertyConstraint<T?>>,
        requirement: XdPropertyRequirement,
        val default: (R, KProperty<*>) -> T,
        override val binding: XdCustomTypeBinding<T>? = null) :
        XdCustomTypeProperty<T>,
        XdMutableConstrainedProperty<R, T>(
                dbPropertyName,
                constraints,
                requirement,
                PropertyType.PRIMITIVE
        ) {

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        val result: T? = thisRef.reattachAndGetPrimitiveValue(property.dbName)
        if (isBoolean) {
            return (result != null) as T
        }
        return result ?: default(thisRef, property)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        thisRef.reattachAndSetPrimitiveValue(property.dbName, value, clazz)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        if (isBoolean) return true
        return thisRef.reattachAndGetPrimitiveValue<T>(property.dbName) != null
    }

    val isBoolean: Boolean get() = clazz == java.lang.Boolean::class.java
}
