package kotlinx.dnq.creator

import jetbrains.exodus.database.EntityCreator
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.firstOrNull


fun <XD : XdEntity> XdEntityType<XD>.findOrNew(findQuery: XdQuery<XD>, initNew: XD.() -> Unit): XD {
    val entityCreator = object : EntityCreator(entityType) {
        override fun find() = findQuery.firstOrNull()?.entity

        override fun created(entity: Entity) {
            wrap(entity).initNew()
        }

    }
    return wrap(storeContainer.store.threadSession.newEntity(entityCreator))
}