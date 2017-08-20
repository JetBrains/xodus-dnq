package kotlinx.dnq.util

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import java.io.InputStream

fun Entity.reattach() = (this as TransientEntity).reattach()

fun TransientEntity.reattach(): TransientEntity {
    val threadSession = store.threadSession
            ?: throw IllegalStateException("There's no current session to attach transient entity to.")
    return threadSession.newLocalCopy(this)
}

fun <T : Comparable<*>> XdEntity.reattachAndGetPrimitiveValue(propertyName: String): T? {
    @Suppress("UNCHECKED_CAST")
    return entity.reattach().getProperty(propertyName) as T?
}

fun <T : Comparable<*>> XdEntity.reattachAndSetPrimitiveValue(propertyName: String, value: T?, clazz: Class<T>) {
    val entity = entity.reattach()
    if (value == null) {
        entity.deleteProperty(propertyName)
    } else {
        // strict casting
        val strictValue = when (clazz) {
            Int::class.java -> (value as Number).toInt()
            Long::class.java -> (value as Number).toLong()
            Double::class.java -> (value as Number).toDouble()
            Float::class.java -> (value as Number).toFloat()
            Short::class.java -> (value as Number).toShort()
            Byte::class.java -> (value as Number).toByte()
            else -> value // boolean, string and date
        }
        entity.setProperty(propertyName, strictValue)
    }
}

fun XdEntity.reattachAndGetBlob(propertyName: String): InputStream? {
    return entity.reattach().getBlob(propertyName)
}

fun XdEntity.reattachAndSetBlob(propertyName: String, value: InputStream?) {
    val entity = entity.reattach()
    if (value == null) {
        entity.deleteBlob(propertyName)
    } else {
        entity.setBlob(propertyName, value)
    }
}

fun XdEntity.reattachAndGetBlobString(propertyName: String): String? {
    return entity.reattach().getBlobString(propertyName)
}

fun XdEntity.reattachAndSetBlobString(propertyName: String, value: String?) {
    val entity = entity.reattach()
    if (value == null) {
        entity.deleteBlob(propertyName)
    } else {
        entity.setBlobString(propertyName, value)
    }
}

fun XdEntity.getOldValue(propertyName: String): Comparable<*>? {
    return (entity as TransientEntity).getPropertyOldValue(propertyName)
}
