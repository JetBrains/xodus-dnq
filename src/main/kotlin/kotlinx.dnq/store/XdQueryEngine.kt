package kotlinx.dnq.store

import com.jetbrains.teamsys.dnq.database.EntityIterableWrapper
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.exodus.entitystore.PersistentStoreTransaction
import jetbrains.exodus.entitystore.iterate.SingleEntityIterable
import jetbrains.exodus.query.QueryEngine

class XdQueryEngine(val store: TransientEntityStore) :
        QueryEngine(store.modelMetaData, store.persistentStore as PersistentEntityStoreImpl) {

    val session: TransientStoreSession get() = store.threadSession

    override fun isWrapped(it: Iterable<Entity>?): Boolean {
        return it is EntityIterableWrapper
    }

    override fun wrap(it: EntityIterable): EntityIterable {
        return session.createPersistentEntityIterableWrapper(it)
    }

    override fun wrap(entity: Entity): Iterable<Entity>? {
        if ((entity is TransientEntity) && entity.isSaved) {
            val _e = TransientStoreUtil.reattach(entity)!!
            if (!(TransientStoreUtil.isRemoved(_e)) && _e.isSaved) {
                return SingleEntityIterable(session.persistentTransaction as PersistentStoreTransaction, _e.id)
            }
        }
        return null
    }
}
