package kotlinx.dnq.link

import jetbrains.exodus.entitystore.Entity

sealed class OnDeletePolicy {
    object FAIL : OnDeletePolicy()
    object CLEAR : OnDeletePolicy()
    object CASCADE : OnDeletePolicy()

    class FAIL_PER_TYPE(
            val message: ((linkedEntities: Iterable<Entity>?, hasMore: Boolean) -> String)? = null) : OnDeletePolicy()

    class FAIL_PER_ENTITY(
            val message: ((linkedEntities: Iterable<Entity>?, entity: Entity?, hasMore: Boolean) -> String)? = null) : OnDeletePolicy()
}