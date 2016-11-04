package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity

open class XdEnumEntity(override val entity: Entity) : XdEntity() {

    val name by xdRequiredStringProp(dbName = "__ENUM_CONST_NAME__")

    open val displayName: String
        get() = name

}