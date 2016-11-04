package kotlinx.dnq.store.container

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.env.EnvironmentConfig
import kotlinx.dnq.store.container.createTransientEntityStore
import java.io.File

object StaticStoreContainer : StoreContainer {
    private var _store: TransientEntityStore? = null

    override var store: TransientEntityStore
        get() {
            return _store ?: throw IllegalStateException("Transient store is not initialized")
        }
        set(value) {
            this._store = value
        }

    fun init(dbFolder: File, environmentName: String, configure: EnvironmentConfig.() -> Unit = {}): TransientEntityStoreImpl {
        val store = createTransientEntityStore(dbFolder, environmentName, configure)
        this.store = store
        return store
    }
}