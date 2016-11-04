package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import jetbrains.teamsys.dnq.runtime.util.DnqUtils
import kotlinx.dnq.store.container.LegacyStoreContainer
import kotlinx.dnq.util.inferTypeParameters
import java.lang.reflect.ParameterizedType


abstract class XdLegacyEntityType<P : BasePersistentClassImpl, T : XdEntity>(legacyClass: Class<P>? = null) : XdEntityType<T>(LegacyStoreContainer()) {

    val legacyClass: Class<P> = legacyClass ?: run {
        val pArgument = javaClass.inferTypeParameters(XdLegacyEntityType::class.java)[0]

        val clazz = when (pArgument) {
            is Class<*> -> pArgument
            is ParameterizedType -> pArgument.rawType as? Class<*>
            else -> null
        }

        @Suppress("UNCHECKED_CAST")
        ((clazz as? Class<P>) ?: throw IllegalArgumentException("Cannot infer legacy MPS class for ${javaClass.canonicalName}"))
    }

    override val entityType: String
        get() = legacyClass.entityType

    @Suppress("UNCHECKED_CAST")
    val T.mpsType: P
        get() = DnqUtils.getPersistentClassInstance(entity, entity.type) as P

}

val <P : BasePersistentClassImpl> Class<P>.entityType: String
    get() = this.simpleName?.removeSuffix("Impl") ?: throw IllegalArgumentException("Type has no name")

inline fun <reified P : BasePersistentClassImpl> entityType(): String {
    return P::class.java.entityType
}