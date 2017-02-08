package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.query.XdMutableQuery
import kotlinx.dnq.query.XdQuery
import kotlinx.dnq.query.asQuery
import kotlinx.dnq.simple.XdConstrainedProperty
import java.lang.reflect.*
import kotlin.reflect.*
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
        val parentEntityClass = enclosingEntityClass.superclass as Class<out XdEntity>?
        return if (parentEntityClass == XdEntity::class.java) {
            null
        } else {
            val parentCompanion = parentEntityClass?.kotlin?.companionObjectInstance
            parentCompanion as? XdEntityType<*>
                    ?: throw IllegalArgumentException("Companion object of XdEntity should be XdEntityType")
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

inline fun <reified T : XdEntity, V : Any?> T.isDefined(property: KProperty1<T, V>): Boolean {
    return isDefined(T::class.java, property)
}

@Suppress("UNCHECKED_CAST")
fun <R : XdEntity, T : Any?> R.isDefined(clazz: Class<R>, property: KProperty1<R, T>): Boolean {
    val field = clazz.getDelegateField(property) ?:
            throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated")
    val delegateValue = field[this]
    return when (delegateValue) {
        is XdConstrainedProperty<*, *> -> (delegateValue as XdConstrainedProperty<R, *>).isDefined(this, property)
        is XdLink<*, *> -> (delegateValue as XdLink<R, *>).isDefined(this, property)
        else -> throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated to Xodus")
    }
}

fun <T : Any> Class<T>.getDelegateField(property: KProperty1<T, *>): Field? {
    return memberFields.firstOrNull { it.name == "${property.name}\$delegate" }?.let { field ->
        field.apply {
            field.isAccessible = true
        }
    }
}

private val DELEGATE = Regex("(\\w+)\\\$delegate")

fun <T : Any> Class<T>.getDelegatedFields(): List<Pair<KProperty1<*, *>, Field>> {
    return this.declaredFields.mapNotNull { field ->
        field.isAccessible = true
        val matchEntire = DELEGATE.matchEntire(field.name)
        if (matchEntire != null) {
            val (propertyName) = matchEntire.destructured
            val property = kotlin.declaredMemberProperties.firstOrNull { it.name == propertyName }
            if (property != null) {
                Pair(property, field)
            } else {
                null
            }
        } else {
            null
        }
    }
}

fun <R : XdEntity> KProperty1<R, *>.getDBName(klass: KClass<R>): String {
    return getDBName(klass.java.entityType)
}

fun <R : XdEntity> KProperty1<R, *>.getDBName(entityType: XdEntityType<R>): String {
    val node = XdModel[entityType]
    return node?.getDBName(this) ?: this.name
}

inline fun <reified R : XdEntity> KProperty1<R, *>.getDBName(): String {
    if (R::class.companionObjectInstance as? XdEntityType<R> == null) {
        throw IllegalArgumentException("Property owner class should have a companion object " +
                "that inherits from ${XdEntityType::class.java.simpleName}")
    }
    return this.getDBName(R::class.companionObjectInstance as XdEntityType<R>)
}

private fun <R : XdEntity> XdHierarchyNode.getDBName(prop: KProperty1<R, *>): String? {
    return this.simpleProperties[prop.name]?.delegate?.let { it.dbPropertyName ?: prop.name }
            ?: this.linkProperties[prop.name]?.delegate?.let { it.dbPropertyName ?: prop.name }
            ?: this.parentNode?.getDBName(prop)
}

val Class<*>.memberFields: Sequence<Field>
    get() = generateSequence(this) {
        it.superclass
    }.flatMap {
        it.declaredFields.asSequence()
    }

private fun <T : XdEntity> T.getPropertyName(property: KProperty1<T, *>): String {
    val clazz = this.javaClass
    val field = clazz.getDelegateField(property) ?:
            throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated")
    val delegateValue = field[this]
    return when (delegateValue) {
        is XdConstrainedProperty<*, *> -> delegateValue.dbPropertyName ?: property.name
        is XdLink<*, *> -> delegateValue.dbPropertyName ?: property.name
        else -> throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated to Xodus")
    }
}

fun <T : XdEntity> T.hasChanges(property: KProperty1<T, *>): Boolean {
    val name = getPropertyName(property)
    return EntityOperations.hasChanges(entity as TransientEntity, name)
}

fun <T : XdEntity, R : XdEntity?> T.getOldValue(property: KProperty1<T, R>): R? {
    val name = getPropertyName(property)
    @Suppress("UNCHECKED_CAST")
    val entity = AssociationSemantics.getOldValue(this.entity as TransientEntity, name)
    return entity?.let { it.wrapper as R }
}

fun <T : XdEntity, R> T.getOldValue(property: KProperty1<T, R>): R? {
    val name = getPropertyName(property)
    @Suppress("UNCHECKED_CAST")
    return PrimitiveAssociationSemantics.getOldValue(this.entity as TransientEntity, name, null) as R?
}

private fun <R : XdEntity, T : XdEntity> R.getLinksWrapper(property: KProperty1<R, XdMutableQuery<T>>, getLinks: (String) -> Iterable<Entity>): XdQuery<T> {
    val clazz = this.javaClass
    val field = clazz.getDelegateField(property) ?:
        throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated")
    @Suppress("UNCHECKED_CAST")
    val delegateValue = field[this] as? XdLink<R, T> ?:
        throw IllegalArgumentException("Property ${clazz.name}::$property is not a Xodus link")
    val name = delegateValue.dbPropertyName ?: property.name
    return getLinks(name).asQuery(delegateValue.oppositeEntityType)
}

fun <R : XdEntity, T : XdEntity> R.getAddedLinks(property: KProperty1<R, XdMutableQuery<T>>) = getLinksWrapper(property) {
    AssociationSemantics.getAddedLinks(entity as TransientEntity, it)
}

fun <R : XdEntity, T : XdEntity> R.getRemovedLinks(property: KProperty1<R, XdMutableQuery<T>>) = getLinksWrapper(property) {
    AssociationSemantics.getRemovedLinks(entity as TransientEntity, it)
}

@Suppress("UNCHECKED_CAST")
val <T : XdEntity> Class<T>.entityType: XdEntityType<T>
    get() = kotlin.companionObjectInstance as XdEntityType<T>