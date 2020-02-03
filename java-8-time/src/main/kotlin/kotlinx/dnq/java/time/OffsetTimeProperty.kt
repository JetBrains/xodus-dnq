/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
package kotlinx.dnq.java.time

import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.*
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import java.io.ByteArrayInputStream
import java.time.OffsetTime

object OffsetTimeBinding : XdCustomTypeBinding<OffsetTime>() {

    override fun write(stream: LightOutputStream, value: OffsetTime) {
        LocalTimeBinding.write(stream, value.toLocalTime())
        ZoneOffsetBinding.write(stream, value.offset)
    }

    override fun read(stream: ByteArrayInputStream): OffsetTime {
        val time = LocalTimeBinding.read(stream)
        val offset = ZoneOffsetBinding.read(stream)
        return OffsetTime.of(time, offset)
    }

    override fun min(): OffsetTime = OffsetTime.MIN
    override fun max(): OffsetTime = OffsetTime.MAX
    override fun prev(value: OffsetTime): OffsetTime = value.minusNanos(1)
    override fun next(value: OffsetTime): OffsetTime = value.plusNanos(1)
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.OffsetTime?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable OffsetTime property with database name `createdAt`.
 * ```
 * var createdAt by xdOffsetTimeProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see isAfter()
 * @see isBefore()
 */
fun <R : XdEntity> xdOffsetTimeProp(dbName: String? = null, constraints: Constraints<R, OffsetTime?>? = null) =
        xdNullableCachedProp(dbName, OffsetTimeBinding, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.OffsetTime`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null OffsetTime property with database name `createdAt`.
 * ```
 * var createdAt by xdRequiredOffsetTimeProp()
 * ```
 *
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see isAfter()
 * @see isBefore()
 */
fun <R : XdEntity> xdRequiredOffsetTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, OffsetTime?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, OffsetTimeBinding, DEFAULT_REQUIRED)
