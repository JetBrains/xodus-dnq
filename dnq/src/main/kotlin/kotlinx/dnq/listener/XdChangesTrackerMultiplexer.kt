/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.listener

import jetbrains.exodus.database.DnqListenerTransientData
import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.ITransientChangesMultiplexer
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.orientdb.OEntityId
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.toXd

fun <XD : XdEntity> ITransientChangesMultiplexer.addListener(entityType: XdEntityType<XD>, listener: XdEntityListener<XD>) {
    this.addListener(entityType.entityType, listener.asEntityListener())
}

fun <XD : XdEntity> ITransientChangesMultiplexer.removeListener(entityType: XdEntityType<XD>, listener: XdEntityListener<XD>) {
    this.removeListener(entityType.entityType, listener.asEntityListener())
}

fun <XD : XdEntity> ITransientChangesMultiplexer.addListener(xd: XD, listener: XdEntityListener<XD>) {
    this.addListener(xd.entity, listener.asEntityListener())
}

fun <XD : XdEntity> ITransientChangesMultiplexer.removeListener(xd: XD, listener: XdEntityListener<XD>) {
    this.removeListener(xd.entity, listener.asEntityListener())
}

fun <XD : XdEntity> XdEntityType<XD>.addListener(store: TransientEntityStore, listener: XdEntityListener<XD>) {
    val eventsMultiplexer = store.changesMultiplexer
            ?: throw IllegalStateException("Cannot access eventsMultiplexer")

    eventsMultiplexer.addListener(this, listener)
}

fun <XD : XdEntity> XdEntityType<XD>.removeListener(store: TransientEntityStore, listener: XdEntityListener<XD>) {
    store.changesMultiplexer?.removeListener(this, listener)
}

fun <XD : XdEntity> XdEntityListener<XD>.asEntityListener(): IEntityListener<Entity> = EntityListenerWrapper(this)

internal class EntityListenerWrapper<in XD : XdEntity>(val wrapped: XdEntityListener<XD>) : IEntityListener<Entity> {
    override fun addedSyncBeforeConstraints(added: Entity) = wrapped.addedSyncBeforeConstraints(added.toXd())
    override fun addedSync(added: Entity) = wrapped.addedSync(added.toXd())

    override fun updatedSyncBeforeConstraints(old: Entity, current: Entity) = wrapped.updatedSyncBeforeConstraints(old.toXd(), current.toXd())
    override fun updatedSync(old: Entity, current: Entity) = wrapped.updatedSync(old.toXd(), current.toXd())

    override fun removedSyncBeforeConstraints(
        removed: Entity,
        requestListenerStorage: () -> DnqListenerTransientData<Entity>
    ) {
        wrapped.removedSyncBeforeConstraints(removed.toXd()) { XdDnqListenerTransientData(requestListenerStorage) }
    }

    override fun removedSync(removed: OEntityId, requestListenerStorage: () -> DnqListenerTransientData<Entity>) {
        wrapped.removedSync(removed) { XdDnqListenerTransientData(requestListenerStorage) }
    }

    override fun hashCode() = wrapped.hashCode()
    override fun equals(other: Any?) = other is EntityListenerWrapper<*> && wrapped == other.wrapped
}

internal class XdDnqListenerTransientData<out XD : XdEntity>(private val requestData: () -> DnqListenerTransientData<Entity>): DnqListenerTransientData<XD> {
    override fun <T> getValue(name: String, clazz: Class<T>) = requestData().getValue(name, clazz)

    override fun <T> storeValue(name: String, value: T) = requestData().storeValue(name, value)

    override fun getRemoved(): XD {
        return requestData().getRemoved().toXd<XD>()
    }

    override fun setRemoved(entity: Any) {
        @Suppress("UNCHECKED_CAST")
        requestData().setRemoved((entity as XD).entity)
    }
}
