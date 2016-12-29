package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import java.io.InputStream
import kotlin.reflect.KProperty

class XdNullableBlobProperty<in R : XdEntity>(dbPropertyName: String?) :
        XdConstrainedProperty<R, InputStream?>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.BLOB) {

    override fun getValue(thisRef: R, property: KProperty<*>): InputStream? {
        return PrimitiveAssociationSemantics.getBlob(thisRef.entity, dbPropertyName ?: property.name)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: InputStream?) {
        PrimitiveAssociationSemantics.setBlob(thisRef.entity, dbPropertyName ?: property.name, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}