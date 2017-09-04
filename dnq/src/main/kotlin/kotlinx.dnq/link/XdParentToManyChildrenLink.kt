package kotlinx.dnq.link

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.isNotEmpty
import kotlinx.dnq.util.reattach
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class XdParentToManyChildrenLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>,
        dbPropertyName: String?,
        required: Boolean
) : ReadOnlyProperty<R, XdMutableQuery<T>>, XdLink<R, T>(
        entityType,
        dbPropertyName,
        null,
        if (required) AssociationEndCardinality._1_n else AssociationEndCardinality._0_n,
        AssociationEndType.ParentEnd,
        onDelete = OnDeletePolicy.CASCADE,
        onTargetDelete = OnDeletePolicy.CLEAR
) {

    override fun getValue(thisRef: R, property: KProperty<*>): XdMutableQuery<T> {
        return object : XdMutableQuery<T>(entityType) {
            override val entityIterable: Iterable<Entity>
                get() = thisRef.reattach().getLinks(property.name)

            override fun add(entity: T) {
                thisRef.reattach().addChild(property.name, oppositeField.name, entity.reattach())
            }

            override fun remove(entity: T) {
                entity.reattach().removeFromParent(property.name, oppositeField.name)
            }

            override fun clear() {
                thisRef.reattach().clearChildren(property.name)
            }
        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return getValue(thisRef, property).isNotEmpty
    }
}