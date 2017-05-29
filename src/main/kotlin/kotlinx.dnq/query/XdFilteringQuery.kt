package kotlinx.dnq.query

import jetbrains.exodus.query.*
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import org.joda.time.DateTime
import kotlin.reflect.KProperty1

fun <T : XdEntity> XdQuery<T>.filter(clause: (T) -> Unit): XdQuery<T> {
    val searchingEntity = SearchingEntity(entityType.entityType, entityType.entityStore).inScope {
        clause(entityType.wrap(this))
    }
    return searchingEntity.nodes.fold(this) { cur, it ->
        cur.query(it)
    }
}

inline fun <reified S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(crossinline mapping: (S) -> T?): XdQuery<T> {
    val mappingEntity = MappingEntity(entityType.entityType, entityType.entityStore).inScope {
        mapping(entityType.wrap(this))
    }
    @Suppress("UNCHECKED_CAST")
    return mapDistinct(mappingEntity.modelProperty as KProperty1<S, T?>)
}

inline fun <S : XdEntity, reified T : XdEntity, Q : XdQuery<T>> XdQuery<S>.flatMapDistinct(crossinline mapping: (S) -> Q): XdQuery<T> {
    val mappingEntity = MappingEntity(entityType.entityType, entityType.entityStore).inScope {
        mapping(entityType.wrap(this)).size() // to fallback to getLinks of entity method
    }
    @Suppress("UNCHECKED_CAST")
    return flatMapDistinct(mappingEntity.modelProperty as KProperty1<S, Q>)
}

fun <T : XdEntity> XdEntityType<T>.filter(clause: (T) -> Unit): XdQuery<T> {
    return all().filter(clause)
}

infix fun <T : Comparable<T>> T?.lt(value: T): XdSearchingNode {
    val returnType = value.javaClass.kotlin
    return withNode(PropertyRange(SearchingEntity.get().currentProperty!!, returnType.minValue(), returnType.prev(value)))
}

infix fun <T : Comparable<T>> T?.eq(value: T?): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    val correctedValue = value?.let {
        if (it is DateTime) {
            it.millis
        } else {
            it
        }
    }
    return withNode(PropertyEqual(searchingEntity.currentProperty!!, correctedValue))
}

infix fun <T : XdEntity> T?.eq(value: T?): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(LinkEqual(searchingEntity.currentProperty!!, value?.entity))
}

infix fun <T : Comparable<T>> T?.gt(value: T): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.javaClass.kotlin
    return withNode(PropertyRange(searchingEntity.currentProperty!!, returnType.next(value), returnType.maxValue()))
}

infix fun <T : Comparable<T>> T?.between(value: kotlin.Pair<T, T>): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    val returnType = value.first.javaClass.kotlin
    return withNode(PropertyRange(searchingEntity.currentProperty!!, returnType.prev(value.first), returnType.next(value.second)))
}

infix fun String?.startsWith(value: String?): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(PropertyStartsWith(searchingEntity.currentProperty!!, value ?: ""))
}

infix fun <T : Comparable<T>> T?.ne(value: T?): XdSearchingNode {
    return withNode(UnaryNot(PropertyEqual(SearchingEntity.get().currentProperty!!, value)))
}

infix fun <T : XdEntity> T?.isIn(entities: Iterable<T?>): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(entities.fold(None as NodeBase) { tree, e -> tree or (LinkEqual(searchingEntity.currentProperty!!, e?.entity)) })
}

infix fun <T : Comparable<*>> T?.isIn(values: Iterable<T?>): XdSearchingNode {
    val searchingEntity = SearchingEntity.get()
    return withNode(values.fold(None as NodeBase) { tree, v -> tree or (PropertyEqual(searchingEntity.currentProperty!!, v)) })
}

private fun withNode(node: NodeBase): XdSearchingNode {
    return node.let {
        SearchingEntity.get().nodes.add(it)
        XdSearchingNode(it)
    }
}

fun <T: FakeTransientEntity> T.inScope(fn: T.() -> Unit): T {
    FakeTransientEntity.current.set(this)
    try {
        fn()
    } finally {
        FakeTransientEntity.current.set(null)
    }
    return this
}

open class XdSearchingNode(val target: NodeBase) {

    infix open fun and(another: XdSearchingNode): XdSearchingNode {
        return process(another) { And(target, another.target) }
    }

    infix open fun or(another: XdSearchingNode): XdSearchingNode {
        return process(another) { Or(target, another.target) }
    }

    private fun process(another: XdSearchingNode,
                        factory: () -> CommutativeOperator): XdSearchingNode {
        return XdSearchingNode(factory()).also {
            SearchingEntity.get().nodes.apply {
                removeAll(listOf(target, another.target))
                add(it.target)
            }
        }
    }
}