package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.reattachAndGetPrimitiveValue
import kotlinx.dnq.util.reattachAndSetPrimitiveValue
import kotlin.reflect.KProperty

class XdProperty<in R : XdEntity, T : Comparable<*>>(
        val clazz: Class<T>,
        dbPropertyName: String?,
        constraints: List<PropertyConstraint<T?>>,
        requirement: XdPropertyRequirement,
        val default: (R, KProperty<*>) -> T
) : XdConstrainedProperty<R, T>(
        dbPropertyName,
        constraints,
        requirement,
        PropertyType.PRIMITIVE
) {

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        return thisRef.reattachAndGetPrimitiveValue(property.dbName) ?: default(thisRef, property)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        thisRef.reattachAndSetPrimitiveValue(property.dbName, value, clazz)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return thisRef.reattachAndGetPrimitiveValue<T>(property.dbName) != null
    }
}