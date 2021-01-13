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
package kotlinx.dnq.java.time

import jetbrains.exodus.bindings.IntegerBinding
import jetbrains.exodus.bindings.ShortBinding
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.*
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import java.io.ByteArrayInputStream
import java.time.LocalDate

object LocalDateBinding : XdCustomTypeBinding<LocalDate>() {

    override val clazz = LocalDate::class.java

    override fun write(stream: LightOutputStream, value: LocalDate) {
        IntegerBinding.BINDING.writeObject(stream, value.year)
        ShortBinding.BINDING.writeObject(stream, value.monthValue.toShort())
        ShortBinding.BINDING.writeObject(stream, value.dayOfMonth.toShort())
    }

    override fun read(stream: ByteArrayInputStream): LocalDate {
        val year = IntegerBinding.BINDING.readObject(stream)
        val month = ShortBinding.BINDING.readObject(stream)
        val day = ShortBinding.BINDING.readObject(stream)
        return LocalDate.of(year, month.toInt(), day.toInt())
    }

    override fun min(): LocalDate = LocalDate.MIN
    override fun max(): LocalDate = LocalDate.MAX
    override fun prev(value: LocalDate): LocalDate = value.minusDays(1)
    override fun next(value: LocalDate): LocalDate = value.plusDays(1)
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.LocalDate?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable LocalDate property with database name `createdAt`.
 * ```
 * var createdAt by xdLocalDateProp()
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
fun <R : XdEntity> xdLocalDateProp(dbName: String? = null, constraints: Constraints<R, LocalDate?>? = null) =
        xdNullableCachedProp(dbName, LocalDateBinding, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.LocalDate`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null LocalDate property with database name `createdAt`.
 * ```
 * var createdAt by xdRequiredLocalDateProp()
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
fun <R : XdEntity> xdRequiredLocalDateProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, LocalDate?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, LocalDateBinding, DEFAULT_REQUIRED)
