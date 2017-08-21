package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStore
import kotlinx.dnq.XdEntity
import java.io.InputStream

fun XdEntity.reattach(): TransientEntity {
    val transientEntity = (entity as TransientEntity)
    val threadSession = transientEntity.store.threadSession
            ?: throw IllegalStateException("There's no current session to attach transient entity to.")
    return threadSession.newLocalCopy(transientEntity)
}

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
    return if (EntityOperations.isRemoved(entity)) {
        val transientStore = entity.store
        val session = transientStore.threadSession
        (transientStore.persistentStore as PersistentEntityStore)
                .getEntity(entity.id)
                .getLink(linkName)
                ?.let { session.newEntity(it) }
    } else {
        getRemovedLinks(linkName).firstOrNull() as TransientEntity?
    }
}

fun linkParentWithMultiChild(xdParent: XdEntity?, parentToChildLinkName: String, childToParentLinkName: String, xdChild: XdEntity) {
    val parent = xdParent?.reattach()
    val child = xdChild.reattach()

    if (parent == null) {
        child.removeFromParent(parentToChildLinkName, childToParentLinkName)
    } else {
        // child.childToParent = parent
        parent.addChild(parentToChildLinkName, childToParentLinkName, child)
    }
}

fun linkParentWithSingleChild(xdParent: XdEntity?, parentToChildLinkName: String, childToParentLinkName: String, xdChild: XdEntity?) {
    val parent = xdParent?.reattach()
    val child = xdChild?.reattach()

    when {
        parent != null && child != null -> parent.setChild(parentToChildLinkName, childToParentLinkName, child)
        parent == null && child != null -> child.removeFromParent(parentToChildLinkName, childToParentLinkName)
        parent != null && child == null -> parent.removeChild(parentToChildLinkName, childToParentLinkName)
        parent == null && child == null -> throw IllegalArgumentException("Both entities can't be null.")
    }
}

fun setOneToOne(xd1: XdEntity?, e1Toe2LinkName: String, e2Toe1LinkName: String, xd2: XdEntity?) {
    val e1 = xd1?.reattach()
    val e2 = xd2?.reattach()
    when {
        e1 != null && e2 != null -> e1.setOneToOne(e1Toe2LinkName, e2Toe1LinkName, e2)
        e1 != null && e2 == null -> e1.setOneToOne(e1Toe2LinkName, e2Toe1LinkName, e2)
        e1 == null && e2 != null -> e2.setOneToOne(e2Toe1LinkName, e1Toe2LinkName, e1)
        e1 == null && e2 == null -> throw IllegalArgumentException("Both entities can't be null.")
    }
}