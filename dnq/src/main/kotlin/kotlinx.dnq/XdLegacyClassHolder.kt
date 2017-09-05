package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import org.jetbrains.mazine.infer.type.parameter.inferTypeParameterClass

interface XdLegacyClassHolder<P : BasePersistentClassImpl, T : XdEntity> {
    val legacyClass: Class<P>

    @Suppress("UNCHECKED_CAST")
    val T.mpsType: P
        get() = (entity.store as TransientEntityStoreImpl).getCachedPersistentClassInstance(entity.type) as P
}

val <P : BasePersistentClassImpl> Class<P>.entityType: String
    get() = this.simpleName?.removeSuffix("Impl") ?: throw IllegalArgumentException("Type has no name")

inline fun <reified P : BasePersistentClassImpl> entityType(): String {
    return P::class.java.entityType
}

fun <B, T : B, V : Any> Class<B>.inferLegacyClass(typeVariableName: String, inheritedClass: Class<T>): Class<V> {
    return inferTypeParameterClass(this, typeVariableName, inheritedClass)
}
