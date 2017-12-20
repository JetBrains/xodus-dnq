package kotlinx.dnq.store.container

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.QueryCancellingPolicy
import kotlinx.dnq.transactional

object ThreadLocalStoreContainer : StoreContainer {
    private val storeThreadLocal = ThreadLocal<TransientEntityStore>()

    override val store: TransientEntityStore
        get() = storeThreadLocal.get() ?: throw IllegalStateException("Current store is undefined")

    fun <T> withStore(store: TransientEntityStore, body: () -> T): T {
        val oldStore = storeThreadLocal.get()
        storeThreadLocal.set(store)
        try {
            return body()
        } finally {
            if (oldStore != null) {
                storeThreadLocal.set(oldStore)
            } else {
                storeThreadLocal.remove()
            }
        }
    }

    fun <T> transactional(
            store: TransientEntityStore,
            readonly: Boolean = false,
            queryCancellingPolicy: QueryCancellingPolicy? = null,
            isNew: Boolean = false, block: (TransientStoreSession) -> T
    ): T = withStore(store) {
        store.transactional(readonly, queryCancellingPolicy, isNew, block)
    }
}
