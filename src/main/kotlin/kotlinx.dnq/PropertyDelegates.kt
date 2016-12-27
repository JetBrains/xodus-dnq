package kotlinx.dnq

import kotlinx.dnq.simple.*
import kotlinx.dnq.util.CachedProperties
import org.joda.time.DateTime
import java.net.URI
import java.net.URL
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/*************************************************************/
// Optional Int property
private val _xdIntProp = xdProp<XdEntity, Int> { e, p -> 0 }

fun <R : XdEntity> R.xdIntProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Int?>.() -> Unit)? = null): XdProperty<R, Int> {
    return if (dbName == null && constraints == null) {
        _xdIntProp
    } else {
        xdProp<R, Int>(dbName, constraints) { e, p -> 0 }
    }
}

/*************************************************************/
// Required Int property
private val _xdRequiredIntProp = CachedProperties(1) {
    createXdRequiredIntProp<XdEntity>(dbName = null, unique = it[0], constraints = null)
}

fun <R : XdEntity> R.xdRequiredIntProp(dbName: String? = null, unique: Boolean = false, constraints: (PropertyConstraintBuilder<R, Int?>.() -> Unit)? = null): ReadWriteProperty<R, Int> {
    return if (dbName == null && constraints == null) {
        _xdRequiredIntProp[unique]
    } else {
        createXdRequiredIntProp(dbName, unique, constraints)
    }
}

private fun <R : XdEntity> createXdRequiredIntProp(dbName: String?, unique: Boolean, constraints: (PropertyConstraintBuilder<R, Int?>.() -> Unit)?): XdProperty<R, Int> =
        xdProp(dbName, constraints, require = true, unique = unique) { e, p -> 0 }


/*************************************************************/
// Optional Long property
private val _xdLongProp = xdProp<XdEntity, Long> { e, p -> 0L }

fun <R : XdEntity> R.xdLongProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): XdProperty<R, Long> {
    return if (dbName == null && constraints == null) {
        _xdLongProp
    } else {
        xdProp<R, Long>(dbName, constraints) { e, p -> 0L }
    }
}

/*************************************************************/
// Required Long property
private val _xdRequiredLongProp = CachedProperties(1) {
    createXdRequiredLongProp<XdEntity>(unique = it[0])
}

fun <R : XdEntity> R.xdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): ReadWriteProperty<R, Long> {
    return if (dbName == null && constraints == null) {
        _xdRequiredLongProp[unique]
    } else {
        createXdRequiredLongProp(dbName, unique, constraints)
    }
}

private fun <R : XdEntity> createXdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): XdProperty<R, Long> {
    return xdProp(dbName, constraints, require = true, unique = unique) { e, p -> 0L }
}

/*************************************************************/
// Nullable Long property
private val _xdNullableLongProp = xdNullableProp<XdEntity, Long>()

fun <R : XdEntity> R.xdNullableLongProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): XdNullableProperty<R, Long> {
    return if (dbName == null && constraints == null) {
        _xdNullableLongProp
    } else {
        xdNullableProp<R, Long>(dbName, constraints)
    }
}

/*************************************************************/
// Boolean property
private val _xdBooleanProp = xdProp<XdEntity, Boolean> { e, p -> false }

fun xdBooleanProp(dbName: String? = null): XdProperty<XdEntity, Boolean> {
    return if (dbName == null) {
        _xdBooleanProp
    } else {
        xdProp<XdEntity, Boolean>(dbName) { e, p -> false }
    }
}

/*************************************************************/
// Nullable Boolean property
private val _xdNullableBooleanProp = xdNullableProp<XdEntity, Boolean>()

fun xdNullableBooleanProp(dbName: String? = null): XdNullableProperty<XdEntity, Boolean> {
    return if (dbName == null) {
        _xdNullableBooleanProp
    } else {
        xdNullableProp<XdEntity, Boolean>(dbName)
    }
}

/*************************************************************/
// Optional String property
private val _xdStringProp = CachedProperties(1) {
    createXdStringProp<XdEntity>(trimmed = it[0])
}

fun <R : XdEntity> R.xdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<R, String?>.() -> Unit)? = null): XdConstrainedProperty<R, String?> {
    return if (dbName == null && constraints == null) {
        _xdStringProp[trimmed]
    } else {
        createXdStringProp(trimmed, dbName, constraints)
    }
}

private fun <R : XdEntity> createXdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<R, String?>.() -> Unit)? = null): XdConstrainedProperty<R, String?> {
    val prop = xdNullableProp<R, String>(dbName, constraints)
    return if (trimmed) {
        prop.wrap({ it }, { it?.trim() })
    } else {
        prop
    }
}

/*************************************************************/
// Required String property
private val _xdRequiredStringProp = CachedProperties(2) {
    createXdRequiredStringProp<XdEntity>(unique = it[0], trimmed = it[1])
}

fun <R : XdEntity> R.xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: ((R, KProperty<*>) -> String)? = null,
        constraints: (PropertyConstraintBuilder<R, String?>.() -> Unit)? = null
): XdConstrainedProperty<R, String> {
    return if (dbName == null && constraints == null) {
        _xdRequiredStringProp[unique, trimmed]
    } else {
        createXdRequiredStringProp(unique, trimmed, dbName, constraints, default)
    }
}

fun <R : XdEntity> R.xdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        default: String,
        constraints: (PropertyConstraintBuilder<R, String?>.() -> Unit)? = null
) = xdRequiredStringProp(unique, trimmed, dbName, { tr, p -> default }, constraints)

private fun <R : XdEntity> createXdRequiredStringProp(
        unique: Boolean = false,
        trimmed: Boolean = false,
        dbName: String? = null,
        constraints: (PropertyConstraintBuilder<R, String?>.() -> Unit)? = null,
        default: ((R, KProperty<*>) -> String)? = null
): XdConstrainedProperty<R, String> {
    val prop = xdProp<R, String>(dbName, constraints, require = true, unique = unique,
            default = default ?: { e, p -> throw RequiredPropertyUndefinedException(e, p) })
    return if (trimmed) {
        prop.wrap({ it }, String::trim)
    } else {
        prop
    }
}

/*************************************************************/
// Optional DateTime property
private val _xdDateTimeProp = createXdDateTimeProp<XdEntity>()

fun <R : XdEntity> R.xdDateTimeProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): ReadWriteProperty<R, DateTime?> {
    return if (dbName == null && constraints == null) {
        _xdDateTimeProp
    } else {
        createXdDateTimeProp(dbName, constraints)
    }
}

fun <R : XdEntity> createXdDateTimeProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): XdWrappedProperty<R, Long?, DateTime?> {
    return xdNullableProp<R, Long>(dbName, constraints).wrap({ it?.let { DateTime(it) } }, { it?.millis })
}

/*************************************************************/
// Required DateTime property
private val _xdRequiredDateTimeProp = CachedProperties(1) {
    createXdRequiredDateTimeProp<XdEntity>(it[0])
}

fun <R : XdEntity> R.xdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): ReadWriteProperty<R, DateTime> {
    return if (dbName == null && constraints == null) {
        _xdRequiredDateTimeProp[unique]
    } else {
        createXdRequiredDateTimeProp(unique, dbName, constraints)
    }
}

fun <R : XdEntity> createXdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<R, Long?>.() -> Unit)? = null): XdWrappedProperty<R, Long, DateTime> {
    return xdProp<R, Long>(dbName, constraints, require = true, unique = unique) { e, p ->
        throw RequiredPropertyUndefinedException(e, p)
    }.wrap({ DateTime(it) }, { it.millis })
}

/*************************************************************/
// Optional Blob property
private val _xdBlobProp = XdNullableBlobProperty<XdEntity>(null)

fun xdBlobProp(dbName: String? = null): XdNullableBlobProperty<XdEntity> {
    return if (dbName == null) {
        _xdBlobProp
    } else {
        XdNullableBlobProperty<XdEntity>(null)
    }
}


/*************************************************************/
// Required Blob property
private val _xdRequiredBlobProp = XdBlobProperty<XdEntity>(null)

fun xdRequiredBlobProp(dbName: String? = null): XdBlobProperty<XdEntity> {
    return if (dbName == null) {
        _xdRequiredBlobProp
    } else {
        XdBlobProperty<XdEntity>(dbName)
    }
}

/*************************************************************/
// Optional Text property
private val _xdBlobStringProp = XdNullableTextProperty<XdEntity>(null)

fun xdBlobStringProp(dbName: String? = null): XdNullableTextProperty<XdEntity> {
    return if (dbName == null) {
        _xdBlobStringProp
    } else {
        XdNullableTextProperty<XdEntity>(dbName)
    }
}

/*************************************************************/
// Required Text property
private val _xdRequiredBlobStringProp = XdTextProperty<XdEntity>(null)

fun xdRequiredBlobStringProp(dbName: String? = null): XdTextProperty<XdEntity> {
    return if (dbName == null) {
        _xdRequiredBlobStringProp
    } else {
        XdTextProperty<XdEntity>(dbName)
    }
}


private inline fun <R : XdEntity, reified T : Comparable<*>> xdProp(
        propertyName: String? = null,
        noinline constraints: (PropertyConstraintBuilder<R, T?>.() -> Unit)? = null,
        require: Boolean = false,
        unique: Boolean = false,
        noinline default: (R, KProperty<*>) -> T): XdProperty<R, T> {

    return XdProperty(T::class.java, propertyName, constraints?.let {
        PropertyConstraintBuilder<R, T?>().apply(constraints).constraints
    } ?: emptyList(), when {
        unique -> XdPropertyRequirement.UNIQUE
        require -> XdPropertyRequirement.REQUIRED
        else -> XdPropertyRequirement.OPTIONAL
    }, default)
}

private inline fun <R : XdEntity, reified T : Comparable<*>> xdNullableProp(
        propertyName: String? = null,
        noinline constraints: (PropertyConstraintBuilder<R, T?>.() -> Unit)? = null): XdNullableProperty<R, T> {
    return XdNullableProperty(T::class.java, propertyName, constraints?.let {
        PropertyConstraintBuilder<R, T?>().apply(constraints).constraints
    } ?: emptyList())
}

fun <R : XdEntity, B, T> XdConstrainedProperty<R, B>.wrap(wrap: (B) -> T, unwrap: (T) -> B): XdWrappedProperty<R, B, T> {
    return XdWrappedProperty(this, wrap, unwrap)
}
