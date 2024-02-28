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
package kotlinx.dnq

import kotlinx.dnq.sequence.XdSequenceProperty
import kotlinx.dnq.simple.*
import kotlinx.dnq.store.container.StoreContainer
import kotlinx.dnq.util.XdPropertyCachedProvider
import org.joda.time.DateTime
import kotlin.reflect.KProperty

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Byte`.
 *
 * If persistent property value is not defined in database the property returns `0`.
 *
 * **Sample**: optional non-negative Byte property with database name `age`.
 * ```
 * var age by xdByteProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdByteProp(dbName: String? = null, constraints: Constraints<R, Byte?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Byte`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property returns `0`.
 *
 * **Sample**: Unique required Byte property with database name `id`.
 * ```
 * var id by xdRequiredByteProp(unique = true)
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdRequiredByteProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Byte?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Byte?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: non-negative nullable Byte property with database name `salary`.
 * ```
 * var salary by xdNullableByteProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdNullableByteProp(dbName: String? = null, constraints: Constraints<R, Byte?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Short`.
 *
 * If persistent property value is not defined in database the property returns `0`.
 *
 * **Sample**: optional non-negative Short property with database name `age`.
 * ```
 * var age by xdShortProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdShortProp(dbName: String? = null, constraints: Constraints<R, Short?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Short`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property returns `0`.
 *
 * **Sample**: Unique required Short property with database name `id`.
 * ```
 * var id by xdRequiredShortProp(unique = true)
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdRequiredShortProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Short?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Short?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: non-negative nullable Short property with database name `salary`.
 * ```
 * var salary by xdNullableShortProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdNullableShortProp(dbName: String? = null, constraints: Constraints<R, Short?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Int`.
 *
 * If persistent property value is not defined in database the property returns `0`.
 *
 * **Sample 1**: optional non-negative Int property with database name `age`.
 * ```
 * var age: xdIntProp { min(0) }
 * ```
 * **Sample 2**: optional Int property with database name `grade`.
 * ```
 * var rank: xdIntProp(dbName = "grade")
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdIntProp(dbName: String? = null, constraints: Constraints<R, Int?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Int`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property returns `0`.
 *
 * **Sample 1**: required non-negative Int property with database name `age`.
 * ```
 * var age: xdRequiredIntProp { min(0) }
 * ```
 * **Sample 2**: required Int property with database name `grade`.
 * ```
 * var rank: xdRequiredIntProp(dbName = "grade")
 * ```
 * **Sample 3**: unique required Int property with database name `id`.
 * ```
 * var id: xdRequiredIntProp(unique = true)
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdRequiredIntProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Int?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Int?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: non-negative nullable Int property with database name `salary`.
 * ```
 * var salary by xdNullableIntProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdNullableIntProp(dbName: String? = null, constraints: Constraints<R, Int?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Long`.
 *
 * If persistent property value is not defined in database the property returns `0`.
 *
 * **Sample**: optional non-negative Long property with database name `salary`.
 * ```
 * var salary: xdLongProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0L }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Long`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property returns `0`.
 *
 * **Sample 1**: unique required Long property with database name `id`.
 * ```
 * var id: xdRequiredLongProp(unique = true)
 * ```
 *
 * **Sample 2**: optional non-negative Long property with database name `salary`.
 * ```
 * var salary: xdRequiredLongProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Long?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0L }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Long?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: non-negative nullable Long property with database name `salary`.
 * ```
 * var salary by xdNullableLongProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdNullableLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Float`.
 *
 * If persistent property value is not defined in database the property returns `0F`.
 *
 * **Sample**: optional non-negative Float property with database name `salary`.
 * ```
 * var salary: xdFloatProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdFloatProp(dbName: String? = null, constraints: Constraints<R, Float?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0F }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Float`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property returns `0F`.
 *
 * **Sample**: unique required Float property with database name `seed`.
 * ```
 * var seed by xdRequiredFloatProp(unique = true)
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdRequiredFloatProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Float?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0F }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Float?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: non-negative nullable Float property with database name `salary`.
 * ```
 * var salary by xdNullableFloatProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdNullableFloatProp(dbName: String? = null, constraints: Constraints<R, Float?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Double`.
 *
 * If persistent property value is not defined in database the property returns `0.0`.
 *
 * **Sample**: optional non-negative Double property with database name `salary`.
 * ```
 * var salary: xdDoubleProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdDoubleProp(dbName: String? = null, constraints: Constraints<R, Double?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0.0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Double`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property returns `0.0`.
 *
 * **Sample**: unique required Double property with database name `seed`.
 * ```
 * var seed by xdRequiredDoubleProp(unique = true)
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdRequiredDoubleProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Double?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0.0 }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Double?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: non-negative nullable Double property with database name `salary`.
 * ```
 * var salary by xdNullableDoubleProp { min(0) }
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see min()
 * @see max()
 */
fun <R : XdEntity> xdNullableDoubleProp(dbName: String? = null, constraints: Constraints<R, Double?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Boolean`.
 *
 * If persistent property value is not defined in database the property returns `false`.
 *
 * **Sample**: optional boolean property with database name `anonymous`.
 * ```
 * var isGuest by xdBooleanProp(dbName = "anonymous")
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdBooleanProp(dbName: String? = null, constraints: Constraints<R, Boolean?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> false }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Boolean?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: nullable Boolean property with database name `isFemale`.
 * ```
 * var isFemale by xdNullableBooleanProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
@Deprecated("Since 2.0.0 works as xdBooleanProp()", ReplaceWith("xdBooleanProp()"), DeprecationLevel.ERROR)
fun <R : XdEntity> xdNullableBooleanProp(dbName: String? = null, constraints: Constraints<R, Boolean?>? = null) =
        xdNullableCachedProp(dbName, constraints = constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `String?`.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable String property with database name `lastName`.
 * ```
 * var lastName by xdStringProp(trimmed = true)
 * ```
 *
 * @param trimmed if `true` value is trimmed when set, i.e. when you assign a value to such property, all leading
 *        and trailing spaces are removed. By default is `false`.
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see regex()
 * @see email()
 * @see containsNone()
 * @see alpha()
 * @see numeric()
 * @see alphaNumeric()
 * @see url()
 * @see uri()
 * @see length()
 */
fun <R : XdEntity> xdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: Constraints<R, String?>? = null) =
        XdPropertyCachedProvider {
            val prop = xdNullableProp(dbName, constraints)
            if (trimmed) {
                prop.wrap(wrap = { it }, unwrap = { it?.trim() })
            } else {
                prop
            }
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `String`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined. Note that
 * Xodus treats empty string as `null`. So empty string does not pass require check.
 *
 * **Sample**: required unique String property with database name `uuid`.
 * ```
 * var uuid by xdRequiredStringProp(unique=true)
 * ```
 *
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param trimmed if `true` value is trimmed when set, i.e. when you assign a value to such property, all leading
 *        and trailing spaces are removed. By default is `false`.
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param default closure that returns default value for the Kotlin-property while persistent property value is
 *        not defined in database. By default throws `RequiredPropertyUndefinedException` on get.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see regex()
 * @see email()
 * @see containsNone()
 * @see alpha()
 * @see numeric()
 * @see alphaNumeric()
 * @see url()
 * @see uri()
 * @see length()
 */
fun <R : XdEntity> xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: ((R, KProperty<*>) -> String)? = null,
        constraints: Constraints<R, String?>? = null
) = XdPropertyCachedProvider {
    val prop = xdProp(
            dbName,
            constraints,
            require = true,
            unique = unique,
            default = default ?: DEFAULT_REQUIRED
    )
    if (trimmed) {
        prop.wrap({ it }, String::trim)
    } else {
        prop
    }
}

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `String`.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined. Note that
 * Xodus treats empty string as `null`. So empty string does not pass require check.
 *
 * **Sample**: required String property with database name `lastName` with default value Doe.
 * ```
 * var lastName by xdRequiredStringProp(default="Doe")
 * ```
 *
 * @param unique if `true` Xodus-DNQ checks on flush uniqueness of the property value among instances of
 *        the persistent class. By default is `false`.
 * @param trimmed if `true` value is trimmed when set, i.e. when you assign a value to such property, all leading
 *        and trailing spaces are removed. By default is `false`.
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param default default value for the Kotlin-property while persistent property value is
 *        not defined in database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see regex()
 * @see email()
 * @see containsNone()
 * @see alpha()
 * @see numeric()
 * @see alphaNumeric()
 * @see url()
 * @see uri()
 * @see length()
 */
fun <R : XdEntity> xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: String,
        constraints: Constraints<R, String?>? = null
) = xdRequiredStringProp(unique, trimmed, dbName, { _, _ -> default }, constraints)

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `org.joda.time.DateTime?`.
 * Xodus does not have built-in support for date-time simple properties. This property is actually wrapping
 * nullable Long property and is storing unix epoch timestamp.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable DateTime property with database name `createdAt`.
 * ```
 * var createdAt by xdDateTimeProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @param constraints closure that has `PropertyConstraintsBuilder` as a receiver. Enables set up of property
 *        constraints that will be checked before transaction flush.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 * @see isAfter()
 * @see isBefore()
 * @see past()
 * @see future()
 */
fun <R : XdEntity> xdDateTimeProp(dbName: String? = null, constraints: Constraints<R, DateTime?>? = null) =
        XdPropertyCachedProvider {
            XdNullableProperty<R, Long>(
                    Long::class.java,
                    dbName,
                    constraints.collect().wrap<R, Long, DateTime> { DateTime(it) }
            ).wrap({ it?.let { DateTime(it) } }, { it?.millis })
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `org.joda.time.DateTime`.
 * Xodus does not have built-in support for date-time simple properties. This property is actually wrapping
 * nullable Long property and is storing unix epoch timestamp.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null DateTime property with database name `createdAt`.
 * ```
 * var createdAt by xdRequiredDateTimeProp()
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
 * @see past()
 * @see future()
 */
fun <R : XdEntity> xdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, DateTime?>? = null) =
        XdPropertyCachedProvider {
            XdProperty<R, Long>(
                    Long::class.java,
                    dbName,
                    constraints.collect().wrap<R, Long, DateTime> { DateTime(it) },
                    if (unique) XdPropertyRequirement.UNIQUE else XdPropertyRequirement.REQUIRED,
                    DEFAULT_REQUIRED
            ).wrap({ DateTime(it) }, { it.millis })
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `InputStream?`.
 * Xodus stores massive blobs as separate files on disk. Xodus also does not build indices for blob properties.
 * So indices for blob properties do not consume memory but you cannot filter or sort `XdQuery` by this property.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable InputStream property with database name `image`.
 * ```
 * var image by xdBlobProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdBlobProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdNullableBlobProperty<R>(dbName)
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `InputStream`.
 * Xodus stores massive blobs as separate files on disk. Xodus also does not build indices for blob properties.
 * So indices for blob properties do not consume memory but you cannot filter or sort `XdQuery` by this property.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null InputStream property with database name `image`.
 * ```
 * var image by xdRequiredBlobProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdRequiredBlobProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdBlobProperty<R>(dbName)
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `String?`.
 * Xodus stores massive blobs as separate files on disk. Xodus also does not build indices for blob properties.
 * So indices for blob properties do not consume memory but you cannot filter or sort `XdQuery` by this property.
 *
 * If persistent property value is not defined in database the property returns `null`.
 *
 * **Sample**: optional nullable String property with database name `description`.
 * ```
 * var description by xdBlobStringProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdBlobStringProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdNullableTextProperty<R>(dbName)
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `String`.
 * Xodus stores massive blobs as separate files on disk. Xodus also does not build indices for blob properties.
 * So indices for blob properties do not consume memory but you cannot filter or sort `XdQuery` by this property.
 *
 * Related persistent property is required, i.e. Xodus-DNQ checks on flush that property value is defined.
 *
 * While persistent property value is not defined in database the property throws `RequiredPropertyUndefinedException` on get.
 *
 * **Sample**: required not-null String property with database name `description`.
 * ```
 * var description by xdRequiredBlobStringProp()
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
fun <R : XdEntity> xdRequiredBlobStringProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdTextProperty<R>(dbName)
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `Set<T>`.
 * Xodus builds index for every element of the set. So you can query entity by any element of the set.
 *
 * If persistent property value is not defined in database the property returns `emptySet()`.
 *
 * **Sample**: set of strings property with database name `tags`.
 * ```
 * class XdPost(entity: Entity): XdEntity(entity) {
 *     companion object: XdNaturalEntityType<XdPost>()
 *     var tags by xdSetProp<XdPost, String>()
 * }
 *
 * // Finds all posts that contains word "Kotlin" among the value of tags property.
 * XdPost.query(XdPost::tags contains "Kotlin")
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
inline fun <R : XdEntity, reified T : Comparable<T>> xdSetProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdSetProperty<R, T>(dbName, T::class.java)
        }

/**
 * Gets from cache or creates a new property delegate for primitive persistent property of type `MutableSet<T>`.
 * Xodus builds index for every element of the set. So you can query entity by any element of the set.
 *
 * If persistent property value is not defined in database the property returns empty set.
 *
 * Adding and removing of elements of the set are saved to the database.
 *
 * **Sample**: set of strings property with database name `tags`.
 * ```
 * class XdPost(entity: Entity): XdEntity(entity) {
 *     companion object: XdNaturalEntityType<XdPost>()
 *     var tags by xdMutableSetProp<XdPost, String>()
 * }
 *
 * // Add tag
 * post.tags.add("Kotlin")
 *
 * // Finds all posts that contains word "Kotlin" among the value of tags property.
 * XdPost.query(XdPost::tags contains "Kotlin")
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the property in the database.
 * @return property delegate to access Xodus database persistent property using Kotlin-property.
 */
inline fun <R : XdEntity, reified T : Comparable<T>> xdMutableSetProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdMutableSetProperty<R, T>(dbName, T::class.java)
        }

/**
 * Gets from cache or creates a new property delegate for Xodus [jetbrains.exodus.entitystore.Sequence].
 * Sequences let you get unique successive non-negative long IDs. Sequences are persistent, which means that any
 * flushed or committed transaction saves all dirty (modified) sequences which were requested by transactions
 * created against the current EntityStore.
 *
 * **Sample**: sequence to generate issue number in project.
 * ```
 * class XdProject(entity: Entity): XdEntity(entity) {
 *     companion object: XdNaturalEntityType<XdProject>()
 *
 *     var nextIssueNumber by xdSequenceProp()
 * }
 *
 * // Sets unique sequential number for newIssue
 * newIssue.number = project.nextIssueNumber.increment()
 *
 * ```
 *
 * @param dbName name of persistent property in database. If `null` (by default) then name of the related
 *        Kotlin-property is used as the name of the sequence in the database.
 * @return property delegate to access Xodus database sequence using Kotlin-property.
 */
fun <R : XdEntity> xdSequenceProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdSequenceProperty<R>(dbName)
        }
