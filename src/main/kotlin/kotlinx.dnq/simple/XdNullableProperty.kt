package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
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
        // Used PrimitiveAssociationSemantics.get(Entity, String, Any) instead of get(Entity, String, Class, Any)
        //   because the later defaults null-value for primitive type wrapper classes, e.g. for null-value of
        //   Long property it returns 0.
        @Suppress("UNCHECKED_CAST")
        return PrimitiveAssociationSemantics.get(thisRef.entity, dbPropertyName ?: property.name, null) as T?
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T?) {
        PrimitiveAssociationSemantics.set(thisRef.entity, dbPropertyName ?: property.name, value, clazz)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}