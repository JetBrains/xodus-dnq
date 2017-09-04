package kotlinx.dnq.store.container

import jetbrains.exodus.database.TransientEntityStore

interface StoreContainer {
    val store: TransientEntityStore
}

