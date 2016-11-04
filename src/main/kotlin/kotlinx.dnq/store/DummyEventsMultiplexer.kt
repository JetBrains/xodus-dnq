package kotlinx.dnq.store

import com.jetbrains.teamsys.dnq.database.IEventsMultiplexer
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientEntityStore

object DummyEventsMultiplexer : IEventsMultiplexer {
    override fun flushed(
            oldChangesTracker: TransientChangesTracker,
            changesDescription: MutableSet<TransientEntityChange>) {
        oldChangesTracker.dispose()
    }

    override fun onClose(transientEntityStore: TransientEntityStore?) = Unit
}