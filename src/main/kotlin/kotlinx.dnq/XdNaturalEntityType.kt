package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.BasePersistentClassImpl
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.store.container.StoreContainer
import kotlinx.dnq.util.CommonBasePersistentClass

abstract class XdNaturalEntityType<out T : XdEntity>(
        entityType: String? = null,
        storeContainer: StoreContainer = StaticStoreContainer,
        val persistentClassInstance: BasePersistentClassImpl = CommonBasePersistentClass
) : XdEntityType<T>(storeContainer) {
    override val entityType = entityType ?: run {
        javaClass.enclosingClass?.simpleName
                ?: throw IllegalArgumentException("Cannot infer entity type for ${javaClass.canonicalName}")
    }
}
