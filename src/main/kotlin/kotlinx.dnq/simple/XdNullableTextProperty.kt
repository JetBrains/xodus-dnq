package kotlinx.dnq.simple

import jetbrains.exodus.query.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.util.reattachAndGetBlobString
import kotlinx.dnq.util.reattachAndSetBlobString
import kotlin.reflect.KProperty

class XdNullableTextProperty<in R : XdEntity>(
        dbPropertyName: String?) :
        XdConstrainedProperty<R, String?>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.TEXT) {

    override fun getValue(thisRef: R, property: KProperty<*>): String? {
        return thisRef.reattachAndGetBlobString(property.dbName)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: String?) {
        thisRef.reattachAndSetBlobString(property.dbName, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}