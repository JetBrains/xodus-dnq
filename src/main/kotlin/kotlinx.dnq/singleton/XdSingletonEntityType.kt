package kotlinx.dnq.singleton

import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer


abstract class XdSingletonEntityType<XD : XdEntity>(entityTypeName: String? = null, storeContainer: StoreContainer = StaticStoreContainer) :
        XdNaturalEntityType<XD>(entityTypeName, storeContainer) {

    fun get() = all().firstOrNull() ?: new {
        initSingleton()
    }

    protected abstract fun XD.initSingleton()

}
