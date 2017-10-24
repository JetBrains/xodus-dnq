/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package kotlinx.dnq

import kotlinx.dnq.simple.*
import kotlinx.dnq.util.XdPropertyCachedProvider
import org.joda.time.DateTime
import kotlin.reflect.KProperty


fun <R : XdEntity> xdByteProp(dbName: String? = null, constraints: Constraints<R, Byte?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0 }

fun <R : XdEntity> xdRequiredByteProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Byte?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }

fun <R : XdEntity> xdNullableByteProp(dbName: String? = null, constraints: Constraints<R, Byte?>? = null) =
        xdNullableCachedProp(dbName, constraints)

fun <R : XdEntity> xdShortProp(dbName: String? = null, constraints: Constraints<R, Short?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0 }

fun <R : XdEntity> xdRequiredShortProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Short?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }

fun <R : XdEntity> xdNullableShortProp(dbName: String? = null, constraints: Constraints<R, Short?>? = null) =
        xdNullableCachedProp(dbName, constraints)

/**
 * Creates member property delegate for **optional** `Int` value. If database value is undefined, the property
 * value is `0`.
 *
 * ### Examples
 * ```
 * var age: xdIntProp { min(0) }  // Optional non-negative Int property with database name `age`.
 * var rank: xdIntProp(dbName = "grade") // Optional Int property with database name `grade`.
 *```
 *
 * @param dbName name of the property in database. If `null` (by default) then Kotlin-property name is used.
 * @param constraints closure to build property constraints.
 */
fun <R : XdEntity> xdIntProp(dbName: String? = null, constraints: Constraints<R, Int?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0 }

/**
 * Creates member property delegate for **required** `Int` value. While database value is undefined, the property
 * value is `0`.
 *
 * ### Examples
 * ```
 * var age: xdRequiredIntProp { min(0) }  // Required non-negative Int property with database name `age`.
 * var rank: xdRequiredIntProp(dbName = "grade") // Required Int property with database name `grade`.
 * var id: xdRequiredIntProp(unique = true) // Unique required Int property with database name `id`.
 *```
 *
 * @param dbName name of the property in database. If `null` (by default) then Kotlin-property name is used.
 * @param unique if `true` the property value is checked to be unique among the values of this property
 *        for this persistent class.
 * @param constraints closure to build property constraints.
 */
fun <R : XdEntity> xdRequiredIntProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Int?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }

fun <R : XdEntity> xdNullableIntProp(dbName: String? = null, constraints: Constraints<R, Int?>? = null) =
        xdNullableCachedProp(dbName, constraints)

/**
 * Creates member property delegate for **optional** `Long` value. If database value is undefined, the property
 * value is `0L`.
 *
 * ### Examples
 * ```
 * var salary: xdLongProp { min(0) }  // Optional non-negative Long property with database name `salary`.
 *```
 *
 * @param dbName name of the property in database. If `null` (by default) then Kotlin-property name is used.
 * @param constraints closure to build property constraints.
 */
fun <R : XdEntity> xdLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0L }

/**
 * Creates member property delegate for **required** `Long` value. While database value is undefined, the property
 * value is `0L`.
 *
 * ### Examples
 * ```
 * var id: xdRequiredLongProp(unique = true) // Unique required Long property with database name `id`.
 * var salary: xdRequiredLongProp { min(0) } // Optional non-negative Long property with database name `salary`.
 *```
 *
 * @param dbName name of the property in database. If `null` (by default) then Kotlin-property name is used.
 * @param unique if `true` the property value is checked to be unique among the values of this property
 *        for this persistent class.
 * @param constraints closure to build property constraints.
 */
fun <R : XdEntity> xdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Long?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0L }

fun <R : XdEntity> xdNullableLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        xdNullableCachedProp(dbName, constraints)

fun <R : XdEntity> xdFloatProp(dbName: String? = null, constraints: Constraints<R, Float?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0f }

fun <R : XdEntity> xdRequiredFloatProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Float?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0f }

fun <R : XdEntity> xdNullableFloatProp(dbName: String? = null, constraints: Constraints<R, Float?>? = null) =
        xdNullableCachedProp(dbName, constraints)

fun <R : XdEntity> xdDoubleProp(dbName: String? = null, constraints: Constraints<R, Double?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> 0.0 }

fun <R : XdEntity> xdRequiredDoubleProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Double?>? = null) =
        xdCachedProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0.0 }

fun <R : XdEntity> xdNullableDoubleProp(dbName: String? = null, constraints: Constraints<R, Double?>? = null) =
        xdNullableCachedProp(dbName, constraints)

fun <R : XdEntity> xdBooleanProp(dbName: String? = null, constraints: Constraints<R, Boolean?>? = null) =
        xdCachedProp(dbName, constraints) { _, _ -> false }

fun <R : XdEntity> xdNullableBooleanProp(dbName: String? = null, constraints: Constraints<R, Boolean?>? = null) =
        xdNullableCachedProp(dbName, constraints)

fun <R : XdEntity> xdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: Constraints<R, String?>? = null) =
        XdPropertyCachedProvider {
            val prop = xdNullableProp(dbName, constraints)
            if (trimmed) {
                prop.wrap(wrap = { it }, unwrap = { it?.trim() })
            } else {
                prop
            }
        }

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
            default = default ?: { e, p -> throw RequiredPropertyUndefinedException(e, p) }
    )
    if (trimmed) {
        prop.wrap({ it }, String::trim)
    } else {
        prop
    }
}

fun <R : XdEntity> xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: String,
        constraints: Constraints<R, String?>? = null
) = xdRequiredStringProp(unique, trimmed, dbName, { _, _ -> default }, constraints)

fun <R : XdEntity> xdDateTimeProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            xdNullableProp(dbName, constraints).wrap({ it?.let { DateTime(it) } }, { it?.millis })
        }

fun <R : XdEntity> xdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints, require = true, unique = unique) { e, p ->
                throw RequiredPropertyUndefinedException(e, p)
            }.wrap({ DateTime(it) }, { it.millis })
        }

fun <R : XdEntity> xdBlobProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdNullableBlobProperty<R>(dbName)
        }

fun <R : XdEntity> xdRequiredBlobProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdBlobProperty<R>(dbName)
        }

fun <R : XdEntity> xdBlobStringProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdNullableTextProperty<R>(dbName)
        }

fun <R : XdEntity> xdRequiredBlobStringProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdTextProperty<R>(dbName)
        }
