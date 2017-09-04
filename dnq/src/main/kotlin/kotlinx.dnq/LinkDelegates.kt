package kotlinx.dnq

import kotlinx.dnq.link.*
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.util.entityType
import kotlin.reflect.KProperty1

/**
 * Directed [0..1] association
 */
fun <R : XdEntity, T : XdEntity> xdLink0_1(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdToOneOptionalLink<R, T> {
    return XdToOneOptionalLink(entityType, dbPropertyName, onDelete, onTargetDelete)
}

/**
 * Directed [1] association
 */
fun <R : XdEntity, T : XdEntity> xdLink1(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdToOneRequiredLink<R, T> {
    return XdToOneRequiredLink(entityType, dbPropertyName, onDelete, onTargetDelete)
}

/**
 * Directed [0..N] association
 */
fun <R : XdEntity, T : XdEntity> xdLink0_N(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdToManyLink<R, T> {
    return XdToManyLink(entityType, dbPropertyName, onDelete, onTargetDelete, required = false)
}

/**
 * Directed [1..N] association
 */
fun <R : XdEntity, T : XdEntity> xdLink1_N(
        entityType: XdEntityType<T>,
        dbPropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.CLEAR,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdToManyLink<R, T> {
    return XdToManyLink(entityType, dbPropertyName, onDelete, onTargetDelete, required = true)
}

/**
 * Undirected [0..1] association, opposite end is scalar
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_1(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdOneToOneOptionalLink<R, T> {
    return XdOneToOneOptionalLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
}

/**
 * Undirected [1] association, opposite end is scalar
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink1(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdOneToOneRequiredLink<R, T> {
    return XdOneToOneRequiredLink(entityTypeCompanion(), oppositeLink, dbOppositePropertyName, dbPropertyName, onDelete, onTargetDelete)
}

/**
 * Undirected [0..N] association, opposite end is scalar
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_N(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdOneToManyLink<R, T> {
    return XdOneToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = false)
}

/**
 * Undirected [1..N] association, opposite end is scalar
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink1_N(
        oppositeLink: KProperty1<T, R?>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdOneToManyLink<R, T> {
    return XdOneToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = true)
}

/**
 * Undirected [0..1] association, opposite end is vector
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_1(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdManyToOneOptionalLink<R, T> {
    return XdManyToOneOptionalLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
}

/**
 * Undirected [1] association, opposite end is vector
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink1(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdManyToOneRequiredLink<R, T> {
    return XdManyToOneRequiredLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete)
}

/**
 * Undirected [0..N] association, opposite end is vector
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink0_N(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdManyToManyLink<R, T> {
    return XdManyToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = false)
}

/**
 * Undirected [1..N] association, opposite end is vector
 */
inline fun <R : XdEntity, reified T : XdEntity> xdLink1_N(
        oppositeLink: KProperty1<T, XdMutableQuery<R>>,
        dbPropertyName: String? = null,
        dbOppositePropertyName: String? = null,
        onDelete: OnDeletePolicy = OnDeletePolicy.FAIL,
        onTargetDelete: OnDeletePolicy = OnDeletePolicy.FAIL): XdManyToManyLink<R, T> {
    return XdManyToManyLink(entityTypeCompanion(), oppositeLink, dbPropertyName, dbOppositePropertyName, onDelete, onTargetDelete, required = true)
}

/**
 * Parent end [0..1] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChild0_1(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null): XdParentToOneOptionalChildLink<R, T> {
    return XdParentToOneOptionalChildLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
}

/**
 * Parent end [1] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChild1(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null): XdParentToOneRequiredChildLink<R, T> {
    return XdParentToOneRequiredChildLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
}

/**
 * Parent end [0..N] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChildren0_N(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null): XdParentToManyChildrenLink<R, T> {
    return XdParentToManyChildrenLink(entityTypeCompanion(), oppositeLink, dbPropertyName, required = false)
}

/**
 * Parent end [1..N] of aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdChildren1_N(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null): XdParentToManyChildrenLink<R, T> {
    return XdParentToManyChildrenLink(entityTypeCompanion(), oppositeLink, dbPropertyName, required = true)
}

/**
 * Child end of scalar aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdParent(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null): XdOneChildToParentLink<R, T> {
    return XdOneChildToParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
}

/**
 * Child end of scalar aggregation association.
 * Should be used if entity has several parent links
 */
inline fun <R : XdEntity, reified T : XdEntity> xdMultiParent(oppositeLink: KProperty1<T, R?>, dbPropertyName: String? = null): XdOneChildToMultiParentLink<R, T> {
    return XdOneChildToMultiParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
}

/**
 * Child end of vector aggregation association
 */
inline fun <R : XdEntity, reified T : XdEntity> xdParent(oppositeLink: KProperty1<T, XdMutableQuery<R>>, dbPropertyName: String? = null): XdManyChildrenToParentLink<R, T> {
    return XdManyChildrenToParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
}

/**
 * Child end of vector aggregation association
 * Should be used if entity has several parent links
 */
inline fun <R : XdEntity, reified T : XdEntity> xdMultiParent(oppositeLink: KProperty1<T, XdMutableQuery<R>>, dbPropertyName: String? = null): XdManyChildrenToMultiParentLink<R, T> {
    return XdManyChildrenToMultiParentLink(entityTypeCompanion(), oppositeLink, dbPropertyName)
}

inline fun <reified T : XdEntity> entityTypeCompanion(): XdEntityType<T> = T::class.entityType