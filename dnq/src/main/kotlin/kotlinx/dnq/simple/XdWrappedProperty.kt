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

import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdWrappedProperty<in R : XdEntity, B, T>(
        val wrapped: XdMutableConstrainedProperty<R, B>,
        val wrap: (B) -> T,
        val unwrap: (T) -> B) :
        XdMutableConstrainedProperty<R, T>(
                null,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.PRIMITIVE) {

    override val dbPropertyName: String?
        get() = wrapped.dbPropertyName

    override val requirement: XdPropertyRequirement
        get() = wrapped.requirement

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        return wrap(wrapped.getValue(thisRef, property))
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        wrapped.setValue(thisRef, property, unwrap(value))
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = wrapped.isDefined(thisRef, property)
}
