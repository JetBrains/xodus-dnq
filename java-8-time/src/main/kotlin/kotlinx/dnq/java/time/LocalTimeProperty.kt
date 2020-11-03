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

import jetbrains.exodus.bindings.ByteBinding
import jetbrains.exodus.bindings.IntegerBinding
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.*
import kotlinx.dnq.simple.custom.type.*
import java.io.ByteArrayInputStream
import java.time.LocalTime

object LocalTimeBinding : XdCustomTypeBinding<LocalTime>() {

    override val clazz = LocalTime::class.java

    override fun write(stream: LightOutputStream, value: LocalTime) {
        ByteBinding.BINDING.writeObject(stream, value.hour.toByte())
        ByteBinding.BINDING.writeObject(stream, value.minute.toByte())
        ByteBinding.BINDING.writeObject(stream, value.second.toByte())
        IntegerBinding.BINDING.writeObject(stream, value.nano)
    }

    override fun read(stream: ByteArrayInputStream): LocalTime {
        val hour = ByteBinding.BINDING.readObject(stream)
        val minute = ByteBinding.BINDING.readObject(stream)
        val second = ByteBinding.BINDING.readObject(stream)
        val nano = IntegerBinding.BINDING.readObject(stream)
        return LocalTime.of(hour.toInt(), minute.toInt(), second.toInt(), nano)
    }

    override fun min(): LocalTime = LocalTime.MIN
    override fun max(): LocalTime = LocalTime.MAX
    override fun prev(value: LocalTime): LocalTime = value.minusNanos(1)
    override fun next(value: LocalTime): LocalTime = value.plusNanos(1)
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.LocalTime?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable LocalTime property with database name `createdAt`.
 * ```
 * var createdAt by xdLocalTimeProp()
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
fun <R : XdEntity> xdLocalTimeProp(dbName: String? = null, constraints: Constraints<R, LocalTime?>? = null) =
        xdNullableCachedProp(dbName, LocalTimeBinding, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `java.time.LocalTime`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null LocalTime property with database name `createdAt`.
 * ```
 * var createdAt by xdRequiredLocalTimeProp()
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
fun <R : XdEntity> xdRequiredLocalTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, LocalTime?>? = null) =
        xdCachedProp(dbName, constraints, true, unique, LocalTimeBinding, DEFAULT_REQUIRED)
