package kotlinx.dnq

import kotlin.reflect.KProperty

class RequiredPropertyUndefinedException(val entity: XdEntity, val property: KProperty<*>) :
        IllegalStateException("Required field ${property.name} of ${entity.entity.type}[${entity.entityId}] is undefined")