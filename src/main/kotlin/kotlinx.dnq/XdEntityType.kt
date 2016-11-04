package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.NodeBase
import jetbrains.teamsys.dnq.runtime.queries.QueryOperations
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.XdQueryImpl

abstract class XdEntityType<out T : XdEntity>()  {
    abstract val entityType: String

    fun all(): XdQuery<T> {
        return XdQueryImpl(QueryOperations.queryGetAll(entityType), this)
    }

    fun find(node: NodeBase): XdQuery<T> {
        return XdQueryImpl(QueryOperations.query(entityType, node), this)
    }

    fun emptyQuery(): XdQuery<T> {
        return XdQueryImpl(QueryOperations.empty(entityType), this)
    }

    open fun wrap(entity: Entity): T {
        val xdHierarchyNode = XdModel.getOrThrow(entity.type)

        val entityConstructor = xdHierarchyNode.entityConstructor
                ?: throw UnsupportedOperationException("Constructor for the type ${entity.type} is not found")

        @Suppress("UNCHECKED_CAST")
        return entityConstructor(entity) as T
    }
}