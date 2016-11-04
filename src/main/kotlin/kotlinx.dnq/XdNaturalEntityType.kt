package kotlinx.dnq

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.teamsys.dnq.runtime.util.DnqUtils

abstract class XdNaturalEntityType<out T : XdEntity>(entityType: String? = null) : XdEntityType<T>() {
    val entityStore: TransientEntityStore
        get() = DnqUtils.getCurrentTransientSession().store

    override val entityType = entityType ?: run {
        javaClass.enclosingClass?.simpleName
                ?: throw IllegalArgumentException("Cannot infer entity type for ${javaClass.canonicalName}")
    }

    fun new(init: (T.() -> Unit)? = null): T {
        return wrap(entityStore.threadSession.newEntity(entityType)).apply {
            if (init != null) {
                init()
            }
        }
    }
}
