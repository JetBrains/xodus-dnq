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
package kotlinx.dnq.java.time

import jetbrains.exodus.bindings.IntegerBinding
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.Constraints
import kotlinx.dnq.simple.DEFAULT_REQUIRED
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import kotlinx.dnq.simple.xdCachedProp
import kotlinx.dnq.simple.xdNullableCachedProp
import java.io.ByteArrayInputStream
import java.time.ZoneOffset

object ZoneOffsetBinding : XdCustomTypeBinding<ZoneOffset>() {

    override val clazz = ZoneOffset::class.java

    override fun write(stream: LightOutputStream, value: ZoneOffset) {
        IntegerBinding.BINDING.writeObject(stream, -value.totalSeconds)
    }

    override fun read(stream: ByteArrayInputStream): ZoneOffset {
        return ZoneOffset.ofTotalSeconds(-IntegerBinding.BINDING.readObject(stream))
    }

    override fun min(): ZoneOffset = ZoneOffset.MAX

    override fun max(): ZoneOffset = ZoneOffset.MIN

    override fun prev(value: ZoneOffset): ZoneOffset {
        return ZoneOffset.ofTotalSeconds(value.totalSeconds + 1)
    }

    override fun next(value: ZoneOffset): ZoneOffset {
        return ZoneOffset.ofTotalSeconds(value.totalSeconds - 1)
    }
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.ZonedOffset?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable ZonedOffset property with database name `timeZone`.
 * ```
 * var timeZone by xdZonedOffsetProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdZoneOffsetProp(dbName: String? = null, constraints: Constraints<R, ZoneOffset?>? = null) =
        xdNullableCachedProp(dbName, ZoneOffsetBinding, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.ZonedOffset`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null ZonedOffset property with database name `timeZone`.
 * ```
 * var timeZone by xdRequiredZonedOffsetProp()
 * ```
 *
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdRequiredZoneOffsetProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, ZoneOffset?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, ZoneOffsetBinding, DEFAULT_REQUIRED)
