/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.query.*
import jetbrains.exodus.query.metadata.ModelMetaData
import kotlinx.dnq.XdEntity
import kotlinx.dnq.simple.maxValue
import kotlinx.dnq.simple.minValue
import kotlinx.dnq.simple.next
import kotlinx.dnq.simple.prev
import kotlinx.dnq.util.entityType
import kotlinx.dnq.util.getDBName
import org.joda.time.DateTime
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaType

/**
 * Returns new [NodeBase] representing negation of the given [node].
 */
fun not(node: NodeBase): NodeBase = UnaryNot(node)

/**
 * Returns new [NodeBase] representing conjunction of `this` and [that] nodes.
 */
infix fun NodeBase.and(that: NodeBase): NodeBase = And(this, that)

/**
 * Returns new [NodeBase] representing disjunction of `this` and [that] nodes.
 */
infix fun NodeBase.or(that: NodeBase): NodeBase = Or(this, that)

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] equal to the given [value].
 */
fun eq(dbPropertyName: String, value: Comparable<*>?): NodeBase {
    return PropertyEqual(dbPropertyName, value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property equal to the given [value].
 */
infix inline fun <reified R : XdEntity, T : Comparable<*>> KProperty1<R, T?>.eq(value: T?): NodeBase {
    return eq(this.getDBName(R::class), value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property equal to the given [value].
 */
infix inline fun <reified R : XdEntity> KProperty1<R, DateTime?>.eq(value: DateTime?): NodeBase {
    return eq(this.getDBName(R::class), value?.millis)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] is not equal to the given [value].
 */
fun ne(dbPropertyName: String, value: Comparable<*>?): NodeBase {
    return not(PropertyEqual(dbPropertyName, value))
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is not equal to the given [value].
 */
infix inline fun <reified R : XdEntity, T : Comparable<*>> KProperty1<R, T?>.ne(value: T?): NodeBase {
    return ne(this.getDBName(R::class), value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is not equal to the given [value].
 */
infix inline fun <reified R : XdEntity> KProperty1<R, DateTime?>.ne(value: DateTime?): NodeBase {
    return ne(this.getDBName(R::class), value?.millis)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] falls into `this` range.
 */
fun <T : Comparable<T>> ClosedRange<T>.contains(dbPropertyName: String): NodeBase {
    return PropertyRange(dbPropertyName, start, endInclusive)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the given [property] falls into `this` range.
 */
infix inline fun <reified R : XdEntity, T : Comparable<T>> ClosedRange<T>.contains(property: KProperty1<R, T?>): NodeBase {
    return contains(property.getDBName(R::class))
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the given [property] falls into `this` range.
 */
@JvmName("containsDate")
infix inline fun <reified R : XdEntity> ClosedRange<DateTime>.contains(property: KProperty1<R, DateTime?>): NodeBase {
    return (start.millis..endInclusive.millis).contains(property.getDBName(R::class))
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] is greater than the given [value].
 */
fun <V : Comparable<V>> gt(dbPropertyName: String, value: V, valueKClass: KClass<V>): NodeBase {
    return PropertyRange(dbPropertyName, valueKClass.next(value), valueKClass.maxValue())
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is greater than the given [value].
 */
inline infix fun <reified R : XdEntity, reified V : Comparable<V>> KProperty1<R, V?>.gt(value: V): NodeBase {
    return gt(getDBName(R::class), value, V::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is greater than the given [value].
 */
inline infix fun <reified R : XdEntity> KProperty1<R, DateTime?>.gt(value: DateTime): NodeBase {
    return gt(getDBName(R::class), value.millis, Long::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] is less than the given [value].
 */
fun <V : Comparable<V>> lt(dbPropertyName: String, value: V, valueKClass: KClass<V>): NodeBase {
    return PropertyRange(dbPropertyName, valueKClass.minValue(), valueKClass.prev(value))
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is less than the given [value].
 */
inline infix fun <reified R : XdEntity, reified V : Comparable<V>> KProperty1<R, V?>.lt(value: V): NodeBase {
    return lt(getDBName(R::class), value, V::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is less than the given [value].
 */
inline infix fun <reified R : XdEntity> KProperty1<R, DateTime?>.lt(value: DateTime): NodeBase {
    return lt(getDBName(R::class), value.millis, Long::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] is greater or equal to the given [value].
 */
fun <V : Comparable<V>> ge(dbPropertyName: String, value: V, valueKClass: KClass<V>): PropertyRange {
    return PropertyRange(dbPropertyName, value, valueKClass.maxValue())
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is greater or equal to the given [value].
 */
inline infix fun <reified R : XdEntity, reified V : Comparable<V>> KProperty1<R, V?>.ge(value: V): PropertyRange {
    return ge(getDBName(R::class), value, V::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is greater or equal to the given [value].
 */
inline infix fun <reified R : XdEntity> KProperty1<R, DateTime?>.ge(value: DateTime): PropertyRange {
    return ge(getDBName(R::class), value.millis, Long::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the property with name [dbPropertyName] is less or equal to the given [value].
 */
fun <V : Comparable<V>> le(dbPropertyName: String, value: V, valueKClass: KClass<V>): PropertyRange {
    return PropertyRange(dbPropertyName, valueKClass.minValue(), value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is less or equal to the given [value].
 */
inline infix fun <reified R : XdEntity, reified V : Comparable<V>> KProperty1<R, V?>.le(value: V): PropertyRange {
    return le(getDBName(R::class), value, V::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is less or equal to the given [value].
 */
inline infix fun <reified R : XdEntity> KProperty1<R, DateTime?>.le(value: DateTime): PropertyRange {
    return le(getDBName(R::class), value.millis, Long::class)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of the String property with name [dbPropertyName] starts with the given [value].
 */
fun startsWith(dbPropertyName: String, value: String?): NodeBase {
    return PropertyStartsWith(dbPropertyName, value ?: "")
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` String property starts with the given [value].
 */
inline infix fun <reified R : XdEntity> KProperty1<R, String?>.startsWith(value: String?): NodeBase {
    return startsWith(getDBName(R::class), value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property equal to the given [value].
 */
fun <R : XdEntity, T : XdEntity> KProperty1<R, T?>.eq(entityKClass: KClass<R>, value: T?): NodeBase {
    return LinkEqual(getDBName(entityKClass), value?.entity)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property equal to the given [value].
 */
inline infix fun <reified R : XdEntity, T : XdEntity> KProperty1<R, T?>.eq(value: T?): NodeBase {
    return eq(R::class, value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property contains the given [value].
 */
fun <R : XdEntity, T : XdEntity> KProperty1<R, XdQuery<T>>.contains(entityKClass: KClass<R>, value: T): NodeBase {
    return LinkEqual(getDBName(entityKClass), value.entity)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property contains the given [value].
 */
inline infix fun <reified R : XdEntity, T : XdEntity> KProperty1<R, XdQuery<T>>.contains(value: T): NodeBase {
    return contains(R::class, value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is not equal to the given [value].
 */
fun <R : XdEntity, T : XdEntity> KProperty1<R, T?>.ne(entityKClass: KClass<R>, value: T?): NodeBase = not(this.eq(entityKClass, value))

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property is not equal to the given [value].
 */
inline infix fun <reified R : XdEntity, T : XdEntity> KProperty1<R, T?>.ne(value: T?): NodeBase = ne(R::class, value)

@Deprecated("Use matches(entityKClass, node) instead", ReplaceWith("this.matches(entityKClass, node)", "kotlinx.dnq.query.NodeBaseOperationsKt.matches"))
fun <R : XdEntity, T : XdEntity> KProperty1<R, T?>.link(entityKClass: KClass<R>, node: NodeBase): NodeBase {
    return matches(entityKClass, node)
}

@Deprecated("Use matches(node) instead", ReplaceWith("this.matches(node)", "kotlinx.dnq.query.NodeBaseOperationsKt.matches"))
inline fun <reified R : XdEntity, T : XdEntity> KProperty1<R, T?>.link(node: NodeBase): NodeBase {
    return matches(node)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property matches the given [node] predicate.
 *
 * ### Sample
 * ```
 * XdUser.query(XdUser::contact.matches(XdContact::isVerified eq true))
 * ```
 */
fun <R : XdEntity, T : XdEntity> KProperty1<R, T?>.matches(entityKClass: KClass<R>, node: NodeBase): NodeBase {
    @Suppress("UNCHECKED_CAST")
    return LinksEqualDecorator(getDBName(entityKClass), node, (this.returnType.javaType as Class<T>).entityType.entityType)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` property matches the given [node] predicate.
 *
 * ### Sample
 * ```
 * XdUser.query(XdUser::contact.matches(XdContact::isVerified eq true))
 * ```
 */
inline fun <reified R : XdEntity, T : XdEntity> KProperty1<R, T?>.matches(node: NodeBase): NodeBase {
    return matches(R::class, node)
}

/**
 * Query node base condition that always returns nothing
 */
object None : NodeBase() {
    override fun getSimpleName(): String = "none"

    override fun getClone(): NodeBase = this

    override fun instantiate(entityType: String?, queryEngine: QueryEngine?, metaData: ModelMetaData?): MutableIterable<Entity> {
        return EntityIterableBase.EMPTY
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        return false
    }

    override fun replaceChild(child: NodeBase, newChild: NodeBase): NodeBase {
        throw UnsupportedOperationException()
    }
}

inline fun <reified T : XdEntity, R : XdEntity> KProperty1<T, R?>.containsIn(vararg entities: R?): NodeBase {
    return entities.fold(None as NodeBase) { tree, e -> tree or (this eq e) }
}

inline fun <reified T : XdEntity, R : Comparable<*>> KProperty1<T, R?>.containsIn(vararg values: R?): NodeBase {
    return values.fold(None as NodeBase) { tree, value -> tree or (this eq value) }
}

inline infix fun <reified T : XdEntity, reified R : XdEntity> KProperty1<T, R?>.inEntities(entities: Iterable<R?>): NodeBase {
    return this.containsIn(*entities.toList().toTypedArray())
}

inline infix fun <reified T : XdEntity, reified R : Comparable<*>> KProperty1<T, R?>.inValues(values: Iterable<R?>): NodeBase {
    return this.containsIn(*values.toList().toTypedArray())
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` Set property contains the given [value].
 */
inline infix fun <reified R : XdEntity, T : Comparable<T>> KProperty1<R, Set<T>>.contains(value: T): NodeBase {
    return eq(this.getDBName(R::class), value)
}

/**
 * Returns new [NodeBase] representing filter:
 *
 * value of `this` Set<String> property contains element that starts with the given [value].
 */
inline infix fun <reified R : XdEntity> KProperty1<R, Set<String>>.anyStartsWith(value: String?): NodeBase {
    return startsWith(getDBName(R::class), value)
}
