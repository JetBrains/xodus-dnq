package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

class XdParentToOneOptionalChildLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>,
        dbPropertyName: String?
) : ReadWriteProperty<R, T?>, XdLink<R, T>(
        entityType,
        dbPropertyName,
        null,
        AssociationEndCardinality._0_1,
        AssociationEndType.ParentEnd,
        onDelete = OnDeletePolicy.CASCADE,
        onTargetDelete = OnDeletePolicy.CLEAR
) {

    override fun getValue(thisRef: R, property: KProperty<*>): T? {
        return AssociationSemantics.getToOne(thisRef.entity, property.name)?.let { value ->
            entityType.wrap(value)
        }
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T?) {
        AggregationAssociationSemantics.setOneToOne(thisRef.entity, property.name, oppositeField.name, value?.entity)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}

