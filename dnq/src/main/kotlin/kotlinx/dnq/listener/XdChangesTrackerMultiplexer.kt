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

import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.ITransientChangesMultiplexer
import jetbrains.exodus.database.RemovedEntityData
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.toXd
import kotlin.reflect.KProperty

fun <XD : XdEntity> ITransientChangesMultiplexer.addListener(
    entityType: XdEntityType<XD>,
    listener: XdEntityListener<XD>
) {
    this.addListener(entityType.entityType, listener.asEntityListener())
}

fun <XD : XdEntity> ITransientChangesMultiplexer.removeListener(
    entityType: XdEntityType<XD>,
    listener: XdEntityListener<XD>
) {
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

    override fun updatedSyncBeforeConstraints(old: Entity, current: Entity) =
        wrapped.updatedSyncBeforeConstraints(old.toXd(), current.toXd())

    override fun updatedSync(old: Entity, current: Entity) = wrapped.updatedSync(old.toXd(), current.toXd())

    override fun removedSyncBeforeConstraints(removed: Entity, removedEntityData: RemovedEntityData<Entity>) {
        wrapped.removedSyncBeforeConstraints(removed.toXd(), XdRemovedEntityData(removedEntityData))
    }

    override fun removedSync(removedEntityData: RemovedEntityData<Entity>) {
        wrapped.removedSync(XdRemovedEntityData(removedEntityData))
    }

    override fun hashCode() = wrapped.hashCode()
    override fun equals(other: Any?) = other is EntityListenerWrapper<*> && wrapped == other.wrapped
}

internal class XdRemovedEntityData<out XD : XdEntity>(private val data: RemovedEntityData<Entity>) :
    RemovedEntityData<XD> {
    override val removed = data.removed.toXd<XD>()
    override val removedId = data.removedId

    override fun <T> getValue(name: String): T? {
        return data.getValue(name)
    }

    override fun <T> getValue(property: KProperty<T>): T? {
        return data.getValue(property)
    }

    override fun <T> storeValue(name: String, value: T) {
        return data.storeValue(name, value)
    }

    override fun <T> storeValue(property: KProperty<T>, value: T) {
        return data.storeValue(property, value)
    }
}



