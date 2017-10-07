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
fun <R : XdEntity> R.xdIntProp(dbName: String? = null, constraints: Constraints<R, Int?>? = null) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints) { _, _ -> 0 }
        }

/**
 * Creates extension property delegate for **optional** `Int` value. If database value is undefined, the property
 * value is `0`.
 *
 * ### Examples
 * ```
 * var age: xdIntProp()  // Optional Int property with database name `age`.
 * var rank: xdIntProp(dbName = "grade") // Optional Int property with database name `grade`.
 *```
 *
 * @param dbName name of the property in database. If `null` (by default) then Kotlin-property name is used.
 */
fun <R : XdEntity> xdIntProp(dbName: String? = null) = xdProp<R, Int>(dbName) { _, _ -> 0 }

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
fun <R : XdEntity> R.xdRequiredIntProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Int?>? = null) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0 }
        }

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
fun <R : XdEntity> R.xdLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints) { _, _ -> 0L }
        }

/**
 * Creates extension property delegate for **optional** `Long` value. If database value is undefined, the property
 * value is `0L`.
 *
 * ### Examples
 * ```
 * var salary: xdLongProp { min(0) }  // Optional non-negative Long property with database name `salary`.
 *```
 *
 * @param dbName name of the property in database. If `null` (by default) then Kotlin-property name is used.
 */
fun <R : XdEntity> xdLongProp(dbName: String? = null) = xdProp<R, Long>(dbName) { _, _ -> 0L }

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
fun <R : XdEntity> R.xdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints, require = true, unique = unique) { _, _ -> 0L }
        }

fun <R : XdEntity> R.xdNullableLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            xdNullableProp(dbName, constraints)
        }

fun <R : XdEntity> xdNullableLongProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        xdNullableProp(dbName, constraints)

fun <R : XdEntity> R.xdBooleanProp(dbName: String? = null, constraints: Constraints<R, Boolean?>? = null) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints) { _, _ -> false }
        }

fun <R : XdEntity> xdBooleanProp(dbName: String? = null) = xdProp<R, Boolean>(dbName) { _, _ -> false }

fun <R : XdEntity> R.xdNullableBooleanProp(dbName: String? = null, constraints: Constraints<R, Boolean?>? = null) =
        XdPropertyCachedProvider {
            xdNullableProp(dbName, constraints)
        }

fun xdNullableBooleanProp(dbName: String? = null) = xdNullableProp<XdEntity, Boolean>(dbName)

fun <R : XdEntity> R.xdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: Constraints<R, String?>? = null) =
        XdPropertyCachedProvider {
            createXdStringProp(trimmed, dbName, constraints)
        }

fun <R : XdEntity> xdStringProp(trimmed: Boolean = false, dbName: String? = null) = createXdStringProp<R>(trimmed, dbName)

private fun <R : XdEntity> createXdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: Constraints<R, String?>? = null): XdConstrainedProperty<R, String?> {
    val prop = xdNullableProp(dbName, constraints)
    return if (trimmed) {
        prop.wrap(wrap = { it }, unwrap = { it?.trim() })
    } else {
        prop
    }
}

fun <R : XdEntity> R.xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: ((R, KProperty<*>) -> String)? = null,
        constraints: Constraints<R, String?>? = null
) = XdPropertyCachedProvider {
    createXdRequiredStringProp(unique, trimmed, dbName, constraints, default)
}

fun <R : XdEntity> R.xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: String,
        constraints: Constraints<R, String?>? = null
) = xdRequiredStringProp(unique, trimmed, dbName, { _, _ -> default }, constraints)

private fun <R : XdEntity> createXdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        constraints: Constraints<R, String?>? = null,
        default: ((R, KProperty<*>) -> String)? = null
): XdConstrainedProperty<R, String> {
    val prop = xdProp(
            dbName,
            constraints,
            require = true,
            unique = unique,
            default = default ?: { e, p -> throw RequiredPropertyUndefinedException(e, p) }
    )
    return if (trimmed) {
        prop.wrap({ it }, String::trim)
    } else {
        prop
    }
}

fun <R : XdEntity> R.xdDateTimeProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            createXdDateTimeProp(dbName, constraints)
        }

fun <R : XdEntity> xdDateTimeProp(dbName: String? = null) = createXdDateTimeProp<R>(dbName)

private fun <R : XdEntity> createXdDateTimeProp(dbName: String? = null, constraints: Constraints<R, Long?>? = null): XdWrappedProperty<R, Long?, DateTime?> {
    return xdNullableProp(dbName, constraints).wrap({ it?.let { DateTime(it) } }, { it?.millis })
}

fun <R : XdEntity> R.xdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        XdPropertyCachedProvider {
            createXdRequiredDateTimeProp(unique, dbName, constraints)
        }

fun <R : XdEntity> xdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null) = createXdRequiredDateTimeProp<R>(unique, dbName)

private fun <R : XdEntity> createXdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: Constraints<R, Long?>? = null) =
        xdProp(dbName, constraints, require = true, unique = unique) { e, p ->
            throw RequiredPropertyUndefinedException(e, p)
        }.wrap({ DateTime(it) }, { it.millis })

fun <R : XdEntity> R.xdBlobProp(dbName: String? = null) = XdPropertyCachedProvider {
    XdNullableBlobProperty<R>(dbName)
}

fun xdBlobProp(dbName: String? = null) = XdNullableBlobProperty<XdEntity>(null)

fun <R : XdEntity> R.xdRequiredBlobProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdBlobProperty<R>(dbName)
        }

fun xdRequiredBlobProp(dbName: String? = null) = XdBlobProperty<XdEntity>(dbName)

fun <R : XdEntity> R.xdBlobStringProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdNullableTextProperty<R>(dbName)
        }

fun xdBlobStringProp(dbName: String? = null) = XdNullableTextProperty<XdEntity>(dbName)

fun <R : XdEntity> R.xdRequiredBlobStringProp(dbName: String? = null) =
        XdPropertyCachedProvider {
            XdTextProperty<R>(dbName)
        }

fun xdRequiredBlobStringProp(dbName: String? = null) = XdTextProperty<XdEntity>(dbName)