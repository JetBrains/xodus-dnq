package kotlinx.dnq.simple

import jetbrains.exodus.entitystore.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdWrappedProperty<in R : XdEntity, B, T>(
        val wrapped: XdConstrainedProperty<R, B>,
        val wrap: (B) -> T,
        val unwrap: (T) -> B) :
        XdConstrainedProperty<R, T>(
                null,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.PRIMITIVE) {

    override val dbPropertyName: String?
        get() = wrapped.dbPropertyName

    override val requirement: XdPropertyRequirement
        get() = wrapped.requirement

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        return wrap(wrapped.getValue(thisRef, property))
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        wrapped.setValue(thisRef, property, unwrap(value))
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = wrapped.isDefined(thisRef, property)
}