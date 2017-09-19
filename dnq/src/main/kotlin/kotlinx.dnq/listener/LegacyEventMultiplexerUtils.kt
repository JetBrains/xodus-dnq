/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package kotlinx.dnq.listener

import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.IEventsMultiplexer
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.toXd

fun <XD : XdEntity> IEventsMultiplexer.addListener(entityType: XdEntityType<XD>, listener: XdEntityListener<XD>) {
    this.addListener(entityType.entityType, listener.asLegacyListener())
}

fun <XD : XdEntity> IEventsMultiplexer.removeListener(entityType: XdEntityType<XD>, listener: XdEntityListener<XD>) {
    this.removeListener(entityType.entityType, listener.asLegacyListener())
}

fun <XD : XdEntity> IEventsMultiplexer.addListener(xd: XD, listener: XdEntityListener<XD>) {
    this.addListener(xd.entity, listener.asLegacyListener())
}

fun <XD : XdEntity> IEventsMultiplexer.removeListener(xd: XD, listener: XdEntityListener<XD>) {
    this.removeListener(xd.entity, listener.asLegacyListener())
}

fun <XD : XdEntity> XdEntityType<XD>.addListener(store: TransientEntityStore, listener: XdEntityListener<XD>) {
    val eventsMultiplexer = store.eventsMultiplexer
            ?: throw IllegalStateException("Cannot access eventsMultiplexer")

    eventsMultiplexer.addListener(this, listener)
}

fun <XD : XdEntity> XdEntityType<XD>.removeListener(store: TransientEntityStore, listener: XdEntityListener<XD>) {
    store.eventsMultiplexer?.removeListener(this, listener)
}

fun <XD : XdEntity> XdEntityListener<XD>.asLegacyListener(): IEntityListener<Entity> = EntityListenerWrapper(this)

internal class EntityListenerWrapper<XD : XdEntity>(val wrapped: XdEntityListener<XD>) : IEntityListener<Entity> {
    override fun addedSyncBeforeConstraints(added: Entity) = wrapped.addedSyncBeforeConstraints(added.toXd<XD>())
    override fun addedSyncBeforeFlush(added: Entity) = wrapped.addedSyncBeforeFlush(added.toXd<XD>())
    override fun addedSync(added: Entity) = wrapped.addedSync(added.toXd<XD>())
    override fun addedAsync(added: Entity) = wrapped.addedAsync(added.toXd<XD>())

    override fun updatedSyncBeforeConstraints(old: Entity, current: Entity) = wrapped.updatedSyncBeforeConstraints(old.toXd<XD>(), current.toXd<XD>())
    override fun updatedSyncBeforeFlush(old: Entity, current: Entity) = wrapped.updatedSyncBeforeFlush(old.toXd<XD>(), current.toXd<XD>())
    override fun updatedSync(old: Entity, current: Entity) = wrapped.updatedSync(old.toXd<XD>(), current.toXd<XD>())
    override fun updatedAsync(old: Entity, current: Entity) = wrapped.updatedAsync(old.toXd<XD>(), current.toXd<XD>())

    override fun removedSyncBeforeConstraints(removed: Entity) = wrapped.removedSyncBeforeConstraints(removed.toXd<XD>())
    override fun removedSyncBeforeFlush(removed: Entity) = wrapped.removedSyncBeforeFlush(removed.toXd<XD>())
    override fun removedSync(removed: Entity) = wrapped.removedSync(removed.toXd<XD>())
    override fun removedAsync(removed: Entity) = wrapped.removedAsync(removed.toXd<XD>())

    override fun hashCode() = wrapped.hashCode()

    override fun equals(other: Any?) = other is EntityListenerWrapper<*> && wrapped == other.wrapped
}
