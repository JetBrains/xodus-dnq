package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.reattachAndGetPrimitiveValue
import kotlinx.dnq.util.reattachAndSetPrimitiveValue
import kotlin.reflect.KProperty

class XdNullableProperty<in R : XdEntity, T : Comparable<*>>(
        val clazz: Class<T>,
        dbPropertyName: String?,
        constraints: List<PropertyConstraint<T?>>) :
        XdConstrainedProperty<R, T?>(
                dbPropertyName,
                constraints,
                XdPropertyRequirement.OPTIONAL,
                PropertyType.PRIMITIVE) {

    override fun getValue(thisRef: R, property: KProperty<*>): T? {
        return thisRef.reattachAndGetPrimitiveValue(property.dbName)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T?) {
        thisRef.reattachAndSetPrimitiveValue(property.dbName, value, clazz)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}