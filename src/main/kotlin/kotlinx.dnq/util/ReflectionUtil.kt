package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.XdModel
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.asQuery
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.wrapper
import org.joda.time.DateTime
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.defaultType
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmName


fun <B, T : B> Class<T>.inferTypeParameters(baseClass: Class<B>): Array<Type> {
    val hierarchy = generateSequence<Class<*>>(this) { it.superclass }

    var prevMapping: Map<TypeVariable<*>, Type> = hierarchy.first().typeParameters.map { it to it }.toMap()
    for (type in hierarchy.takeWhile { it != baseClass }) {
        val parameters = type.superclass.typeParameters
        val arguments = (type.genericSuperclass as? ParameterizedType)?.actualTypeArguments
        prevMapping = if (parameters.isNotEmpty() && arguments != null) {
            (parameters zip arguments).map {
                val (parameter, argument) = it
                parameter to if (argument is TypeVariable<*> && argument in prevMapping.keys) {
                    prevMapping[argument]!!
                } else {
                    it.second
                }
            }.toMap()
        } else {
            emptyMap()
        }
    }
    return prevMapping.values.toTypedArray()
}

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
            } catch(e: Exception) {
                throw IllegalArgumentException("Enclosing XdEntity should have constructor(${Entity::class.jvmName})")
            }
            { entity -> constructor.newInstance(entity) }
        }
    }

@Suppress("UNCHECKED_CAST")
inline fun <reified T : XdEntity, V : Any?> T.isDefined(property: KProperty1<T, V>): Boolean {
    return isDefined(T::class.java, property)
}

fun <R : XdEntity, T : Any?> R.isDefined(clazz: Class<R>, property: KProperty1<R, T>): Boolean {
    return isDefined(clazz.entityType, property)
}

@Suppress("UNCHECKED_CAST")
fun <R : XdEntity, T : Any?> R.isDefined(entityType: XdEntityType<R>, property: KProperty1<R, T>): Boolean {
    // do all this fuzzy stuff only if the property is open
    val realProperty = if (property.isFinal) {
        property
    } else {
        this.javaClass.kotlin.memberProperties.single { it.name == property.name }
    }

    val realEntityType = if (realProperty == property) {
        entityType
    } else {
        this.javaClass.entityType
    }

    val metaProperty = XdModel.getOrThrow(realEntityType.entityType).resolveMetaProperty(realProperty)

    return when (metaProperty) {
        is XdHierarchyNode.SimpleProperty -> (metaProperty.delegate as XdConstrainedProperty<R, *>).isDefined(this, property)
        is XdHierarchyNode.LinkProperty -> (metaProperty.delegate as XdLink<R, *>).isDefined(this, property)
        null -> {
            // simple property
            val value = realProperty.get(this)
            if (value != null) {
                // todo? Map, ByteArray, LongArray,...
                (value as? Iterable<*>)?.any() ?: (value as? Sequence<*>)?.any() ?: (value as? Array<*>)?.any() ?: true
            } else {
                false
            }
        }
        else -> throw IllegalArgumentException("Property ${entityType.entityType}::$property is not delegated to Xodus")
    }
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

fun <T : XdEntity> T.hasChanges(property: KProperty1<T, *>): Boolean {
    val name = property.getDBName(javaClass.entityType)
    return EntityOperations.hasChanges(entity as TransientEntity, name)
}

fun <T : XdEntity, R : XdEntity?> T.getOldValue(property: KProperty1<T, R>): R? {
    val name = property.getDBName(javaClass.entityType)
    @Suppress("UNCHECKED_CAST")
    val entity = AssociationSemantics.getOldValue(this.entity as TransientEntity, name)
    return entity?.let { it.wrapper as R }
}

fun <T : XdEntity> T.getOldValue(property: KProperty1<T, DateTime?>): DateTime? {
    val name = property.getDBName(javaClass.entityType)
    return PrimitiveAssociationSemantics.getOldValue(this.entity as TransientEntity, name, Long::class.java, null)?.let(::DateTime)
}

fun <T : XdEntity, R : Comparable<*>?> T.getOldValue(property: KProperty1<T, R>): R? {
    val name = property.getDBName(javaClass.entityType)
    @Suppress("UNCHECKED_CAST")
    return PrimitiveAssociationSemantics.getOldValue(this.entity as TransientEntity, name, null) as R?
}

private fun <R : XdEntity, T : XdEntity> R.getLinksWrapper(property: KProperty1<R, XdMutableQuery<T>>, getLinks: (String) -> Iterable<Entity>): XdQuery<T> {
    val clazz = this.javaClass
    val metaProperty = XdModel.getOrThrow(clazz.entityType.entityType).resolveMetaProperty(property) as? XdHierarchyNode.LinkProperty
            ?: throw IllegalArgumentException("Property ${clazz.name}::$property is not a Xodus link")

    @Suppress("UNCHECKED_CAST")
    val delegateValue = metaProperty.delegate as XdLink<R, T>
    return getLinks(metaProperty.dbPropertyName).asQuery(delegateValue.oppositeEntityType)
}

fun <R : XdEntity, T : XdEntity> R.getAddedLinks(property: KProperty1<R, XdMutableQuery<T>>) = getLinksWrapper(property) {
    AssociationSemantics.getAddedLinks(entity as TransientEntity, it)
}

fun <R : XdEntity, T : XdEntity> R.getRemovedLinks(property: KProperty1<R, XdMutableQuery<T>>) = getLinksWrapper(property) {
    AssociationSemantics.getRemovedLinks(entity as TransientEntity, it)
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
