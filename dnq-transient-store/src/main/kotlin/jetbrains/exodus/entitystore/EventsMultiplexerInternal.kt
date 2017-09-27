package jetbrains.exodus.entitystore

import jetbrains.exodus.database.EntityChangeType.*
import jetbrains.exodus.database.IEntityListener
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.entitystore.Where.*
import java.util.*

internal fun handleChange(
        where: Where,
        c: TransientEntityChange,
        listeners: Queue<IEntityListener<Entity>>
) = when (where) {
    SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS -> when (c.changeType) {
        ADD -> listeners.visit(true) { it.addedSyncBeforeConstraints(c.transientEntity) }
        UPDATE -> listeners.visit(true) { it.updatedSyncBeforeConstraints(c.snaphotEntity, c.transientEntity) }
        REMOVE -> listeners.visit(true) { it.removedSyncBeforeConstraints(c.snaphotEntity) }
    }
    SYNC_BEFORE_FLUSH_AFTER_CONSTRAINTS -> when (c.changeType) {
        ADD -> listeners.visit { it.addedSyncAfterConstraints(c.transientEntity) }
        UPDATE -> listeners.visit { it.updatedSyncAfterConstraints(c.snaphotEntity, c.transientEntity) }
        REMOVE -> listeners.visit { it.removedSyncAfterConstraints(c.snaphotEntity) }
    }
    SYNC_AFTER_FLUSH -> when (c.changeType) {
        ADD -> listeners.visit { it.addedSync(c.transientEntity) }
        UPDATE -> listeners.visit { it.updatedSync(c.snaphotEntity, c.transientEntity) }
        REMOVE -> listeners.visit { it.removedSync(c.snaphotEntity) }
    }
    ASYNC_AFTER_FLUSH -> when (c.changeType) {
        ADD -> listeners.visit { it.addedAsync(c.transientEntity) }
        UPDATE -> listeners.visit { it.updatedAsync(c.snaphotEntity, c.transientEntity) }
        REMOVE -> listeners.visit { it.removedAsync(c.snaphotEntity) }
    }
}

private fun Queue<IEntityListener<Entity>>.visit(rethrow: Boolean = false, action: (IEntityListener<Entity>) -> Unit) {
    for (l in this) {
        try {
            action(l)
        } catch (e: Exception) {
            // rethrow exception only for beforeFlush listeners
            if (rethrow) {
                if (e is RuntimeException) {
                    throw e
                }
                throw RuntimeException(e)
            } else {
                if (EventsMultiplexer.logger.isErrorEnabled) {
                    EventsMultiplexer.logger.error("Exception while notifying entity listener.", e)
                }
            }
        }
    }
}

internal class FullEntityId(store: EntityStore, id: EntityId) {
    private val storeHashCode: Int = System.identityHashCode(store)
    private val entityTypeId: Int = id.typeId
    private val entityLocalId: Long = id.localId

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FullEntityId) {
            return false
        }
        if (storeHashCode != other.storeHashCode) {
            return false
        }
        return if (entityLocalId != other.entityLocalId) {
            false
        } else entityTypeId == other.entityTypeId
    }

    override fun hashCode(): Int {
        var result = storeHashCode
        result = 31 * result + entityTypeId
        result = 31 * result + (entityLocalId xor (entityLocalId shr 32)).toInt()
        return result
    }

    override fun toString(): String {
        val builder = StringBuilder(10)
        toString(builder)
        return builder.toString()
    }

    fun toString(builder: StringBuilder) {
        builder.append(entityTypeId)
        builder.append('-')
        builder.append(entityLocalId)
        builder.append('@')
        builder.append(storeHashCode)
    }
}