package kotlinx.dnq.link

import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

abstract class XdLink<in R, out T: XdEntity>(
        val oppositeEntityType: XdEntityType<T>,
        val dbPropertyName: String?,
        val cardinality: AssociationEndCardinality, val endType: AssociationEndType, val onDelete: OnDeletePolicy, val onTargetDelete: OnDeletePolicy) {

    open val oppositeField: KProperty1<*, *>?
        get() = null

    abstract fun isDefined(thisRef: R, property: KProperty<*>): Boolean
}