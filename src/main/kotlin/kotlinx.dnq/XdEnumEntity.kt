package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity

open class XdEnumEntity(override val entity: Entity) : XdEntity() {

    companion object : XdNaturalEntityType<XdEnumEntity>() {
        const val ENUM_CONST_NAME_FIELD = "__ENUM_CONST_NAME__"
    }

    val name by xdRequiredStringProp(dbName = ENUM_CONST_NAME_FIELD)

    open val displayName: String
        get() = name

}