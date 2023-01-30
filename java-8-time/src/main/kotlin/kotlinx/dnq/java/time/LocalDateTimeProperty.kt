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

import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.*
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

object LocalDateTimeBinding : XdCustomTypeBinding<LocalDateTime>() {

    override val clazz = LocalDateTime::class.java

    override fun write(stream: LightOutputStream, value: LocalDateTime) {
        LocalDateBinding.write(stream, value.toLocalDate())
        LocalTimeBinding.write(stream, value.toLocalTime())
    }

    override fun read(stream: ByteArrayInputStream): LocalDateTime {
        val date = LocalDateBinding.read(stream)
        val time = LocalTimeBinding.read(stream)
        return LocalDateTime.of(date, time)
    }

    override fun min(): LocalDateTime = LocalDateTime.MIN
    override fun max(): LocalDateTime = LocalDateTime.MAX
    override fun prev(value: LocalDateTime): LocalDateTime = value.minusNanos(1)
    override fun next(value: LocalDateTime): LocalDateTime = value.plusNanos(1)
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.LocalDateTime?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable LocalDateTime property with database name `createdAt`.
 * ```
 * var createdAt by xdLocalDateTimeProp()
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
fun <R : XdEntity> xdLocalDateTimeProp(dbName: String? = null, constraints: Constraints<R, LocalDateTime?>? = null) =
        xdNullableCachedProp(dbName, LocalDateTimeBinding, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.LocalDateTime`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null LocalDateTime property with database name `createdAt`.
 * ```
 * var createdAt by xdRequiredLocalDateTimeProp()
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
fun <R : XdEntity> xdRequiredLocalDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, LocalDateTime?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, LocalDateTimeBinding, DEFAULT_REQUIRED)
