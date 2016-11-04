package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import java.io.InputStream
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class XdBlobProperty<in R : XdEntity>(
        dbPropertyName: String?) :
        XdConstrainedProperty<R, InputStream>(dbPropertyName, emptyList(), XdPropertyRequirement.REQUIRED) {

    override fun getValue(thisRef: R, property: KProperty<*>): InputStream {
        return PrimitiveAssociationSemantics.getBlob(thisRef.entity, dbPropertyName ?: property.name) ?:
                throw RequiredPropertyUndefinedException(thisRef, property)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: InputStream) {
        PrimitiveAssociationSemantics.setBlob(thisRef.entity, dbPropertyName ?: property.name, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return PrimitiveAssociationSemantics.getBlob(thisRef.entity, dbPropertyName ?: property.name) != null
    }
}