package kotlinx.dnq.link

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics
import jetbrains.exodus.query.metadata.AssociationEndCardinality
import jetbrains.exodus.query.metadata.AssociationEndType
import kotlinx.dnq.RequiredPropertyUndefinedException
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

class XdOneToOneRequiredLink<R : XdEntity, T : XdEntity>(
        val entityType: XdEntityType<T>,
        override val oppositeField: KProperty1<T, R?>,
        dbPropertyName: String?,
        dbOppositePropertyName: String?,
        onDeletePolicy: OnDeletePolicy,
        onTargetDeletePolicy: OnDeletePolicy
) : ReadWriteProperty<R, T>, XdLink<R, T>(
        entityType,
        dbPropertyName,
        dbOppositePropertyName,
        AssociationEndCardinality._1,
        AssociationEndType.UndirectedAssociationEnd,
        onDeletePolicy,
        onTargetDeletePolicy
) {

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        val entity = AssociationSemantics.getToOne(thisRef.entity, dbPropertyName ?: property.name) ?:
                throw RequiredPropertyUndefinedException(thisRef, property)
        return entityType.wrap(entity)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        UndirectedAssociationSemantics.setOneToOne(thisRef.entity, dbPropertyName ?: property.name, dbOppositePropertyName ?: oppositeField.name, value.entity)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>): Boolean {
        return AssociationSemantics.getToOne(thisRef.entity, dbPropertyName ?: property.name) != null
    }
}

