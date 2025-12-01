/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.query

import com.jetbrains.teamsys.dnq.database.PersistentEntityIterableWrapper
import com.jetbrains.teamsys.dnq.database.TransientEntityIterable
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.asYTDBTransaction
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.query.LeafNode
import jetbrains.exodus.query.NodeBase
import jetbrains.exodus.query.NodeFactory
import jetbrains.exodus.query.QueryEngine
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.XdModel
import kotlinx.dnq.session
import kotlinx.dnq.util.entityType
import kotlinx.dnq.util.getDBName
import kotlinx.dnq.util.threadSessionOrThrow
import java.util.*
import kotlin.Boolean
import kotlin.Comparable
import kotlin.Comparator
import kotlin.Deprecated
import kotlin.Int
import kotlin.NoSuchElementException
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.apply
import kotlin.collections.ArrayList
import kotlin.collections.Collection
import kotlin.collections.HashSet
import kotlin.collections.Iterable
import kotlin.collections.Iterator
import kotlin.collections.List
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.asSequence
import kotlin.collections.filterNotNull
import kotlin.collections.first
import kotlin.collections.hashSetOf
import kotlin.collections.indexOf
import kotlin.collections.lastIndex
import kotlin.collections.lastOrNull
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.mapTo
import kotlin.collections.none
import kotlin.collections.reversed
import kotlin.collections.toList
import kotlin.collections.toSet
import kotlin.collections.toTypedArray
import kotlin.let
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaType
import kotlin.sequences.Sequence
import kotlin.sequences.asIterable
import kotlin.sequences.count
import kotlin.sequences.distinct
import kotlin.sequences.drop
import kotlin.sequences.firstOrNull
import kotlin.sequences.forEach
import kotlin.sequences.lastOrNull
import kotlin.sequences.map
import kotlin.sequences.none
import kotlin.sequences.single
import kotlin.sequences.singleOrNull
import kotlin.sequences.take
import kotlin.sequences.toCollection
import kotlin.sequences.toHashSet
import kotlin.sequences.toList
import kotlin.sequences.toMutableList
import kotlin.sequences.toMutableSet
import kotlin.sequences.toSet
import kotlin.sequences.toSortedSet

/**
 * Representation of effective database collections that use Xodus indices.
 * Such objects are returned by `XdEntityType#all()`, multi-value persistent links, and various database
 * collection operations: filtering, sorting, mapping, etc.
 */
interface XdQuery<out T : XdEntity> {
    val entityType: XdEntityType<T>
    val entityIterable: Iterable<Entity>
}

class XdQueryImpl<out T : XdEntity>(
    override val entityIterable: Iterable<Entity>,
    override val entityType: XdEntityType<T>
) : XdQuery<T>

private val <T : XdEntity> XdQuery<T>.queryEngine: QueryEngine
    get() = entityType.entityStore.queryEngine

fun <T : XdEntity> Iterable<Entity>?.asQuery(entityType: XdEntityType<T>): XdQuery<T> {
    return if (this != null) {
        XdQueryImpl(this, entityType)
    } else {
        XdQueryImpl(YTDBEntityIterable.EMPTY, entityType)
    }
}

/**
 * Performs an operation on `iterable`, disposing all used resources if it is an [EntityIterable].
 */
fun <T, R> useIterable(iterable: Iterable<T>, f: (Sequence<T>) -> R): R {
    val iterator = iterable.iterator()

    try {
        return f(iterator.asSequence())
    } finally {
        if (iterator is EntityIterator && iterator.shouldBeDisposed()) {
            iterator.dispose()
        }
    }
}

/**
 * Performs an operation on the results of this query, disposing all used resources after.
 */
fun <T : XdEntity, R> XdQuery<T>.useEntitySequence(f: (Sequence<Entity>) -> R): R =
    useIterable(entityIterable, f)

/**
 * Performs an operation on [XdEntity]s from the results of this query, disposing all used resources after.
 */
fun <T : XdEntity, R> XdQuery<T>.useSequence(f: (Sequence<T>) -> R): R =
    useEntitySequence { s -> f(s.map { entityType.wrap(it) }) }

/**
 * Performs an operation of [EntityId]s of the results of this query, disposing all used resources after.
 */
fun <T : XdEntity, R> XdQuery<T>.useIdsSequence(f: (Sequence<EntityId>) -> R): R =
    useEntitySequence { s -> f(s.map { it.id }) }

/**
 * Creates a [Sequence] instance that wraps the original query returning its results when being iterated.
 *
 * WARNING: This method is unsafe, because doesn't close the underlying [EntityIterator], use [useSequence] instead.
 */
fun <T : XdEntity> XdQuery<T>.asSequence(): Sequence<T> {
    return entityIterable
        .asSequence()
        .map { entityType.wrap(it) }
}

/**
 * Creates a [Sequence] instance that wraps the original query returning its results as entity ids when being iterated.
 *
 * WARNING: This method is unsafe because it doesn't close the underlying [EntityIterator], use [useIdsSequence] instead.
 */
fun <T : XdEntity> XdQuery<T>.asIdSequence(): Sequence<EntityId> =
    entityIterable.asSequence().map { it.id }

/**
 * Creates an [Iterable] instance that wraps the original query returning its results when being iterated.
 *
 * WARNING: This method is unsafe because it doesn't close the underlying [EntityIterator], use [useSequence] instead.
 */
fun <T : XdEntity> XdQuery<T>.asIterable(): Iterable<T> = asSequence().asIterable()

/**
 * Creates an [Iterator] instance that iterates over query results.
 *
 * WARNING: This method is unsafe because the resulting [EntityIterator] is not closed upon completion of the query,
 * use [useSequence] instead.
 */
operator fun <T : XdEntity> XdQuery<T>.iterator(): Iterator<T> = asSequence().iterator()

/**
 * Appends all elements to the given [destination] collection.
 */
fun <T : XdEntity, C : MutableCollection<in T>> XdQuery<T>.toCollection(destination: C) =
    useSequence { it.toCollection(destination) }

/**
 * Returns a [List] containing all results of the original query.
 */
fun <T : XdEntity> XdQuery<T>.toList(): List<T> = useSequence { it.toList() }

/**
 * Returns a [List] containing ids of all results of the original query.
 */
fun <T : XdEntity> XdQuery<T>.toIdList(): List<EntityId> = useIdsSequence { it.toList() }

/**
 * Returns a [MutableList] filled with all results of the original query.
 */
fun <T : XdEntity> XdQuery<T>.toMutableList(): MutableList<T> = useSequence { it.toMutableList() }

/**
 * Returns a [Set] of all results of the original query.
 *
 * The returned set preserves the element iteration order of the original query.
 */
fun <T : XdEntity> XdQuery<T>.toSet(): Set<T> = useSequence { it.toSet() }

/**
 * Returns a [HashSet] of all results of the original query.
 */
fun <T : XdEntity> XdQuery<T>.toHashSet(): HashSet<T> = useSequence { it.toHashSet() }

/**
 * Returns a [SortedSet] of all results of the original query.
 *
 * Elements in the set returned are sorted according to the given [comparator].
 */
fun <T : XdEntity> XdQuery<T>.toSortedSet(comparator: Comparator<T>): SortedSet<T> =
    useSequence { it.toSortedSet(comparator) }

/**
 * Returns a mutable set containing all distinct results of the original query.
 *
 * The returned set preserves the element iteration order of the original query.
 */
fun <T : XdEntity> XdQuery<T>.toMutableSet(): MutableSet<T> = useSequence { it.toMutableSet() }

/**
 * Returns an empty query.
 */
fun <T : XdEntity> XdEntityType<T>.emptyQuery(): XdQuery<T> {
    return XdQueryImpl(YTDBEntityIterable.EMPTY, this)
}

fun <T : XdEntity> XdEntityType<T>.singleton(element: T?): XdQuery<T> {
    return queryEntities(singletonOf(element?.entity))
}

private fun <T : XdEntity> XdEntityType<T>.singletonOf(element: Entity?): Iterable<Entity> {
    if (element == null) {
        return YTDBEntityIterable.EMPTY
    }
    if ((element as TransientEntity).isNew) {
        return sequenceOf(element).asIterable()
    }
    return entityStore.session.getSingletonIterable(element)
}

/**
 * Builds a query of given elements.
 *
 * Null elements are ignored, e.g. `queryOf(null)` returns an empty query.
 */
fun <T : XdEntity> XdEntityType<T>.queryOf(vararg elements: T?): XdQuery<T> {
    val notNullElements = elements.filterNotNull()
    val iterable = if (notNullElements.isEmpty()){
        YTDBEntityIterable.EMPTY
    } else {
        val txn = notNullElements.first().threadSessionOrThrow
        PersistentEntityIterableWrapper(
            txn.store,
            YTDBEntityIterable.query(
                txn.transactionInternal as YTDBStoreTransaction,
                GremlinQuery.ByIds(
                    notNullElements.map { txn.newEntity(it.entity).entity.id.asOId() }
                )
            )
        )
    }

    return XdQueryImpl(iterable, this)
}

fun <T : XdEntity> XdEntityType<T>.wrapAsQuery(vararg elements: T?): XdQuery<T> = XdQueryImpl(
    elements
        .asSequence()
        .filterNotNull()
        .map { it.entity }
        .toList()
        .ifEmpty { YTDBEntityIterable.EMPTY },
    this
)

/**
 * Builds a query of given elements.
 *
 * Null elements are ignored, e.g. `queryOf(null)` returns an empty query.
 */
@Deprecated("Instead use <T : XdEntity> XdEntityType<T>.query(elements: Iterable<T?>)")
inline fun <reified T : XdEntity> XdEntityType<T>.queryOf(elements: Iterable<T?>): XdQuery<T> {
    return queryOf(*elements.toList().toTypedArray())
}

/**
 * Returns a new query containing all results that are contained by both `this` and [that] queries.
 */
infix fun <T : XdEntity> XdQuery<T>.intersect(that: XdQuery<T>): XdQuery<T> {
    val result = queryEngine.intersect(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

/**
 * Returns a new query containing all distinct results from both `this` and [that] queries.
 */
infix fun <T : XdEntity> XdQuery<T>.union(that: XdQuery<T>): XdQuery<T> {
    val result = queryEngine.union(this.entityIterable, that.entityIterable)
    val commonAncestor = XdModel.getCommonAncestor(this.entityType, that.entityType)
    return XdQueryImpl(result, commonAncestor ?: this.entityType)
}

/**
 * Returns a new query containing [that] and all distinct results from `this` query.
 */
infix fun <T : XdEntity> XdQuery<T>.union(that: T?): XdQuery<T> {
    return this union entityType.queryOf(that)
}

/**
 * Creates balanced union tree of queries returned by `queryFun` for each element of `this`.
 */
fun <T : XdEntity, S : XdEntity> Iterable<S>.unionEach(
    entityType: XdEntityType<T>,
    queryFun: (S) -> XdQuery<T>
): XdQuery<T> {
    val queryList = ArrayList<XdQuery<T>>().apply { this@unionEach.mapTo(this, queryFun) }
    var i = 0
    while (i < queryList.lastIndex) {
        queryList.add(queryList[i].union(queryList[i + 1]))
        i += 2
    }
    return queryList.lastOrNull() ?: entityType.emptyQuery()
}

/**
 * Creates balanced union tree of queries returned by `queryFun` for each element of `this`.
 */
fun <T : XdEntity, S : XdEntity> XdQuery<S>.unionEach(
    entityType: XdEntityType<T>,
    queryFun: (S) -> XdQuery<T>
): XdQuery<T> {
    return useSequence { it.asIterable().unionEach(entityType, queryFun) }
}

/**
 * Returns a new query containing all results of `this` query and then all results of the given [that] query.
 */
operator fun <T : XdEntity> XdQuery<T>.plus(that: XdQuery<T>): XdQuery<T> {
    val result = queryEngine.concat(this.entityIterable, that.entityIterable)
    val commonAncestor = XdModel.getCommonAncestor(this.entityType, that.entityType)
    return XdQueryImpl(result, commonAncestor ?: this.entityType)
}

/**
 * Returns a new query containing all results of `this` query and then given [that] entity.
 */
operator fun <T : XdEntity> XdQuery<T>.plus(that: T?): XdQuery<T> {
    return this + entityType.queryOf(that)
}

/**
 * Returns a new query containing all results of `this` query except the results contained in the given [that] query.
 */
infix fun <T : XdEntity> XdQuery<T>.exclude(that: XdQuery<T>): XdQuery<T> {
    val it = queryEngine.exclude(this.entityIterable, that.entityIterable)
    return XdQueryImpl(it, this.entityType)
}

/**
 * Returns a new query containing all results of `this` query except the given [that] entity.
 */
infix fun <T : XdEntity> XdQuery<T>.exclude(that: T?): XdQuery<T> {
    return this exclude entityType.queryOf(that)
}

/**
 * Returns new query that contains results of `this` query that match the given [node] predicate.
 *
 * This method uses built-in Xodus indices. It's much more efficient and performant
 * than in-memory collection manipulations.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 *        There is a set of predefined methods to build such trees.
 * @see eq
 * @see ne
 * @see gt
 * @see lt
 * @see ge
 * @see le
 * @see startsWith
 * @see contains
 * @see and
 * @see or
 * @see not
 */
fun <T : XdEntity> XdQuery<T>.query(node: NodeBase): XdQuery<T> {
    return queryEngine.query(entityIterable, entityType.entityType, node).asQuery(entityType)
}

/**
 * Returns query of all entities of `this` entity type that match the given [node] predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdEntityType<T>.query(node: NodeBase): XdQuery<T> {
    return all().query(node)
}

/**
 * Represents an iterable as a query.
 * Should be used when queries are mixed with custom iterables to make query tree optimization possible.
 *
 * @param it arbitrary iterable of entities.
 * @see query
 */
fun <T : XdEntity> XdEntityType<T>.queryEntities(it: Iterable<Entity>): XdQuery<T> {
    if (it is Collection && it.isEmpty()) {
        return emptyQuery()
    }
    //this was optimized for xodus, now not
    return XdQueryImpl(it, this)
}

/**
 * Represents an iterable as a query.
 * Should be used when queries are mixed with custom iterables to make query tree optimization possible.
 *
 * @param it arbitrary iterable of entities.
 * @see query
 */
inline fun <reified T : XdEntity> XdEntityType<T>.query(it: Iterable<T?>): XdQuery<T> {
    if (it is Collection && it.isEmpty()) {
        return emptyQuery()
    }
    return XdQueryImpl(it.mapNotNull { it?.entity }, this)
}

/**
 * Returns a new query containing all results of `this` query that are instances of the given [entityType].
 */
fun <T : XdEntity, S : T> XdQuery<T>.filterIsInstance(entityType: XdEntityType<S>): XdQuery<S> {
    val queryEngine = this.queryEngine
    return queryEngine.query(
        this.entityIterable,
        this.entityType.entityType,
        LeafNode(GremlinBlock.HasLabel(entityType.entityType))
    ).asQuery(entityType)
}

/**
 * Returns a new query containing all results of `this` query that are not instances of the given [entityType].
 */
fun <T : XdEntity, S : T> XdQuery<T>.filterIsNotInstance(entityType: XdEntityType<S>): XdQuery<T> {
    val queryEngine = this.queryEngine
    return queryEngine.query(
        this.entityIterable,
        this.entityType.entityType,
        LeafNode(GremlinBlock.Not(GremlinBlock.HasLabel(entityType.entityType)))
    ).asQuery(entityType)
}

/**
 * Returns a new query of all results of `this` query sorted by value of the given [property].
 *
 * The sorting is stable, i.e. it allows to sort by one property then by another. For example,
 * sort all users by gender and users of the same gender sort by login
 * ```
 * XdUser.all().sortedBy(XdUser::login).sortedBy(XdUser::gender, asc = true)
 * ```
 *
 * @param asc if `true` (by default) sort in ascending order, if `false` sort in descending order.
 */
fun <T : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(
    property: KProperty1<T, V>,
    asc: Boolean = true
): XdQuery<T> {
    return queryEngine.query(
        entityIterable,
        entityType.entityType,
        NodeFactory.sortBy(
            property.getDBName(entityType),
            if (asc) GremlinBlock.SortDirection.ASC else GremlinBlock.SortDirection.DESC
        )
    ).asQuery(entityType)
}

/**
 * Returns a new query of all results of `this` query sorted by value of the given [property] of the given [linkProperty].
 *
 * For example, sort all users by the titles of their jobs:
 * ```
 * XdUser.all().sortedBy(XdUser::job, XdJob::title)
 * ```
 *
 * @param asc if `true` (by default) sort in ascending order, if `false` sort in descending order.
 */
inline fun <reified T : XdEntity, reified S : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(
    linkProperty: KProperty1<T, S?>,
    property: KProperty1<S, V?>,
    asc: Boolean = true
): XdQuery<T> {
    return sortedBy(T::class, linkProperty, S::class, property, asc)
}

/**
 * Returns a new query of all results of `this` query sorted by value of the given [property] of the given [linkProperty].
 *
 * For example, sort all users by the titles of their jobs:
 * ```
 * XdUser.all().sortedBy(XdUser::job, XdJob::title)
 * ```
 *
 * @param asc if `true` (by default) sort in ascending order, if `false` sort in descending order.
 */
fun <T : XdEntity, S : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(
    klass: KClass<T>,
    linkProperty: KProperty1<T, S?>,
    linkKlass: KClass<S>,
    property: KProperty1<S, V?>,
    asc: Boolean = true
): XdQuery<T> {
    return queryEngine.query(
        entityIterable,
        entityType.entityType,
        NodeFactory.sortByLinked(
//            null,
//            linkKlass.java.entityType.entityType,
            linkProperty.getDBName(klass),
            property.getDBName(linkKlass),
            if (asc) GremlinBlock.SortDirection.ASC else GremlinBlock.SortDirection.DESC
        )
    ).asQuery(entityType)
}

/**
 * Returns number of results of `this` query. If `this` query is `null` returns `0`.
 */
fun <T : XdEntity> XdQuery<T>?.size(): Int {
    val it = this?.entityIterable

    return when (it) {
        null -> 0
        YTDBEntityIterable.EMPTY -> 0
        is EntityIterable -> it.size().toInt()
        is Collection<*> -> it.size
        else -> useEntitySequence { it.count() }
    }
}

/**
 * Returns approximate number of results of `this` query. If `this` query is `null` returns `0`.
 */
fun <T : XdEntity> XdQuery<T>?.roughSize(): Int {
    return if (this == null) {
        0
    } else {
        val it = queryEngine.toEntityIterable(entityIterable)
        when (it) {
            is EntityIterable -> it.roughSize.toInt()
            is Collection<*> -> it.size
            else -> useEntitySequence { it.count() }
        }
    }
}

/**
 * Returns number of results of `this` query that match the given [node] predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>?.size(node: NodeBase): Int {
    return this?.query(node).size()
}

/**
 * Returns true if `this` query has no results.
 */
val <T : XdEntity> XdQuery<T>?.isEmpty: Boolean
    get() {
        return if (this == null) {
            true
        } else {
            val it = entityIterable
            if (it is Collection<*>) {
                it.isEmpty()
            } else if (it is TransientEntityIterable) {
                it.isEmpty()
            } else {
                val entIt = queryEngine.toEntityIterable(it)
                if (queryEngine.isPersistentIterable(entIt)) {
                    (entIt as EntityIterable).isEmpty
                } else {
                    useIterable(entIt) { it.none() }
                }
            }
        }
    }

/**
 * Returns true if `this` query has some results.
 */
val <T : XdEntity> XdQuery<T>?.isNotEmpty: Boolean
    get() = !isEmpty

/**
 * Returns a new query containing all results of `this` query except first [n] elements.
import jetbrains.exodus.query.PropertyEqual
 */
fun <T : XdEntity> XdQuery<T>.drop(n: Int): XdQuery<T> {
    return operation({ it.skip(n) }, { it.drop(n) })
}

/**
 * Returns a new query containing first [n] results of `this` query.
 */
fun <T : XdEntity> XdQuery<T>.take(n: Int): XdQuery<T> {
    return operation({ it.take(n) }, { it.take(n) })
}

private inline fun <T : XdEntity> XdQuery<T>.operation(
    ifEntityIterable: (EntityIterable) -> EntityIterable,
    notEntityIterable: (Sequence<Entity>) -> Sequence<Entity>
): XdQuery<T> {
    val it = queryEngine.toEntityIterable(entityIterable)
    return when (it) {
        is YTDBEntityIterable -> wrap(ifEntityIterable(it.unwrap()))
        is EntityIterable -> wrap(ifEntityIterable(it))
        else -> notEntityIterable(it.asSequence()).asIterable()
    }.asQuery(entityType)
}

private fun <T : XdEntity> XdQuery<T>.wrap(entityIterable: EntityIterable): EntityIterable {
    return entityType.entityStore.session.createPersistentEntityIterableWrapper(entityIterable)
}

/**
 * Returns a new query containing only distinct results from the given `this` query.
 */
fun <T : XdEntity> XdQuery<T>.distinct(): XdQuery<T> {
    return operation({ it.distinct() }, { it.distinct() })
}

/**
 * Returns a new query containing distinct values of the property [dbFieldName] of each result of `this` query.
 */
fun <S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(
    dbFieldName: String,
    targetEntityType: XdEntityType<T>
): XdQuery<T> {
    return queryEngine.selectDistinct(entityIterable, dbFieldName).asQuery(targetEntityType)
}

private fun Iterable<Entity?>.filterNotNull(entityType: XdEntityType<*>): Iterable<Entity> {
    val entityTypeName = entityType.entityType
    val queryEngine = entityType.entityStore.queryEngine

    if (this is YTDBEntityIterable) {
        return this.intersect(
            YTDBEntityIterable.where(
                entityTypeName,
                this.transaction.asYTDBTransaction(),
                GremlinBlock.All
            )
        )
    } else {
        val modelMetaData = queryEngine.modelMetaData
        val subTypes = modelMetaData?.getEntityMetaData(entityTypeName)?.allSubTypes?.toSet() ?: hashSetOf()
        return this.asSequence().filter {
            it != null && subTypes.contains(it.type)
        }.filterNotNull().asIterable()
    }
}

/**
 * Returns a new query containing distinct values of the [field] of each result of `this` query.
 */
fun <S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(field: KProperty1<S, T?>): XdQuery<T> {
    @Suppress("UNCHECKED_CAST")
    return mapDistinct(field.getDBName(entityType), (field.returnType.javaType as Class<T>).entityType)
}

/**
 * Returns a new query of all elements yielded from values of property [dbFieldName] of each result of `this` query.
 */
fun <S : XdEntity, T : XdEntity> XdQuery<S>.flatMapDistinct(
    dbFieldName: String,
    targetEntityType: XdEntityType<T>
): XdQuery<T> {
    return queryEngine.selectManyDistinct(entityIterable, dbFieldName).asQuery(targetEntityType)
}

/**
 * Returns a new query of all elements yielded from values of [field] of each result of `this` query.
 */
inline fun <S : XdEntity, reified T : XdEntity, Q : XdQuery<T>> XdQuery<S>.flatMapDistinct(field: KProperty1<S, Q>): XdQuery<T> {
    @Suppress("UNCHECKED_CAST")
    return flatMapDistinct(field.getDBName(entityType), T::class.entityType)
}

/**
 * Returns first index of [entity], or negative value if the query does not contain the entity.
 */
fun <T : XdEntity> XdQuery<T>.indexOf(entity: Entity?): Int {
    val it = queryEngine.toEntityIterable(entityIterable)
    return if (entity != null) {
        if (queryEngine.isPersistentIterable(it)) {
            (it as EntityIterable).unwrap().indexOf(entity)
        } else {
            it.indexOf(entity)
        }
    } else {
        -1
    }
}

/**
 * Returns first index of [entity], or negative value if the query does not contain the entity.
 */
fun <T : XdEntity> XdQuery<T>.indexOf(entity: T?): Int {
    return indexOf(entity?.entity)
}

/**
 * Returns `true` if query contains [entity].
 */
operator fun <T : XdEntity> XdQuery<T>.contains(entity: Entity?): Boolean {
    val iterable = entityIterable
    val i = if (iterable is EntityIterable) {
        iterable.unwrap()
    } else {
        iterable
    }
    return when {
        i is Collection<*> -> {
            i.contains(entity)
        }

        i is YTDBEntityIterable && entity != null -> {
            i.contains(entity)
        }

        else -> {
            (i as EntityIterable).unwrap().indexOf(entity) != -1
        }
    }
}

/**
 * Returns `true` if query contains [entity].
 */
operator fun <T : XdEntity> XdQuery<T>.contains(entity: T?): Boolean {
    return contains(entity?.entity)
}

/**
 * Returns first result of the query.
 * @throws [NoSuchElementException] if the query is empty.
 */
fun <T : XdEntity> XdQuery<T>.first(): T {
    return firstOrNull() ?: throw NoSuchElementException("Query is empty.")
}

/**
 * Returns first result of the query that match the given [node] predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @throws [NoSuchElementException] if the query is empty.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.first(node: NodeBase): T {
    return query(node).first()
}

/**
 * Returns the first result of the query, or `null` if the query is empty.
 */
fun <T : XdEntity> XdQuery<T>.firstOrNull(): T? {
    if (entityIterable is TransientEntityIterable){
        return useIterable(entityIterable) { eit -> eit.firstOrNull()?.let { entityType.wrap(it) } }
    }
    val it = queryEngine.toEntityIterable(entityIterable)
    val entity = if (it is YTDBEntityIterable) {
        it.unwrap().first?.let { entityType.entityStore.session.newEntity(it) }
    } else {
        useIterable(it) { eit -> eit.firstOrNull() }
    }
    return entity?.let { entityType.wrap(it) }
}

/**
 * Returns the first result of the query that match the given [node] predicate, or `null` if no result match the predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.firstOrNull(node: NodeBase): T? {
    return query(node).firstOrNull()
}

/**
 * Returns the last result of the query.
 * @throws [NoSuchElementException] if the query is empty.
 */
fun <T : XdEntity> XdQuery<T>.last(): T {
    return lastOrNull() ?: throw NoSuchElementException("Query is empty.")
}

/**
 * Returns the last result of the query that match the given [node] predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @throws [NoSuchElementException] if the query is empty.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.last(node: NodeBase): T {
    return query(node).last()
}

/**
 * Returns the last result of the query, or `null` if the query is empty.
 */
fun <T : XdEntity> XdQuery<T>.lastOrNull(): T? {
    val it = queryEngine.toEntityIterable(entityIterable)
    val entity = if (it is YTDBEntityIterable) {
        it.unwrap().last?.let { entityType.entityStore.session.newEntity(it) }
    } else {
        useIterable(it) { eit -> eit.lastOrNull() }
    }
    return entity?.let { entityType.wrap(it) }
}

/**
 * Returns the last result of the query that match the given [node] predicate, or `null` if no result match the predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.lastOrNull(node: NodeBase): T? {
    return query(node).lastOrNull()
}

/**
 * Returns the single query result, or throws an exception if the query is empty or has more than one result.
 */
fun <T : XdEntity> XdQuery<T>.single(): T {
    return useSequence { it.single() }
}

/**
 * Returns the single query result matching the given [node] predicate, or throws an exception
 * if there are no such results or more than one.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.single(node: NodeBase): T {
    return query(node).single()
}

/**
 * Returns the single query result, or `null` if the query is empty or has more than one result.
 */
fun <T : XdEntity> XdQuery<T>.singleOrNull(): T? {
    return useSequence { it.singleOrNull() }
}

/**
 * Returns the single query result matching the given [node] predicate, or `null`
 * if there are no such results or more than one.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.singleOrNull(node: NodeBase): T? {
    return query(node).singleOrNull()
}

/**
 * Returns `true` if query has at least one result.
 */
fun <T : XdEntity> XdQuery<T>.any() = isNotEmpty

/**
 * Returns `true` if query has at least one result matching the given [node] predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.any(node: NodeBase): Boolean {
    return query(node).any()
}

/**
 * Returns `true` if query has no results.
 */
fun <T : XdEntity> XdQuery<T>.none() = isEmpty

/**
 * Reverses elements in query
 */
fun <T : XdEntity> XdQuery<T>.reversed(): XdQuery<T> {
    val engine = queryEngine
    val iterable = engine.toEntityIterable(entityIterable)
    return if (iterable is YTDBEntityIterable) {
        XdQueryImpl(wrap(iterable.unwrap().reverse()), entityType)
    } else {

        XdQueryImpl(useEntitySequence { it.asIterable().reversed() }, entityType)
    }
}

/**
 * Returns `true` if query has no results matching the given [node] predicate.
 *
 * @param node object that defines an abstract syntax tree of filtering predicate expression.
 * @see query
 */
fun <T : XdEntity> XdQuery<T>.none(node: NodeBase): Boolean {
    return query(node).entityIterable.none()
}

/**
 * Adds all elements of given [Sequence] to this mutable query.
 */
fun <T : XdEntity> XdMutableQuery<T>.addAll(elements: Sequence<T>) {
    elements.forEach { add(it) }
}

/**
 * Adds all results of given [XdQuery] to this mutable query.
 */
fun <T : XdEntity> XdMutableQuery<T>.addAll(elements: XdQuery<T>) {
    elements.useSequence { addAll(it) }
}

/**
 * Adds all results of given [Iterable] to this mutable query.
 */
fun <T : XdEntity> XdMutableQuery<T>.addAll(elements: Iterable<T>) {
    useIterable(elements) { addAll(it) }
}

/**
 * Removes all elements of given [Sequence] from this mutable query.
 */
fun <T : XdEntity> XdMutableQuery<T>.removeAll(elements: Sequence<T>) {
    elements.forEach { remove(it) }
}

/**
 * Removes all results of given [XdQuery] from this mutable query.
 */
fun <T : XdEntity> XdMutableQuery<T>.removeAll(elements: XdQuery<T>) {
    elements.useSequence { removeAll(it) }
}

/**
 * Removes all elements of given [Iterable] from this mutable query.
 */
fun <T : XdEntity> XdMutableQuery<T>.removeAll(elements: Iterable<T>) {
    removeAll(elements.asSequence())
}

fun <T : XdEntity> XdQuery<T>.forEach(action: (T) -> Unit) {
    useSequence { it.forEach(action) }
}
