package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import jetbrains.exodus.entitystore.metadata.AssociationEndCardinality
import jetbrains.exodus.entitystore.metadata.AssociationEndType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

class XdParentToOneRequiredChildLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>) :
        ReadWriteProperty<R, T>,
        XdLink<R, T>(entityType, null,
                AssociationEndCardinality._1, AssociationEndType.ParentEnd, onDelete = OnDeletePolicy.CASCADE, onTargetDelete = OnDeletePolicy.CLEAR) {

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        val entity = AssociationSemantics.getToOne(thisRef.entity, property.name) ?:
                throw RequiredPropertyUndefinedException(thisRef, property)

        return entityType.wrap(entity)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        AggregationAssociationSemantics.setOneToOne(thisRef.entity, property.name, oppositeField.name, value.entity)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return AssociationSemantics.getToOne(thisRef.entity, property.name) != null
    }
}

