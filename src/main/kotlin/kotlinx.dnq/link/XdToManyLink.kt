package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.metadata.AssociationEndCardinality
import jetbrains.exodus.entitystore.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.isNotEmpty
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

open class XdToManyLink<in R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        dbPropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy,
        required: Boolean
) : ReadOnlyProperty<R, XdMutableQuery<T>>, XdLink<R, T>(
        entityType,
        dbPropertyName,
        if (required) AssociationEndCardinality._1_n else AssociationEndCardinality._0_n,
        AssociationEndType.DirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): XdMutableQuery<T> {
        return object : XdMutableQuery<T>(entityType) {
            override val entityIterable: Iterable<Entity>
                get() = AssociationSemantics.getToMany(thisRef.entity, dbPropertyName ?: property.name)

            override fun add(entity: T) {
                DirectedAssociationSemantics.createToMany(thisRef.entity, dbPropertyName ?: property.name, entity.entity)
            }

            override fun remove(entity: T) {
                DirectedAssociationSemantics.removeToMany(thisRef.entity, dbPropertyName ?: property.name, entity.entity)
            }

            override fun clear() {
                DirectedAssociationSemantics.clearToMany(thisRef.entity, dbPropertyName ?: property.name)
            }
        }
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return getValue(thisRef, property).isNotEmpty
    }
}