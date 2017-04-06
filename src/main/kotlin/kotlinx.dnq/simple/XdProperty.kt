package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
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
        return PrimitiveAssociationSemantics.get(thisRef.entity, dbPropertyName ?: property.name, clazz, null) ?:
                default(thisRef, property)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        PrimitiveAssociationSemantics.set(thisRef.entity, dbPropertyName ?: property.name, value, clazz)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return PrimitiveAssociationSemantics.get(thisRef.entity, dbPropertyName ?: property.name, null) != null
    }
}