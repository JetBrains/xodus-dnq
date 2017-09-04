package kotlinx.dnq.query

import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType

abstract class XdMutableQuery<T : XdEntity>(override val entityType: XdEntityType<T>) : XdQuery<T> {
    abstract fun add(entity: T)
    abstract fun remove(entity: T)
    abstract fun clear()
}