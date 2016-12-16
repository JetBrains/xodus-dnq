package kotlinx.dnq

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.query.*
import kotlinx.dnq.store.container.StoreContainer

abstract class XdEntityType<out T : XdEntity>(val storeContainer: StoreContainer) {
    abstract val entityType: String
    val entityStore: TransientEntityStore
        get() = storeContainer.store

    fun all(): XdQuery<T> {
        return XdQueryImpl(entityStore.queryEngine.queryGetAll(entityType), this)
    }

    fun where(clause: T.() -> Unit): XdQuery<T> {
        val query = XdQueryImpl(entityStore.queryEngine.queryGetAll(entityType), this)
        var temp: XdQuery<T> = query
        SearchingEntity(entityType, entityStore).inScope {
            wrap(this).clause()
        }.nodes.forEach {
            temp = temp.query(it)
        }
        return temp
    }

    fun new(init: (T.() -> Unit)? = null): T {
        val transaction = (entityStore.threadSession
                ?: throw IllegalStateException("New entities can be created only in transactional block"))
        return wrap(transaction.newEntity(entityType)).apply {
            if (init != null) {
                init()
            }
        }
    }

    open fun wrap(entity: Entity): T {
        val xdHierarchyNode = XdModel.getOrThrow(entity.type)

        val entityConstructor = xdHierarchyNode.entityConstructor
                ?: throw UnsupportedOperationException("Constructor for the type ${entity.type} is not found")

        @Suppress("UNCHECKED_CAST")
        return entityConstructor(entity) as T
    }
}