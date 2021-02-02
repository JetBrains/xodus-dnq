/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.getLinkEx
import com.jetbrains.teamsys.dnq.database.reattachTransient
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStore
import kotlinx.dnq.XdEntity
import java.io.InputStream

fun XdEntity.reattach(session: TransientStoreSession? = null) = entity.reattachTransient(session)

val XdEntity.threadSessionOrThrow: TransientStoreSession get() = entity.threadSessionOrThrow

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
    val session = transientStore.threadSessionOrThrow
    return if (session.isRemoved(entity)) {
        (transientStore.persistentStore as PersistentEntityStore)
                .getEntity(entity.id)
                .getLink(linkName)
                ?.let { session.newEntity(it) }
    } else if (entity.isNew) {
        null
    } else if (!entity.hasChanges(linkName)) {
        entity.persistentEntity.getLink(linkName)?.let { session.newEntity(it) }
    } else {
        getRemovedLinks(linkName).firstOrNull() as TransientEntity?
    }
}

fun XdEntity.reattachAndGetLink(linkName: String): Entity? {
    val session = threadSessionOrThrow
    return reattach(session).getLinkEx(linkName, session)
}

val XdEntity.isReadOnly: Boolean get() = (entity as TransientEntity).isReadonly