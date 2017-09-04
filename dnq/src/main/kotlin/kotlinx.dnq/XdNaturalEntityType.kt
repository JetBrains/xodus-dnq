package kotlinx.dnq

import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer
import kotlin.reflect.KProperty1

abstract class XdNaturalEntityType<T : XdEntity>(
        entityType: String? = null,
        storeContainer: StoreContainer = StaticStoreContainer
) : XdEntityType<T>(storeContainer) {

    open val compositeIndices = emptyList<List<KProperty1<T, *>>>()

    override val entityType = entityType ?:
            javaClass.enclosingClass?.simpleName ?:
            throw IllegalArgumentException("Cannot infer entity type for ${javaClass.canonicalName}")

    open fun initEntityType() {
        // Do nothing by default
    }
}
