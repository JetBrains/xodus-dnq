package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics
import jetbrains.exodus.entitystore.metadata.AssociationEndCardinality
import jetbrains.exodus.entitystore.metadata.AssociationEndType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class XdToOneRequiredLink<in R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        dbPropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy) :
        ReadWriteProperty<R, T>,
        XdLink<R, T>(entityType, dbPropertyName,
                AssociationEndCardinality._1, AssociationEndType.DirectedAssociationEnd, onDeletePolicy, onTargetDeletePolicy) {

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        val entity = AssociationSemantics.getToOne(thisRef.entity, dbPropertyName ?: property.name) ?:
                throw RequiredPropertyUndefinedException(thisRef, property)
        return entityType.wrap(entity)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        DirectedAssociationSemantics.setToOne(thisRef.entity, dbPropertyName ?: property.name, value.entity)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return AssociationSemantics.getToOne(thisRef.entity, dbPropertyName ?: property.name) != null
    }
}