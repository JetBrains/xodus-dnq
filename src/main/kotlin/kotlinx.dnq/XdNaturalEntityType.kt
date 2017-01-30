package kotlinx.dnq

import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer

abstract class XdNaturalEntityType<out T : XdEntity>(
        entityType: String? = null,
        storeContainer: StoreContainer = StaticStoreContainer
) : XdEntityType<T>(storeContainer) {

    override val entityType = entityType ?:
            javaClass.enclosingClass?.simpleName ?:
            throw IllegalArgumentException("Cannot infer entity type for ${javaClass.canonicalName}")

    open fun initEntityType() {
        // Do nothing by default
    }
}
