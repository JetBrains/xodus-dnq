package kotlinx.dnq.util

import com.jetbrains.teamsys.dnq.database.EntityOperations
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.XdModel
import kotlinx.dnq.link.XdLink
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
            parentCompanion?.let {
                if (parentCompanion is XdEntityType<*>) {
                    parentCompanion
                } else {
                    throw IllegalArgumentException("Companion object of XdEntity should be XdEntityType")
                }
            }
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
    val delegate = XdModel[entityType]?.simpleProperties?.get(this)?.delegate
    return delegate?.dbPropertyName ?: this.name
}

val Class<*>.memberFields: Sequence<Field>
    get() = generateSequence(this) {
        it.superclass
    }.flatMap {
        it.declaredFields.asSequence()
    }


fun <T : XdEntity> T.hasChanges(property: KProperty1<T, *>): Boolean {
    val clazz = this.javaClass
    val field = clazz.getDelegateField(property) ?:
            throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated")
    val delegateValue = field[this]
    val name = when (delegateValue) {
        is XdConstrainedProperty<*, *> -> delegateValue.dbPropertyName ?: property.name
        is XdLink<*, *> -> delegateValue.dbPropertyName ?: property.name
        else -> throw IllegalArgumentException("Property ${clazz.name}::$property is not delegated to Xodus")
    }

    return EntityOperations.hasChanges(entity as TransientEntity, name)
}

@Suppress("UNCHECKED_CAST")
val <T : XdEntity> Class<T>.entityType: XdEntityType<T>
    get() = kotlin.companionObjectInstance as XdEntityType<T>