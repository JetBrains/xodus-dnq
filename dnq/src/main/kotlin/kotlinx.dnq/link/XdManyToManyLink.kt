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

open class XdManyToManyLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String?,
        dbOppositePropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy,
        required: Boolean
) : ReadOnlyProperty<R, XdMutableQuery<T>>, XdLink<R, T>(
        entityType,
        dbPropertyName,
        dbOppositePropertyName,
        if (required) AssociationEndCardinality._1_n else AssociationEndCardinality._0_n,
        AssociationEndType.UndirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): XdMutableQuery<T> {
        return object : XdMutableQuery<T>(entityType) {
            override val entityIterable: Iterable<Entity>
                get() = thisRef.reattach().getLinks(property.dbName)

            override fun add(entity: T) {
                thisRef.reattach().createManyToMany(property.dbName, dbOppositePropertyName ?: oppositeField.name, entity.reattach())
            }

            override fun remove(entity: T) {
                thisRef.reattach().deleteLink(property.dbName, entity.reattach())
                entity.reattach().deleteLink(dbOppositePropertyName ?: oppositeField.name, thisRef.reattach())
            }

            override fun clear() {
                thisRef.reattach().clearManyToMany(property.dbName, dbOppositePropertyName ?: oppositeField.name)
            }
        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return getValue(thisRef, property).isNotEmpty
    }
}