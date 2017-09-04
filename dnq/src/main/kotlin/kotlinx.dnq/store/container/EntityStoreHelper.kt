package kotlinx.dnq.store.container

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.exodus.query.metadata.ModelMetaDataImpl
import jetbrains.exodus.env.EnvironmentConfig
import jetbrains.exodus.env.Environments
import jetbrains.teamsys.dnq.runtime.queries.TransientSortEngine
import kotlinx.dnq.store.DummyEventsMultiplexer
import kotlinx.dnq.store.XdQueryEngine
import java.io.File

fun createTransientEntityStore(dbFolder: File, environmentName: String, configure: EnvironmentConfig.() -> Unit = {}): TransientEntityStoreImpl {
    return TransientEntityStoreImpl().apply {
        val store = this
        val environment = Environments.newInstance(dbFolder, EnvironmentConfig().apply(configure))
        val persistentStore = PersistentEntityStoreImpl(environment, environmentName)
        this.persistentStore = persistentStore;
        this.modelMetaData = ModelMetaDataImpl()
        this.eventsMultiplexer = DummyEventsMultiplexer
        this.queryEngine = XdQueryEngine(store).apply {
            this.sortEngine = TransientSortEngine(this).apply {
                setEntityStore(store)
            }
        }
    }
}