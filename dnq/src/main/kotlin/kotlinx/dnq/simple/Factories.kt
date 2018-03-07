/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.query.metadata.PropertyMetaData
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import kotlinx.dnq.util.XdPropertyCachedProvider
import kotlin.reflect.KProperty

typealias Constraints<R, T> = PropertyConstraintBuilder<R, T?>.() -> Unit

val DEFAULT_REQUIRED: (XdEntity, KProperty<*>) -> Nothing = { e, p -> throw RequiredPropertyUndefinedException(e, p) }

fun <R : XdEntity, T : Comparable<*>> Constraints<R, T>?.collect(): List<PropertyConstraint<T?>> {
    return if (this != null) {
        PropertyConstraintBuilder<R, T?>()
                .apply(this)
                .constraints
    } else {
        emptyList()
    }
}

fun <R : XdEntity, B : Comparable<*>, T : Comparable<*>> List<PropertyConstraint<T?>>.wrap(wrap: (B) -> T): List<PropertyConstraint<B?>> {
    return map { wrappedConstraint ->
        object : PropertyConstraint<B?>() {
            override fun check(entity: TransientEntity, propertyMetaData: PropertyMetaData, value: B?) =
                    wrappedConstraint.check(entity, propertyMetaData, value?.let { wrap(value) })

            override fun isValid(value: B?) =
                    wrappedConstraint.isValid(value?.let { wrap(it) })

            override fun getExceptionMessage(propertyName: String, propertyValue: B?) =
                    wrappedConstraint.getExceptionMessage(propertyName, propertyValue?.let { wrap(it) })

            override fun getDisplayMessage(propertyName: String, propertyValue: B?) =
                    wrappedConstraint.getDisplayMessage(propertyName, propertyValue?.let { wrap(it) })
        }
    }
}


inline fun <R : XdEntity, reified T : Comparable<T>> xdCachedProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null,
        require: Boolean = false,
        unique: Boolean = false,
        binding: XdCustomTypeBinding<T>? = null,
        noinline default: (R, KProperty<*>) -> T) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints, require, unique, default, binding)
        }

inline fun <R : XdEntity, reified T : Comparable<T>> xdNullableCachedProp(
        dbName: String? = null,
        binding: XdCustomTypeBinding<T>? = null,
        noinline constraints: Constraints<R, T?>? = null) =
        XdPropertyCachedProvider {
            xdNullableProp(dbName, constraints, binding)
        }

inline fun <R : XdEntity, reified T : Comparable<T>> xdProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null,
        require: Boolean = false,
        unique: Boolean = false,
        noinline default: (R, KProperty<*>) -> T,
        binding: XdCustomTypeBinding<T>? = null): XdProperty<R, T> {

    return XdProperty(T::class.java, dbName, constraints.collect(), when {
        unique -> XdPropertyRequirement.UNIQUE
        require -> XdPropertyRequirement.REQUIRED
        else -> XdPropertyRequirement.OPTIONAL
    }, default, binding)
}

inline fun <R : XdEntity, reified T : Comparable<T>> xdNullableProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null,
        binding: XdCustomTypeBinding<T>? = null): XdNullableProperty<R, T> {
    return XdNullableProperty(T::class.java, dbName, constraints.collect(), binding)
}

fun <R : XdEntity, B, T> XdMutableConstrainedProperty<R, B>.wrap(wrap: (B) -> T, unwrap: (T) -> B): XdWrappedProperty<R, B, T> {
    return XdWrappedProperty(this, wrap, unwrap)
}
