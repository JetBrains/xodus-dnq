/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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

    /**
     * Whether a store that indexes every simple property should also index this one. `true` by default.
     *
     * Set it to `false` for properties whose values are unbounded: on YouTrackDB a B-tree index key may not
     * exceed `BTREE_MAX_KEY_SIZE` (~2457 bytes with the default 8 KB page), and a larger value fails the write
     * with `TooBigIndexKeyException`. An unindexed property stays a normal property - readable, writable and
     * usable as a query predicate; queries scan instead of probing an index.
     */
    open val isAutoIndexed: Boolean get() = true

    abstract fun isDefined(thisRef: R, property: KProperty<*>): Boolean

    internal val KProperty<*>.dbName get() = dbPropertyName ?: this.name
}
