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

import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.*
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val min = ZonedDateTime.ofLocal(LocalDateTime.MIN, ZoneOffset.UTC, ZoneOffset.UTC)
private val max = ZonedDateTime.ofLocal(LocalDateTime.MAX, ZoneOffset.UTC, ZoneOffset.UTC)

object ZonedDateTimeBinding : XdCustomTypeBinding<ZonedDateTime>() {

    override val clazz = ZonedDateTime::class.java

    override fun write(stream: LightOutputStream, value: ZonedDateTime) {
        LocalDateTimeBinding.write(stream, value.toLocalDateTime())
        stream.writeString(value.zone.id)
    }

    override fun read(stream: ByteArrayInputStream): ZonedDateTime {
        val localDateTime = LocalDateTimeBinding.read(stream)
        val zoneId = StringBinding.BINDING.readObject(stream)
        return ZonedDateTime.of(localDateTime, ZoneId.of(zoneId))
    }

    override fun min(): ZonedDateTime = min
    override fun max(): ZonedDateTime = max
    override fun prev(value: ZonedDateTime): ZonedDateTime = value.minusNanos(1)
    override fun next(value: ZonedDateTime): ZonedDateTime = value.plusNanos(1)
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.ZonedDateTime?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable ZonedDateTime property with database name `createdAt`.
 * ```
 * var createdAt by xdZonedDateTimeProp()
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
fun <R : XdEntity> xdZonedDateTimeProp(dbName: String? = null, constraints: Constraints<R, ZonedDateTime?>? = null) =
        xdNullableCachedProp(dbName, ZonedDateTimeBinding, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.ZonedDateTime`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null ZonedDateTime property with database name `createdAt`.
 * ```
 * var createdAt by xdRequiredZonedDateTimeProp()
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
fun <R : XdEntity> xdRequiredZonedDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, ZonedDateTime?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, ZonedDateTimeBinding, DEFAULT_REQUIRED)
