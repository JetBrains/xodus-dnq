package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import jetbrains.exodus.entitystore.metadata.AssociationEndCardinality
import jetbrains.exodus.entitystore.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

class XdOneChildToMultiParentLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>) :
        ReadWriteProperty<R, T?>,
        XdLink<R, T>(entityType, null,
                AssociationEndCardinality._1, AssociationEndType.ChildEnd, onDelete = OnDeletePolicy.CLEAR, onTargetDelete = OnDeletePolicy.CASCADE) {

    override fun getValue(thisRef: R, property: KProperty<*>): T? {
        return AssociationSemantics.getToOne(thisRef.entity, property.name)?.let { value ->
            entityType.wrap(value)
        }
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T?) {
        AggregationAssociationSemantics.setOneToOne(value?.entity, oppositeField.name, property.name, thisRef.entity)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}

