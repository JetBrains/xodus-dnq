package kotlinx.dnq.simple

import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.reattachAndGetBlob
import kotlinx.dnq.util.reattachAndSetBlob
import java.io.InputStream
import kotlin.reflect.KProperty

class XdNullableBlobProperty<in R : XdEntity>(dbPropertyName: String?) :
        XdConstrainedProperty<R, InputStream?>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.BLOB) {

    override fun getValue(thisRef: R, property: KProperty<*>): InputStream? {
        return thisRef.reattachAndGetBlob(property.dbName)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: InputStream?) {
        thisRef.reattachAndSetBlob(property.dbName, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}