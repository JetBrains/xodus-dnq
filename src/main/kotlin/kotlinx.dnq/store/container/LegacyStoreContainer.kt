package kotlinx.dnq.store.container

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.teamsys.dnq.runtime.util.DnqUtils

object LegacyStoreContainer : StoreContainer {
    override val store: TransientEntityStore
        get() = DnqUtils.getTransientStore()
}