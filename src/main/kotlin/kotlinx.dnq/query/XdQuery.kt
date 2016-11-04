package kotlinx.dnq.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.query.NodeBase
import jetbrains.exodus.query.SortByLinkProperty
import jetbrains.exodus.query.SortByProperty
import jetbrains.teamsys.dnq.runtime.queries.QueryOperations
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.util.entityType
import kotlinx.dnq.util.getDBName
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaType

interface XdQuery<out T : XdEntity> {
    val entityType: XdEntityType<T>
    val entityIterable: Iterable<Entity>
}

class XdQueryImpl<out T : XdEntity>(
        override val entityIterable: Iterable<Entity>,
        override val entityType: XdEntityType<T>) : XdQuery<T>

fun <T : XdEntity> Iterable<Entity>?.asQuery(entityType: XdEntityType<T>): XdQuery<T> {
    return if (this != null) {
        XdQueryImpl(this, entityType)
    } else {
        XdQueryImpl(EntityIterableBase.EMPTY, entityType)
    }
}

fun <T : XdEntity> XdQuery<T>.asSequence(): Sequence<T> {
    return entityIterable.asSequence().map { entityType.wrap(it) }
}

fun <T : XdEntity> XdEntityType<T>.queryOf(vararg elements: T?): XdQuery<T> {
    val union = elements.fold<T?, Iterable<Entity>>(EntityIterableBase.EMPTY) { union, element ->
        if (element != null) {
            QueryOperations.union(union, QueryOperations.singleton(element.entity))
        } else {
            union
        }
    }
    return XdQueryImpl(union, this)
}

infix fun <T : XdEntity> XdQuery<T>.intersect(that: XdQuery<T>): XdQuery<T> {
    val result = QueryOperations.intersect(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

infix fun <T : XdEntity> XdQuery<T>.union(that: XdQuery<T>): XdQuery<T> {
    val result = QueryOperations.union(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

operator fun <T : XdEntity> XdQuery<T>.plus(that: XdQuery<T>): XdQuery<T> {
    val result = QueryOperations.concat(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

infix fun <T : XdEntity> XdQuery<T>.exclude(that: XdQuery<T>): XdQuery<T> {
    val result = QueryOperations.exclude(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

fun <T : XdEntity> XdQuery<T>.query(node: NodeBase): XdQuery<T> {
    return QueryOperations.query(entityIterable, entityType.entityType, node).asQuery(entityType)
}

fun <T : XdEntity, S : T> XdQuery<T>.filterIsInstance(entityType: XdEntityType<S>): XdQuery<S> {
    val allOfTargetType = QueryOperations.queryGetAll(entityType.entityType)
    return QueryOperations.intersect(allOfTargetType, this.entityIterable).asQuery(entityType)
}

fun <T : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(property: KProperty1<T, V>, asc: Boolean = true): XdQuery<T> {
    return QueryOperations.query(entityIterable, entityType.entityType, SortByProperty(null, property.getDBName(entityType), asc)).asQuery(entityType)
}

inline fun <reified T : XdEntity, reified S : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(linkProperty: KProperty1<T, S>, property: KProperty1<S, V>, asc: Boolean = true): XdQuery<T> {
    return sortedBy(T::class, linkProperty, S::class, property, asc)
}

fun <T : XdEntity, S : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(klass: KClass<T>, linkProperty: KProperty1<T, S>, linkKlass: KClass<S>, property: KProperty1<S, V>, asc: Boolean = true): XdQuery<T> {
    return QueryOperations.query(entityIterable, entityType.entityType, SortByLinkProperty(null, linkKlass.java.entityType.entityType, property.getDBName(linkKlass), linkProperty.getDBName(klass), asc)).asQuery(entityType)
}

fun <T : XdEntity> XdQuery<T>?.size(): Int {
    return QueryOperations.getSize(this?.entityIterable)
}

fun <T : XdEntity> XdQuery<T>?.roughSize(): Int {
    return QueryOperations.roughSize(this?.entityIterable)
}

fun <T : XdEntity> XdQuery<T>?.size(node: NodeBase): Int {
    return this?.query(node).size()
}

val <T : XdEntity> XdQuery<T>?.isEmpty: Boolean
    get() = QueryOperations.isEmpty(this?.entityIterable)

val <T : XdEntity> XdQuery<T>?.isNotEmpty: Boolean
    get() = !isEmpty

fun <T : XdEntity> XdQuery<T>.drop(n: Int): XdQuery<T> {
    return QueryOperations.skip(entityIterable, n).asQuery(entityType)
}

fun <T : XdEntity> XdQuery<T>.take(n: Int): XdQuery<T> {
    return QueryOperations.take(entityIterable, n).asQuery(entityType)
}

fun <T : XdEntity> XdQuery<T>.distinct(): XdQuery<T> {
    return QueryOperations.distinct(entityIterable).asQuery(entityType)
}

inline fun <reified S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(field: KProperty1<S, T?>): XdQuery<T> {
    @Suppress("UNCHECKED_CAST")
    return QueryOperations.selectDistinct(entityIterable, field.getDBName(S::class)).asQuery((field.returnType.javaType as Class<T>).entityType)
}

inline fun <reified S : XdEntity, T : XdEntity> XdQuery<S>.flatMapDistinct(field: KProperty1<S, T>): XdQuery<T> {
    @Suppress("UNCHECKED_CAST")
    return QueryOperations.selectManyDistinct(entityIterable, field.getDBName(S::class)).asQuery((field.returnType.javaType as Class<T>).entityType)
}

fun <T : XdEntity> XdQuery<T>.indexOf(entity: Entity?): Int {
    return QueryOperations.indexOf(entityIterable, entity)
}

operator fun <T : XdEntity> XdQuery<T>.contains(entity: Entity?): Boolean {
    return QueryOperations.contains(entityIterable, entity)
}

operator fun <T : XdEntity> XdQuery<T>.contains(entity: XdEntity?): Boolean {
    return QueryOperations.contains(entityIterable, entity?.entity)
}

fun <T : XdEntity> XdQuery<T>.first(): T {
    return QueryOperations.getFirst(entityIterable)?.let {
        entityType.wrap(it)
    } ?: throw NoSuchElementException("Query is empty.")
}

fun <T : XdEntity> XdQuery<T>.first(node: NodeBase): T {
    return query(node).first()
}

fun <T : XdEntity> XdQuery<T>.firstOrNull(): T? {
    return QueryOperations.getFirst(entityIterable)?.let {
        entityType.wrap(it)
    }
}

fun <T : XdEntity> XdQuery<T>.firstOrNull(node: NodeBase): T? {
    return query(node).firstOrNull()
}

fun <T : XdEntity> XdQuery<T>.single(): T {
    return asSequence().single()
}

fun <T : XdEntity> XdQuery<T>.single(node: NodeBase): T {
    return query(node).single()
}

fun <T : XdEntity> XdQuery<T>.singleOrNull(): T? {
    return asSequence().singleOrNull()
}

fun <T : XdEntity> XdQuery<T>.singleOrNull(node: NodeBase): T? {
    return query(node).singleOrNull()
}

fun <T : XdEntity> XdQuery<T>.any() = isNotEmpty

fun <T : XdEntity> XdQuery<T>.any(node: NodeBase): Boolean {
    return query(node).any()
}

fun <T : XdEntity> XdQuery<T>.none() = isEmpty

fun <T : XdEntity> XdQuery<T>.none(node: NodeBase): Boolean {
    return query(node).asSequence().none()
}
