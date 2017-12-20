package kotlinx.dnq.sample

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.query.size
import kotlinx.dnq.store.container.ThreadLocalStoreContainer
import kotlinx.dnq.store.container.createTransientEntityStore
import kotlinx.dnq.util.initMetaData
import java.io.File


class XdMultiStoreEntity(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<XdMultiStoreEntity>(storeContainer = ThreadLocalStoreContainer)
}

fun main(args: Array<String>) {
    XdModel.registerNodes(XdMultiStoreEntity)

    val userHome = File(System.getProperty("user.home"))
    val store1 = createTransientEntityStore(dbFolder = File(userHome, "tmp/repo1"), environmentName = "repo1")
    initMetaData(XdModel.hierarchy, store1)

    val store2 = createTransientEntityStore(dbFolder = File(userHome, "tmp/repo2"), environmentName = "repo1")
    initMetaData(XdModel.hierarchy, store2)

    ThreadLocalStoreContainer.transactional(store1) {
        XdMultiStoreEntity.new()
        println("Added entity number ${XdMultiStoreEntity.all().size()} to first store")
    }

    ThreadLocalStoreContainer.transactional(store2) {
        XdMultiStoreEntity.new()
        println("Added entity number ${XdMultiStoreEntity.all().size()} to second store")
    }
}