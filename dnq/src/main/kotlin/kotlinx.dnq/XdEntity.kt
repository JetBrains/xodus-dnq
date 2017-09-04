package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import kotlinx.dnq.util.reattach
import kotlinx.dnq.util.requireThreadSession

abstract class XdEntity {
    abstract val entity: Entity

    val entityId: EntityId get() = entity.id

    val isNew: Boolean
        get() = reattach().isNew

    val isRemoved: Boolean
        get() = (entity as TransientEntity).store.requireThreadSession().isRemoved(entity)

    open fun delete() {
        EntityOperations.remove(entity)
    }

    // FIXME: this trigger is fired only for natural entities, should it be called for legacy entities also?
    open fun beforeFlush() {}

    // FIXME: this trigger is fired only for natural entities, should it be called for legacy entities also?
    open fun destructor() {}

    open fun constructor() {}

    override fun equals(other: Any?): Boolean {
        return when {
            this === other -> true
            other !is XdEntity -> false
            else -> this.entity == other.entity
        }
    }

    override fun hashCode() = entity.hashCode()
}