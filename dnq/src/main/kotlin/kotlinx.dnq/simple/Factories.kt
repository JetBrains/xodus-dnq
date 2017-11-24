package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.query.metadata.PropertyMetaData
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.XdPropertyCachedProvider
import kotlin.reflect.KProperty

typealias Constraints<R, T> = PropertyConstraintBuilder<R, T?>.() -> Unit

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
            override fun check(e: TransientEntity, pmd: PropertyMetaData, value: B?) =
                    wrappedConstraint.check(e, pmd, value?.let { wrap(value) })

            override fun isValid(value: B?) =
                    wrappedConstraint.isValid(value?.let { wrap(it) })

            override fun getExceptionMessage(propertyName: String, propertyValue: B?) =
                    wrappedConstraint.getExceptionMessage(propertyName, propertyValue?.let { wrap(it) })

            override fun getDisplayMessage(propertyName: String, propertyValue: B?) =
                    wrappedConstraint.getDisplayMessage(propertyName, propertyValue?.let { wrap(it) })
        }
    }
}


inline fun <R : XdEntity, reified T : Comparable<*>> xdCachedProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null,
        require: Boolean = false,
        unique: Boolean = false,
        noinline default: (R, KProperty<*>) -> T) =
        XdPropertyCachedProvider {
            xdProp(dbName, constraints, require, unique, default)
        }

inline fun <R : XdEntity, reified T : Comparable<*>> xdNullableCachedProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null) =
        XdPropertyCachedProvider {
            xdNullableProp(dbName, constraints)
        }

inline fun <R : XdEntity, reified T : Comparable<*>> xdProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null,
        require: Boolean = false,
        unique: Boolean = false,
        noinline default: (R, KProperty<*>) -> T): XdProperty<R, T> {

    return XdProperty(T::class.java, dbName, constraints.collect(), when {
        unique -> XdPropertyRequirement.UNIQUE
        require -> XdPropertyRequirement.REQUIRED
        else -> XdPropertyRequirement.OPTIONAL
    }, default)
}

inline fun <R : XdEntity, reified T : Comparable<*>> xdNullableProp(
        dbName: String? = null,
        noinline constraints: Constraints<R, T?>? = null): XdNullableProperty<R, T> {
    return XdNullableProperty(T::class.java, dbName, constraints.collect())
}

fun <R : XdEntity, B, T> XdConstrainedProperty<R, B>.wrap(wrap: (B) -> T, unwrap: (T) -> B): XdWrappedProperty<R, B, T> {
    return XdWrappedProperty(this, wrap, unwrap)
}