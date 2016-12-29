package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdTextProperty<in R : XdEntity>(dbPropertyName: String?) :
        XdConstrainedProperty<R, String>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.REQUIRED,
                PropertyType.TEXT) {

    override fun getValue(thisRef: R, property: KProperty<*>): String {
        return PrimitiveAssociationSemantics.getBlobAsString(thisRef.entity, dbPropertyName ?: property.name) ?:
                throw RequiredPropertyUndefinedException(thisRef, property)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: String) {
        PrimitiveAssociationSemantics.setBlob(thisRef.entity, dbPropertyName ?: property.name, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return PrimitiveAssociationSemantics.getBlobAsString(thisRef.entity, dbPropertyName ?: property.name) != null
    }
}