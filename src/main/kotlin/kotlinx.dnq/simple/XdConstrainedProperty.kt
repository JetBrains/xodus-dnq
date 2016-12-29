package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.query.metadata.PropertyType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

abstract class XdConstrainedProperty<in R, T>(
        open val dbPropertyName: String?,
        open val constraints: List<PropertyConstraint<T?>>,
        open val requirement: XdPropertyRequirement,
        open val propertyType: PropertyType): ReadWriteProperty<R, T> {

    abstract fun isDefined(thisRef: R, property: KProperty<*>): Boolean
}