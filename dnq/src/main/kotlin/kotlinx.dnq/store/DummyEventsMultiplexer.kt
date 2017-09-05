package kotlinx.dnq.store

import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.IEventsMultiplexer
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity

object DummyEventsMultiplexer : IEventsMultiplexer {
    override fun flushed(
            oldChangesTracker: TransientChangesTracker,
            changesDescription: MutableSet<TransientEntityChange>) {
        oldChangesTracker.dispose()
    }

    override fun onClose(transientEntityStore: TransientEntityStore?) = Unit

    override fun addListener(e: Entity, listener: IEntityListener<*>) {
        TODO("not implemented")
    }

    override fun removeListener(e: Entity, listener: IEntityListener<*>) {
        TODO("not implemented")
    }

    override fun addListener(entityType: String, listener: IEntityListener<*>) {
        TODO("not implemented")
    }

    override fun removeListener(entityType: String, listener: IEntityListener<*>) {
        TODO("not implemented")
    }
}
