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

fun <XD : XdEntity> XdEntityListener<XD>.asLegacyListener() = let {
    object : IEntityListener<Entity> {
        override fun addedSyncBeforeConstraints(added: Entity) = it.addedSyncBeforeConstraints(added.toXd<XD>())
        override fun addedSyncBeforeFlush(added: Entity) = it.addedSyncBeforeFlush(added.toXd<XD>())
        override fun addedSync(added: Entity) = it.addedSync(added.toXd<XD>())
        override fun addedAsync(added: Entity) = it.addedAsync(added.toXd<XD>())

        override fun updatedSyncBeforeConstraints(old: Entity, current: Entity) = it.updatedSyncBeforeConstraints(old.toXd<XD>(), current.toXd<XD>())
        override fun updatedSyncBeforeFlush(old: Entity, current: Entity) = it.updatedSyncBeforeFlush(old.toXd<XD>(), current.toXd<XD>())
        override fun updatedSync(old: Entity, current: Entity) = it.updatedSync(old.toXd<XD>(), current.toXd<XD>())
        override fun updatedAsync(old: Entity, current: Entity) = it.updatedAsync(old.toXd<XD>(), current.toXd<XD>())

        override fun removedSyncBeforeConstraints(removed: Entity) = it.removedSyncBeforeConstraints(removed.toXd<XD>())
        override fun removedSyncBeforeFlush(removed: Entity) = it.removedSyncBeforeFlush(removed.toXd<XD>())
        override fun removedSync(removed: Entity) = it.removedSync(removed.toXd<XD>())
        override fun removedAsync(removed: Entity) = it.removedAsync(removed.toXd<XD>())
    }
}
