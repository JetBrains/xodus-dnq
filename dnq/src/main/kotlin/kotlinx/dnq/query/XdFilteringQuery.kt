/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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

import jetbrains.exodus.query.*
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.simple.maxValue
import kotlinx.dnq.simple.minValue
import kotlinx.dnq.simple.next
import kotlinx.dnq.simple.prev
import kotlinx.dnq.util.getDBName
import org.joda.time.DateTime
import kotlin.reflect.KProperty1

@DnqFilterDsl
fun <T : XdEntity> XdQuery<T>.filter(clause: FilteringContext.(T) -> XdSearchingNode): XdQuery<T> {
    return filterUnsafe { clause(it) }
}

@DnqFilterDsl
@Suppress("UNCHECKED_CAST")
fun <S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(mapping: (S) -> T?): XdQuery<T> {
    val mappingEntity = MappingEntity(entityType.entityType, entityType.entityStore).inScope {
        mapping(entityType.wrap(this))
    }
    val link = mappingEntity.link!!
    val property = link.property as KProperty1<XdEntity, *>
    return mapDistinct(property.getDBName(entityType), link.delegate.oppositeEntityType as XdEntityType<T>)
}

@DnqFilterDsl
@Suppress("UNCHECKED_CAST")
fun <S : XdEntity, T : XdEntity, Q : XdQuery<T>> XdQuery<S>.flatMapDistinct(mapping: (S) -> Q): XdQuery<T> {
    val mappingEntity = MappingEntity(entityType.entityType, entityType.entityStore).inScope {
        mapping(entityType.wrap(this)).size() // to fallback to getLinks of entity method
    }
    val link = mappingEntity.link!!
    val property = link.property as KProperty1<XdEntity, *>
    return flatMapDistinct(property.getDBName(entityType), link.delegate.oppositeEntityType as XdEntityType<T>)
}

@DnqFilterDsl
fun <T : XdEntity> XdEntityType<T>.filter(clause: FilteringContext.(T) -> XdSearchingNode): XdQuery<T> {
    return all().filter(clause)
}

@DnqFilterDsl
@Deprecated("use filter instead")
fun <T : XdEntity> XdQuery<T>.filterUnsafe(clause: FilteringContext.(T) -> Unit): XdQuery<T> {
    val searchingEntity = SearchingEntity(entityType.entityType, entityType.entityStore).inScope {
        FilteringContext.clause(entityType.wrap(this))
    }
    return searchingEntity.nodes.fold(this) { cur, it ->
        cur.query(it)
    }
}

@DnqFilterDsl
object FilteringContext {

    @DnqFilterDsl
    infix fun <T : Comparable<T>> T?.lt(value: T): XdSearchingNode {
        val returnType = value.javaClass.kotlin
        return withNode(PropertyRange(deepestNodeName, returnType.minValue(), returnType.prev(value)).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun <T : Comparable<T>> T?.le(value: T): XdSearchingNode {
        val returnType = value.javaClass.kotlin
        return withNode(PropertyRange(deepestNodeName, returnType.minValue(), value.rawValue()).decorateIfNeeded())
    }

    @DnqFilterDsl
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

    @DnqFilterDsl
    infix fun <T : XdEntity> T?.eq(value: T?): XdSearchingNode {
        return withNode(LinkEqual(deepestNodeName, value?.entity).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun <T : XdEntity> T?.ne(value: T?): XdSearchingNode {
        return withNode(UnaryNot(LinkEqual(deepestNodeName, value?.entity).decorateIfNeeded()))
    }

    @DnqFilterDsl
    infix fun <T : Comparable<T>> T?.gt(value: T): XdSearchingNode {
        val returnType = value.javaClass.kotlin
        return withNode(PropertyRange(deepestNodeName, returnType.next(value), returnType.maxValue()).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun <T : Comparable<T>> T?.ge(value: T): XdSearchingNode {
        val returnType = value.javaClass.kotlin
        return withNode(PropertyRange(deepestNodeName, value.rawValue(), returnType.maxValue()).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun <T : Comparable<T>> T?.between(value: kotlin.Pair<T, T>): XdSearchingNode {
        val returnType = value.first.javaClass.kotlin
        return withNode(PropertyRange(deepestNodeName, returnType.prev(value.first), returnType.next(value.second)).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun String?.startsWith(value: String?): XdSearchingNode {
        return withNode(PropertyStartsWith(deepestNodeName, value ?: "").decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun String?.contains(value: String?): XdSearchingNode {
        return withNode(PropertyContains(deepestNodeName, value ?: "", true).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun <T : Comparable<T>> T?.ne(value: T?): XdSearchingNode {
        return withNode(UnaryNot(PropertyEqual(deepestNodeName, value).decorateIfNeeded()))
    }

    @DnqFilterDsl
    infix fun <T : XdEntity> T?.isIn(entities: Iterable<T?>): XdSearchingNode {
        return withNode(entities.fold(None as NodeBase) { tree, e -> tree or (LinkEqual(deepestNodeName, e?.entity)) })
    }

    @DnqFilterDsl
    infix fun <T : XdEntity> XdQuery<T>?.contains(entity: T): XdSearchingNode {
        size() // to call getLinks()
        return withNode(LinkEqual(deepestNodeName, entity.entity).decorateIfNeeded())
    }

    @DnqFilterDsl
    infix fun <T : Comparable<*>> T?.isIn(values: Iterable<T?>): XdSearchingNode {
        return withNode(values.fold(None as NodeBase) { tree, v -> tree or (PropertyEqual(deepestNodeName, v).decorateIfNeeded()) })
    }

    @DnqFilterDsl
    infix fun <T : XdEntity> XdQuery<T>?.containsIn(values: Iterable<T?>): XdSearchingNode {
        size() // to call getLinks()
        return withNode(values.fold(None as NodeBase) { tree, v -> tree or (LinkEqual(deepestNodeName, v?.entity).decorateIfNeeded()) })
    }

    @DnqFilterDsl
    fun <T : XdEntity> XdQuery<T>?.isEmpty(): XdSearchingNode {
        size() // to call getLinks()
        return withNode(LinkEqual(deepestNodeName, null).decorateIfNeeded())
    }

    @DnqFilterDsl
    fun <T : XdEntity> XdQuery<T>?.isNotEmpty(): XdSearchingNode {
        size() // to call getLinks()
        return withNode(LinkNotNull(deepestNodeName).decorateIfNeeded())
    }


    private fun <V : Comparable<V>> V.rawValue(): Comparable<*> {
        return if (this is DateTime) millis else this
    }


    private fun withNode(node: NodeBase): XdSearchingNode {
        return node.let {
            SearchingEntity.get().nodes.add(it)
            SearchingEntity.get().childEntity = null
            XdSearchingNode(it)
        }
    }

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
        result = LinksEqualDecorator(temp.currentNodeName!!, result, temp.childEntity!!.type)
        temp = temp.parentEntity
    }
    return result
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

    @DnqFilterDsl
    open infix fun and(another: XdSearchingNode): XdSearchingNode {
        return process(another) { And(target, another.target) }
    }

    @DnqFilterDsl
    open infix fun or(another: XdSearchingNode): XdSearchingNode {
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

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class DnqFilterDsl
