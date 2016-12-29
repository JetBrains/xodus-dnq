package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.isNotEmpty
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class XdManyToManyLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy,
        required: Boolean
) : ReadOnlyProperty<R, XdMutableQuery<T>>, XdLink<R, T>(
        entityType,
        dbPropertyName,
        if (required) AssociationEndCardinality._1_n else AssociationEndCardinality._0_n,
        AssociationEndType.UndirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): XdMutableQuery<T> {
        return object : XdMutableQuery<T>(entityType) {
            override val entityIterable: Iterable<Entity>
                get() = AssociationSemantics.getToMany(thisRef.entity, property.name)

            override fun add(entity: T) {
                UndirectedAssociationSemantics.createManyToMany(thisRef.entity, property.name, oppositeField.name, entity.entity)
            }

            override fun remove(entity: T) {
                UndirectedAssociationSemantics.removeManyToMany(thisRef.entity, property.name, oppositeField.name, entity.entity)
            }

            override fun clear() {
                UndirectedAssociationSemantics.clearManyToMany(thisRef.entity, property.name, oppositeField.name)
            }
        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return getValue(thisRef, property).isNotEmpty
    }
}