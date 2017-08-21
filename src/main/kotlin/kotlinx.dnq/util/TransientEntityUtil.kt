package kotlinx.dnq.util

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStore
import kotlinx.dnq.XdEntity
import java.io.InputStream

fun TransientEntityStore.requireThreadSession(): TransientStoreSession {
    return threadSession ?: throw IllegalStateException("There's no current session to attach transient entity to.")
}

fun TransientEntity.reattach(): TransientEntity {
    return store.requireThreadSession().newLocalCopy(this)
}

fun XdEntity.reattach() = (entity as TransientEntity).reattach()

fun <T : Comparable<*>> XdEntity.reattachAndGetPrimitiveValue(propertyName: String): T? {
    @Suppress("UNCHECKED_CAST")
    return reattach().getProperty(propertyName) as T?
}

fun <T : Comparable<*>> XdEntity.reattachAndSetPrimitiveValue(propertyName: String, value: T?, clazz: Class<T>) {
    val entity = reattach()
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
    return reattach().getBlob(propertyName)
}

fun XdEntity.reattachAndSetBlob(propertyName: String, value: InputStream?) {
    val entity = reattach()
    if (value == null) {
        entity.deleteBlob(propertyName)
    } else {
        entity.setBlob(propertyName, value)
    }
}

fun XdEntity.reattachAndGetBlobString(propertyName: String): String? {
    return reattach().getBlobString(propertyName)
}

fun XdEntity.reattachAndSetBlobString(propertyName: String, value: String?) {
    val entity = reattach()
    if (value == null) {
        entity.deleteBlob(propertyName)
    } else {
        entity.setBlobString(propertyName, value)
    }
}

fun XdEntity.getOldPrimitiveValue(propertyName: String): Comparable<*>? {
    return (entity as TransientEntity).getPropertyOldValue(propertyName)
}

fun XdEntity.getAddedLinks(linkName: String): EntityIterable {
    return reattach().getAddedLinks(linkName)
}

fun XdEntity.getRemovedLinks(linkName: String): EntityIterable {
    return reattach().getRemovedLinks(linkName)
}

fun XdEntity.getOldLinkValue(linkName: String): TransientEntity? {
    val entity = this.entity as TransientEntity
    val transientStore = entity.store
    val session = transientStore.requireThreadSession()
    return if (session.isRemoved(entity)) {
        (transientStore.persistentStore as PersistentEntityStore)
                .getEntity(entity.id)
                .getLink(linkName)
                ?.let { session.newEntity(it) }
    } else {
        getRemovedLinks(linkName).firstOrNull() as TransientEntity?
    }
}
