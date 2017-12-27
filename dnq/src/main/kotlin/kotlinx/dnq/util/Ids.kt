package kotlinx.dnq.util

import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.session
import kotlinx.dnq.toXd

@Throws(EntityRemovedInDatabaseException::class)
fun <T : XdEntity> XdEntityType<T>.findById(xdId: String): T {
    return entityStore.session.let {
        it.getEntity(it.toEntityId(xdId)).toXd()
    }
}