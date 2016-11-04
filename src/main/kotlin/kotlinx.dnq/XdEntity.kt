package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId

abstract class XdEntity {
    abstract val entity: Entity

    val entityId: EntityId get() = entity.id

    val isNew: Boolean
        get() = EntityOperations.isNew(entity)

    val isRemoved: Boolean
        get() = EntityOperations.isRemoved(entity)

    open fun delete() {
        EntityOperations.remove(entity)
    }

    override fun equals(other: Any?): Boolean {
        return when {
            this === other -> true
            other !is XdEntity -> false
            else -> EntityOperations.equals(this.entity, other.entity)
        }
    }

    override fun hashCode() = entity.hashCode()
}