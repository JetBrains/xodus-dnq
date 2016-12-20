package kotlinx.dnq

import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer

abstract class XdNaturalEntityType<out T : XdEntity>(
        entityType: String? = null,
        storeContainer: StoreContainer = StaticStoreContainer
) : XdEntityType<T>(storeContainer) {
    override val entityType = entityType ?: run {
        javaClass.enclosingClass?.simpleName
                ?: throw IllegalArgumentException("Cannot infer entity type for ${javaClass.canonicalName}")
    }
}
