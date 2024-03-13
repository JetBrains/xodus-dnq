/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class XdConstrainedProperty<in R, T>(
        open val dbPropertyName: String?,
        open val constraints: List<PropertyConstraint<T?>>,
        open val requirement: XdPropertyRequirement,
        open val propertyType: PropertyType) : ReadOnlyProperty<R, T> {

    abstract fun isDefined(thisRef: R, property: KProperty<*>): Boolean

    internal val KProperty<*>.dbName get() = dbPropertyName ?: this.name
}
