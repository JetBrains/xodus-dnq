package kotlinx.dnq.util

import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.toXd

@Throws(EntityRemovedInDatabaseException::class)
fun <T : XdEntity> XdEntityType<T>.findById(xdId: String): T {
    val tx = (entityStore.threadSession
            ?: throw IllegalStateException("finding entities can be called only in transactional block"))
    return tx.getEntity(tx.toEntityId(xdId)).toXd()
}