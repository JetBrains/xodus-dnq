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

fun xdIntProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<Int?>.() -> Unit)? = null): XdProperty<XdEntity, Int> {
    return if (dbName == null && constraints == null) {
        _xdIntProp
    } else {
        xdProp<XdEntity, Int>(dbName, constraints) { e, p -> 0 }
    }
}


/*************************************************************/
// Required Int property
private val _xdRequiredIntProp = CachedProperties(1) {
    createXdRequiredIntProp(dbName = null, unique = it[0], constraints = null)
}

fun xdRequiredIntProp(dbName: String? = null, unique: Boolean = false, constraints: (PropertyConstraintBuilder<Int?>.() -> Unit)? = null): ReadWriteProperty<XdEntity, Int> {
    return if (dbName == null && constraints == null) {
        _xdRequiredIntProp[unique]
    } else {
        createXdRequiredIntProp(dbName, unique, constraints)
    }
}

private fun createXdRequiredIntProp(dbName: String?, unique: Boolean, constraints: (PropertyConstraintBuilder<Int?>.() -> Unit)?): XdProperty<XdEntity, Int> =
        xdProp(dbName, constraints, require = true, unique = unique) { e, p -> 0 }


/*************************************************************/
// Optional Long property
private val _xdLongProp = xdProp<XdEntity, Long> { e, p -> 0L }

fun xdLongProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): XdProperty<XdEntity, Long> {
    return if (dbName == null && constraints == null) {
        _xdLongProp
    } else {
        xdProp<XdEntity, Long>(dbName, constraints) { e, p -> 0L }
    }
}

/*************************************************************/
// Required Long property
private val _xdRequiredLongProp = CachedProperties(1) {
    createXdRequiredLongProp(unique = it[0])
}

fun xdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): ReadWriteProperty<XdEntity, Long> {
    return if (dbName == null && constraints == null) {
        _xdRequiredLongProp[unique]
    } else {
        createXdRequiredLongProp(dbName, unique, constraints)
    }
}

private fun createXdRequiredLongProp(dbName: String? = null, unique: Boolean = false, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): XdProperty<XdEntity, Long> {
    return xdProp(dbName, constraints, require = true, unique = unique) { e, p -> 0L }
}

/*************************************************************/
// Nullable Long property
private val _xdNullableLongProp = xdNullableProp<XdEntity, Long>()

fun xdNullableLongProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): XdNullableProperty<XdEntity, Long> {
    return if (dbName == null && constraints == null) {
        _xdNullableLongProp
    } else {
        xdNullableProp<XdEntity, Long>(dbName, constraints)
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
    createXdStringProp(trimmed = it[0])
}

fun xdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<String?>.() -> Unit)? = null): XdConstrainedProperty<XdEntity, String?> {
    return if (dbName == null && constraints == null) {
        _xdStringProp[trimmed]
    } else {
        createXdStringProp(trimmed, dbName, constraints)
    }
}

private fun createXdStringProp(trimmed: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<String?>.() -> Unit)? = null): XdConstrainedProperty<XdEntity, String?> {
    val prop = xdNullableProp<XdEntity, String>(dbName, constraints)
    return if (trimmed) {
        prop.wrap({ it }, { it?.trim() })
    } else {
        prop
    }
}

/*************************************************************/
// Required String property
private val _xdRequiredStringProp = CachedProperties(2) {
    createXdRequiredStringProp(unique = it[0], trimmed = it[1])
}

fun xdRequiredStringProp(unique: Boolean = false, trimmed: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<String?>.() -> Unit)? = null): XdConstrainedProperty<XdEntity, String> {
    return if (dbName == null && constraints == null) {
        _xdRequiredStringProp[unique, trimmed]
    } else {
        createXdRequiredStringProp(unique, trimmed, dbName, constraints)
    }
}

private fun createXdRequiredStringProp(unique: Boolean = false, trimmed: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<String?>.() -> Unit)? = null): XdConstrainedProperty<XdEntity, String> {
    val prop = xdProp<XdEntity, String>(dbName, constraints, require = true, unique = unique) { e, p -> throw RequiredPropertyUndefinedException(e, p) }
    return if (trimmed) {
        prop.wrap({ it }, { it.trim() })
    } else {
        prop
    }
}

/*************************************************************/
// Optional DateTime property
private val _xdDateTimeProp = createXdDateTimeProp()

fun xdDateTimeProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): ReadWriteProperty<XdEntity, DateTime?> {
    return if (dbName == null && constraints == null) {
        _xdDateTimeProp
    } else {
        createXdDateTimeProp(dbName, constraints)
    }
}

fun createXdDateTimeProp(dbName: String? = null, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): XdWrappedProperty<XdEntity, Long?, DateTime?> {
    return xdNullableProp<XdEntity, Long>(dbName, constraints).wrap({ it?.let { DateTime(it) } }, { it?.millis })
}

/*************************************************************/
// Required DateTime property
private val _xdRequiredDateTimeProp = CachedProperties(1) {
    createXdRequiredDateTimeProp(it[0])
}

fun xdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): ReadWriteProperty<XdEntity, DateTime> {
    return if (dbName == null && constraints == null) {
        _xdRequiredDateTimeProp[unique]
    } else {
        createXdRequiredDateTimeProp(unique, dbName, constraints)
    }
}

fun createXdRequiredDateTimeProp(unique: Boolean = false, dbName: String? = null, constraints: (PropertyConstraintBuilder<Long?>.() -> Unit)? = null): XdWrappedProperty<XdEntity, Long, DateTime> {
    return xdProp<XdEntity, Long>(dbName, constraints, require = true, unique = unique) { e, p ->
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
        noinline constraints: (PropertyConstraintBuilder<T?>.() -> Unit)? = null,
        require: Boolean = false,
        unique: Boolean = false,
        noinline default: (R, KProperty<*>) -> T): XdProperty<R, T> {

    return XdProperty(T::class.java, propertyName, constraints?.let {
        PropertyConstraintBuilder<T?>().apply(constraints).constraints
    } ?: emptyList(), when {
        unique -> XdPropertyRequirement.UNIQUE
        require -> XdPropertyRequirement.REQUIRED
        else -> XdPropertyRequirement.OPTIONAL
    }, default)
}

private inline fun <R : XdEntity, reified T : Comparable<*>> xdNullableProp(
        propertyName: String? = null,
        noinline constraints: (PropertyConstraintBuilder<T?>.() -> Unit)? = null): XdNullableProperty<R, T> {
    return XdNullableProperty(T::class.java, propertyName, constraints?.let {
        PropertyConstraintBuilder<T?>().apply(constraints).constraints
    } ?: emptyList())
}

fun <R : XdEntity, B, T> XdConstrainedProperty<R, B>.wrap(wrap: (B) -> T, unwrap: (T) -> B): XdWrappedProperty<R, B, T> {
    return XdWrappedProperty(this, wrap, unwrap)
}
