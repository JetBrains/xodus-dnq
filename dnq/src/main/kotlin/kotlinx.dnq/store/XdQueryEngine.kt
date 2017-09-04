package kotlinx.dnq.store

import com.jetbrains.teamsys.dnq.database.EntityIterableWrapper
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import jetbrains.exodus.entitystore.iterate.SingleEntityIterable
import jetbrains.exodus.query.QueryEngine
import kotlinx.dnq.util.reattach
import kotlinx.dnq.util.requireThreadSession

class XdQueryEngine(val store: TransientEntityStore) :
        QueryEngine(store.modelMetaData, store.persistentStore as PersistentEntityStoreImpl) {

    private val session get() = store.requireThreadSession()

    override fun isWrapped(it: Iterable<Entity>?): Boolean {
        return it is EntityIterableWrapper
    }

    override fun wrap(it: EntityIterable): EntityIterable {
        return session.createPersistentEntityIterableWrapper(it)
    }

    override fun wrap(entity: Entity): Iterable<Entity>? {
        return (entity as? TransientEntity)
                ?.takeIf { it.isSaved }
                ?.reattach()
                ?.takeUnless { session.isRemoved(it) }
                ?.takeIf { it.isSaved }
                ?.let { SingleEntityIterable(session.persistentTransaction as PersistentStoreTransaction, it.id) }
    }
}
