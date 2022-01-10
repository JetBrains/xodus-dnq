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
package kotlinx.dnq.util

import jetbrains.exodus.core.dataStructures.SoftConcurrentObjectCache
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.XdModel
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.asQuery
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.toXd
import org.joda.time.DateTime
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmName

internal val <T : XdEntity> XdEntityType<T>.enclosingEntityClass: Class<out T>
    get() {
        val entityTypeClass = this.javaClass
        val entityClass = entityTypeClass.enclosingClass
        if (entityClass == null || entityClass.kotlin.companionObject?.java != entityTypeClass) {
            throw IllegalArgumentException("Entity type should be a companion object of some XdEntity")
        }
        if (!XdEntity::class.java.isAssignableFrom(entityClass)) {
            throw IllegalArgumentException("Enclosing type is supposed to be an XdEntity")
        }
        @Suppress("UNCHECKED_CAST")
        return entityClass as Class<out T>
    }

val XdEntityType<*>.parent: XdEntityType<*>?
    get() {
        @Suppress("UNCHECKED_CAST")
        val parentEntityClass = enclosingEntityClass.superclass as Class<out XdEntity>
        return if (parentEntityClass == XdEntity::class.java) {
            null
        } else {
            parentEntityClass.entityType
        }
    }

val <T : XdEntity> XdEntityType<T>.entityConstructor: ((Entity) -> T)?
    get() {
        val entityClass = enclosingEntityClass
        return if ((entityClass.modifiers and Modifier.ABSTRACT) != 0) {
            null
        } else {
            val constructor = try {
                entityClass.getConstructor(Entity::class.java)
            } catch (e: Exception) {
                throw IllegalArgumentException("Enclosing XdEntity should have constructor(${Entity::class.jvmName})")
            }
            { entity -> constructor.newInstance(entity) }
        }
    }

/**
 * Safely checks if the value of a property is not null or empty. It is especially useful for required
 * xd-properties like xdLink1, xdLink1_N, xdRequired and so on, that will throw on access if the value is undefined.
 * As for simple property types, only Iterable, Sequence and Array inheritors are supported. Calling this function
 * for any other "massive" property type will result in the true value.
 * As of 2.0.0, for Boolean properties, it always returns `true`.
 */
inline fun <reified T : XdEntity, V : Any?> T.isDefined(property: KProperty1<T, V>): Boolean {
    return isDefined(T::class.java, property)
}

/**
 * Safely checks if the value of a property is not null or empty. It is especially useful for required
 * xd-properties like xdLink1, xdLink1_N, xdRequired and so on, that will throw on access if the value is undefined.
 * As for simple property types, only Iterable, Sequence and Array inheritors are supported. Calling this function
 * for any other "massive" property type will result in the true value.
 */
fun <R : XdEntity, T : Any?> R.isDefined(clazz: Class<R>, property: KProperty1<R, T>): Boolean {
    return isDefined(clazz.entityType, property, clazz)
}

/**
 * Safely checks if the value of a property is not null or empty. It is especially useful for required
 * xd-properties like xdLink1, xdLink1_N, xdRequired and so on, that will throw on access if the value is undefined.
 * As for simple property types, only Iterable, Sequence and Array inheritors are supported. Calling this function
 * for any other "massive" property type will result in the true value.
 */
@Suppress("UNCHECKED_CAST")
fun <R : XdEntity, T : Any?> R.isDefined(entityType: XdEntityType<R>, property: KProperty1<R, T>): Boolean {
    return isDefined(entityType, property, null)
}

private fun <R : XdEntity, T : Any?> R.isDefined(entityType: XdEntityType<R>, property: KProperty1<R, T>, entityClass: Class<R>?): Boolean {
    // do all this fuzzy stuff only if the property is open and the entity class is open too
    val (realProperty, realEntityType) = javaClass.getPropertyMeta(property, entityType, entityClass)

    val metaProperty = XdModel.getOrThrow(realEntityType.entityType).resolveMetaProperty(realProperty)

    @Suppress("UNCHECKED_CAST")
    return when (metaProperty) {
        is XdHierarchyNode.SimpleProperty -> (metaProperty.delegate as XdConstrainedProperty<R, *>).isDefined(this, property)
        is XdHierarchyNode.LinkProperty -> (metaProperty.delegate as XdLink<R, *>).isDefined(this, property)
        null -> {
            val value = realProperty.get(this)
            if (value != null) {
                (value as? Iterable<*>)?.any() ?: (value as? Sequence<*>)?.any() ?: (value as? Array<*>)?.any() ?: true
            } else {
                false
            }
        }
        else -> throw UnsupportedOperationException("Type ${metaProperty.javaClass} of meta " +
                "property ${realEntityType.entityType}#${metaProperty.property} is not supported")
    }
}

private val propertiesCache by lazy(LazyThreadSafetyMode.NONE) {
    SoftConcurrentObjectCache<String, Pair<KProperty1<*, Any?>, XdEntityType<*>>>(XdModel.hierarchy.size * 10)
}

@Suppress("UNCHECKED_CAST")
private fun <R : XdEntity, T : Any?> Class<R>.getPropertyMeta(
        property: KProperty1<R, T>,
        xdEntityType: XdEntityType<R>,
        entityClass: Class<R>?): Pair<KProperty1<R, Any?>, XdEntityType<R>> {
    val key = simpleName + "|" + property.name + "|" + xdEntityType.javaClass.simpleName + "|" + entityClass?.simpleName
    val cached = propertiesCache.tryKey(key)
    if (cached == null) {
        val realProperty = if (property.isFinal || entityClass?.kotlin?.isFinal == true) {
            property
        } else {
            kotlin.memberProperties.single { it.name == property.name }
        }
        val realEntityType: XdEntityType<R> = if (realProperty == property) {
            xdEntityType
        } else {
            entityType
        }
        val pair = realProperty to realEntityType
        propertiesCache.cacheObject(key, pair)
        return pair
    }
    return cached as Pair<KProperty1<R, Any?>, XdEntityType<R>>
}

/**
 * @see XD.getSafe(entityType: XdEntityType<XD>, property: KProperty1<XD, V>): V?
 */
inline fun <reified XD : XdEntity, V : Any> XD.getSafe(property: KProperty1<XD, V>) = getSafe(XD::class.java, property)

/**
 * @see XD.getSafe(entityType: XdEntityType<XD>, property: KProperty1<XD, V>): V?
 */
fun <XD : XdEntity, V : Any> XD.getSafe(clazz: Class<XD>, property: KProperty1<XD, V>) = getSafe(clazz.entityType, property)

/**
 * @return value of the value of the `property` or null if the property is undefined
 */
fun <XD : XdEntity, V : Any> XD.getSafe(entityType: XdEntityType<XD>, property: KProperty1<XD, V>): V? {
    return if (isDefined(entityType, property)) property.get(this) else null
}

fun <R : XdEntity> KProperty1<R, *>.getDBName(klass: KClass<R>): String {
    return getDBName(klass.java.entityType)
}

fun <R : XdEntity> KProperty1<R, *>.getDBName(entityType: XdEntityType<R>): String {
    val node = XdModel[entityType]
    return node?.resolveMetaProperty(this)?.dbPropertyName ?: this.name
}

inline fun <reified R : XdEntity> KProperty1<R, *>.getDBName() = getDBName(R::class.entityType)

fun <T : XdEntity> T.hasChanges(): Boolean {
    return reattach().hasChanges()
}

fun <T : XdEntity> T.hasChanges(property: KProperty1<T, *>): Boolean {
    return reattach().hasChanges(property.getDBName(javaClass.entityType))
}

fun <T : XdEntity, R : XdEntity> T.getOldValue(property: KProperty1<T, R?>): R? {
    return getOldLinkValue(property.getDBName(javaClass.entityType))?.toXd()
}

fun <T : XdEntity> T.getOldValue(property: KProperty1<T, DateTime?>): DateTime? {
    return getOldPrimitiveValue(property.getDBName(javaClass.entityType))?.let(::DateTime)
}

fun <T : XdEntity, R : Comparable<*>?> T.getOldValue(property: KProperty1<T, R>): R? {
    @Suppress("UNCHECKED_CAST")
    return getOldPrimitiveValue(property.getDBName(javaClass.entityType)) as R?
}

private fun <R : XdEntity, T : XdEntity> R.getLinksWrapper(property: KProperty1<R, XdMutableQuery<T>>, getLinks: (String) -> Iterable<Entity>): XdQuery<T> {
    val clazz = this.javaClass
    val metaProperty = XdModel.getOrThrow(clazz.entityType.entityType).resolveMetaProperty(property) as? XdHierarchyNode.LinkProperty
            ?: throw IllegalArgumentException("Property ${clazz.name}::$property is not a Xodus link")

    @Suppress("UNCHECKED_CAST")
    val delegateValue = metaProperty.delegate as XdLink<R, T>
    return getLinks(metaProperty.dbPropertyName).asQuery(delegateValue.oppositeEntityType)
}

fun <R : XdEntity, T : XdEntity> R.getAddedLinks(property: KProperty1<R, XdMutableQuery<T>>) = getLinksWrapper(property) { linkName ->
    this.getAddedLinks(linkName)
}

fun <R : XdEntity, T : XdEntity> R.getRemovedLinks(property: KProperty1<R, XdMutableQuery<T>>) = getLinksWrapper(property) { linkName ->
    this.getRemovedLinks(linkName)
}

val <T : XdEntity> Class<T>.entityType: XdEntityType<T>
    get() = kotlin.entityType

private val entityTypeCache = ConcurrentHashMap<Class<*>, XdEntityType<*>>()

@Suppress("UNCHECKED_CAST")
val <T : XdEntity> KClass<T>.entityType: XdEntityType<T>
    get() = entityTypeCache.getOrPut(java) {
        companionObjectInstance as? XdEntityType<T>
                ?: throw IllegalArgumentException("XdEntity contract is broken for $java, its companion object is not an instance of XdEntityType")
    } as XdEntityType<T>
