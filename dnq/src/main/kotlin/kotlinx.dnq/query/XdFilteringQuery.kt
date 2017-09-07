/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.query

import jetbrains.exodus.query.*
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.simple.maxValue
import kotlinx.dnq.simple.minValue
import kotlinx.dnq.simple.next
import kotlinx.dnq.simple.prev
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
    return withNode(PropertyRange(deepestNodeName, returnType.minValue(), returnType.prev(value)).decorateIfNeeded())
}

infix fun <T : Comparable<T>> T?.eq(value: T?): XdSearchingNode {
    val correctedValue = value?.let {
        if (it is DateTime) {
            it.millis
        } else {
            it
        }
    }
    return withNode(PropertyEqual(deepestNodeName, correctedValue).decorateIfNeeded())
}

infix fun <T : XdEntity> T?.eq(value: T?): XdSearchingNode {
    return withNode(LinkEqual(deepestNodeName, value?.entity).decorateIfNeeded())
}

infix fun <T : XdEntity> T?.ne(value: T?): XdSearchingNode {
    return withNode(UnaryNot(LinkEqual(deepestNodeName, value?.entity)).decorateIfNeeded())
}

infix fun <T : Comparable<T>> T?.gt(value: T): XdSearchingNode {
    val returnType = value.javaClass.kotlin
    return withNode(PropertyRange(deepestNodeName, returnType.next(value), returnType.maxValue()).decorateIfNeeded())
}

infix fun <T : Comparable<T>> T?.between(value: kotlin.Pair<T, T>): XdSearchingNode {
    val returnType = value.first.javaClass.kotlin
    return withNode(PropertyRange(deepestNodeName, returnType.prev(value.first), returnType.next(value.second)).decorateIfNeeded())
}

infix fun String?.startsWith(value: String?): XdSearchingNode {
    return withNode(PropertyStartsWith(deepestNodeName, value ?: "").decorateIfNeeded())
}

infix fun <T : Comparable<T>> T?.ne(value: T?): XdSearchingNode {
    return withNode(UnaryNot(PropertyEqual(deepestNodeName, value)).decorateIfNeeded())
}

infix fun <T : XdEntity> T?.isIn(entities: Iterable<T?>): XdSearchingNode {
    return withNode(entities.fold(None as NodeBase) { tree, e -> tree or (LinkEqual(deepestNodeName, e?.entity)) })
}

infix fun <T : Comparable<*>> T?.isIn(values: Iterable<T?>): XdSearchingNode {
    return withNode(values.fold(None as NodeBase) { tree, v -> tree or (PropertyEqual(deepestNodeName, v).decorateIfNeeded()) })
}

private val deepestChild: SearchingEntity get() = SearchingEntity.get().deepestChild()
private val deepestNodeName: String get() = deepestChild.currentNodeName!!

internal fun NodeBase.decorateIfNeeded(): NodeBase {
    val child = deepestChild
    if (child == SearchingEntity.get()) {
        return this
    }
    var result = this
    var temp = child.parentEntity
    while (temp != null) {
        result = LinksEqualDecorator(temp.currentNodeName, result, temp.childEntity!!.type)
        temp = temp.parentEntity
    }
    return result
}


private fun withNode(node: NodeBase): XdSearchingNode {
    return node.let {
        SearchingEntity.get().nodes.add(it)
        SearchingEntity.get().childEntity = null
        XdSearchingNode(it)
    }
}

fun <T : FakeTransientEntity> T.inScope(fn: T.() -> Unit): T {
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