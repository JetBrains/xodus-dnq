/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.entitystore.constraints.inRange
import jetbrains.exodus.entitystore.constraints.length
import jetbrains.exodus.query.metadata.ConstraintDeclaration
import jetbrains.exodus.query.metadata.ConstraintImplementation
import jetbrains.exodus.query.metadata.ConstraintKind
import jetbrains.exodus.query.metadata.CustomTypeDeclaration
import jetbrains.exodus.query.metadata.DeclarationPropertyId
import jetbrains.exodus.query.metadata.DeclarationRequirement
import jetbrains.exodus.query.metadata.IdentityDeclaration
import jetbrains.exodus.query.metadata.IndexDeclaration
import jetbrains.exodus.query.metadata.IndexFieldDeclaration
import jetbrains.exodus.query.metadata.PropertyType
import jetbrains.exodus.query.metadata.RegistrationCategory
import jetbrains.exodus.query.metadata.ScalarDeclaration
import jetbrains.exodus.query.metadata.TypeDeclaration
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdNullableProperty
import kotlinx.dnq.simple.XdProperty
import kotlinx.dnq.simple.XdWrappedProperty
import kotlinx.dnq.simple.custom.type.XdCustomTypeProperty
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaType

internal class MissingDeclarationFact(declaration: String, fact: DeclarationFactKind<*>) :
    IllegalArgumentException("Missing declaration fact ${fact.label} for $declaration")

/** Keys pair a plain value with a current owner and, for property facts, its exact registered slot. */
internal class DeclarationFactKind<T : Any> private constructor(val label: String) {
    fun at(owner: XdHierarchyNode, value: T, slot: XdHierarchyNode.MetaProperty? = null) =
        DeclarationFact(this, owner, slot, value)

    companion object {
        val TYPE_NAME = DeclarationFactKind<String>("type name")
        val INDEXES = DeclarationFactKind<ExplicitIndexFacts>("explicit indexes")
        val KOTLIN_NAME = DeclarationFactKind<String>("Kotlin name")
        val LOGICAL_TYPE = DeclarationFactKind<LogicalScalarType>("logical scalar type")
        val DATABASE_NAME = DeclarationFactKind<String>("database name")
        val KIND = DeclarationFactKind<PropertyType>("scalar kind")
        val AUTO_INDEXED = DeclarationFactKind<Boolean>("auto-indexing")
        val REQUIREMENT = DeclarationFactKind<DeclarationRequirement>("requiredness")
        val CONSTRAINTS = DeclarationFactKind<ScalarConstraintFacts>("constraints")
        val BINDING = DeclarationFactKind<DirectBindingFact>("direct binding")
    }
}

internal class DeclarationFact<T : Any>(
    val kind: DeclarationFactKind<T>,
    val owner: XdHierarchyNode,
    val slot: XdHierarchyNode.MetaProperty?,
    val value: T
)

// Arrays are traversed directly. No model-supplied collection or iterator enters the plain-data seam.
internal class ExplicitIndexFacts(vararg fields: Array<out String>) {
    val indexes: List<List<String>> = Collections.unmodifiableList(fields.map {
        Collections.unmodifiableList(it.toList())
    })
}

internal class LogicalScalarType(val name: String, parameters: Array<out String>? = null) {
    val parameters: List<String>? = parameters?.let { Collections.unmodifiableList(it.toList()) }
}

internal class ScalarConstraintFacts(val conditionalRequired: Boolean, vararg descriptors: ConstraintDeclaration) {
    val descriptors: List<ConstraintDeclaration> = Collections.unmodifiableList(descriptors.toList())

    init {
        require(!conditionalRequired || this.descriptors.any { it.implementationId == RequireIfConstraint::class.java.name }) {
            "Conditional requiredness needs a constraint descriptor"
        }
    }
}

internal class DirectBindingFact(val descriptor: CustomTypeDeclaration?) {
    init {
        require(descriptor == null || descriptor.bindingImplementationId != null) {
            "A direct binding needs a binding implementation identity"
        }
    }
}

private class FactAddress(
    val kind: DeclarationFactKind<*>,
    val owner: XdHierarchyNode,
    val slot: XdHierarchyNode.MetaProperty?
) {
    override fun equals(other: Any?) =
        other is FactAddress && kind === other.kind && owner === other.owner && slot === other.slot

    override fun hashCode() =
        31 * (31 * System.identityHashCode(kind) + System.identityHashCode(owner)) + System.identityHashCode(slot)
}

/**
 * Copies original local facts from a completed, stable hierarchy. The caller supplies only current
 * plain facts that the requested capture needs and cannot safely read. Each call consumes its own evidence.
 */
internal fun XdHierarchyNode.captureTypeDeclaration(vararg facts: DeclarationFact<*>): TypeDeclaration =
    TypeCapture(facts).capture(this)

private class TypeCapture(evidence: Array<out DeclarationFact<*>>) {
    private val supplied = LinkedHashMap<FactAddress, DeclarationFact<*>>()
    private val used = HashSet<FactAddress>()
    private val reflected = IdentityHashMap<XdHierarchyNode, Map<String, KProperty1<*, *>>>()

    init {
        for (entry in evidence) {
            val address = FactAddress(entry.kind, entry.owner, entry.slot)
            require(supplied.put(address, entry) == null) {
                "Duplicate declaration fact ${entry.kind.label} for ${entry.owner.entityType.javaClass.name}"
            }
        }
    }

    private fun <T : Any> fact(
        kind: DeclarationFactKind<T>,
        owner: XdHierarchyNode,
        slot: XdHierarchyNode.MetaProperty? = null,
        key: String? = null,
        known: T?
    ): T {
        val id = owner.entityType.javaClass.name + (key?.let { "::$it" } ?: "")
        val address = FactAddress(kind, owner, slot)
        val entry = supplied[address] ?: return known ?: throw MissingDeclarationFact(id, kind)
        require(known == null) { "Cannot override declaration fact ${kind.label} for $id" }
        used.add(address)
        // A kind is the typed key used to construct the matching entry.
        @Suppress("UNCHECKED_CAST")
        return entry.value as T
    }

    fun capture(node: XdHierarchyNode): TypeDeclaration {
        val owner = typeName(node)
        val ancestors = generateSequence(node.parentNode) { it.parentNode }.toList().asReversed().map(::typeName)
        val conditionalCandidates = linkedSetOf<String>()
        val scalars = node.simpleProperties.mapValues { (key, scalar) ->
            captureScalar(node, owner, key, scalar, conditionalCandidates)
        }
        val natural = node.entityType as? XdNaturalEntityType<*>
        val knownIndexes = if (natural == null ||
            natural.usesGetter("getCompositeIndices", XdNaturalEntityType::class.java)
        ) ExplicitIndexFacts() else null
        // The audited base accessor always supplies an empty list. Never read or copy an override.
        val indexes = fact(DeclarationFactKind.INDEXES, node, known = knownIndexes).indexes.map { fields ->
            IndexDeclaration(owner, fields.map { key ->
                scalars[key]?.let { IndexFieldDeclaration(it.databaseName, true) }
                    ?: resolveIndexField(node, key)
                    ?: throw IllegalArgumentException("Cannot build composite index by property ${owner}::$key")
            })
        }
        for ((address, entry) in supplied) {
            require(address in used) {
                "Unused declaration fact ${entry.kind.label} for ${entry.owner.entityType.javaClass.name}"
            }
        }
        return TypeDeclaration(
            name = owner,
            isAbstract = Modifier.isAbstract(node.entityType.javaClass.enclosingClass.modifiers),
            ancestors = ancestors,
            registrationCategory = if (natural == null) RegistrationCategory.NON_NATURAL else RegistrationCategory.NATURAL,
            scalars = scalars.values.toList(),
            conditionalRequiredCandidates = conditionalCandidates,
            indexes = indexes,
            identity = IdentityDeclaration("localEntityId", "LONG")
        )
    }

    private fun typeName(node: XdHierarchyNode): String {
        val type = node.entityType
        val known = if (type.usesGetter("getEntityType", XdNaturalEntityType::class.java)) type.entityType else null
        return fact(DeclarationFactKind.TYPE_NAME, node, known = known)
    }

    private fun trustedProperty(node: XdHierarchyNode, key: String, slot: XdHierarchyNode.MetaProperty) =
        reflected.getOrPut(node) {
            node.entityType.javaClass.enclosingClass.kotlin.declaredMemberProperties.associateBy { it.name }
        }[key]?.takeIf { it === slot.property }

    private fun kotlinName(
        node: XdHierarchyNode, key: String, slot: XdHierarchyNode.MetaProperty, known: String?
    ): String = fact(DeclarationFactKind.KOTLIN_NAME, node, slot, key, known).also {
        require(it == key) { "Kotlin name does not match registered slot ${node.entityType.javaClass.name}::$key" }
    }

    private tailrec fun resolveIndexField(node: XdHierarchyNode, key: String): IndexFieldDeclaration? {
        // Match the legacy local scalar, local link, then parent lookup by Kotlin name.
        node.simpleProperties[key]?.let { scalar ->
            val delegate = scalar.delegate.storageDelegate()
            val name = databaseName(node, key, scalar, delegate)
            return IndexFieldDeclaration(name, true)
        }
        node.linkProperties[key]?.let { link ->
            val name = link.delegate.dbPropertyName ?: kotlinName(node, key, link, trustedProperty(node, key, link)?.name)
            return IndexFieldDeclaration(name, false)
        }
        return resolveIndexField(node.parentNode ?: return null, key)
    }

    private fun databaseName(
        node: XdHierarchyNode,
        key: String,
        scalar: XdHierarchyNode.SimpleProperty,
        storage: XdConstrainedProperty<*, *>,
        name: String? = null
    ): String {
        val known = if (storage.usesGetter("getDbPropertyName", XdConstrainedProperty::class.java)) {
            storage.dbPropertyName ?: name ?: kotlinName(node, key, scalar, trustedProperty(node, key, scalar)?.name)
        } else null
        return fact(DeclarationFactKind.DATABASE_NAME, node, scalar, key, known)
    }

    private fun captureScalar(
        node: XdHierarchyNode,
        owner: String,
        key: String,
        scalar: XdHierarchyNode.SimpleProperty,
        conditionalCandidates: MutableSet<String>
    ): ScalarDeclaration {
        val property = trustedProperty(node, key, scalar)
        val name = kotlinName(node, key, scalar, property?.name)
        val type = fact(DeclarationFactKind.LOGICAL_TYPE, node, scalar, key, property?.let {
            // Use only trusted reflection, never an arbitrary registered KProperty implementation.
            when (val javaType = it.returnType.javaType) {
                is Class<*> -> LogicalScalarType(javaType.simpleName)
                is ParameterizedType -> LogicalScalarType((javaType.rawType as Class<*>).simpleName,
                    javaType.actualTypeArguments.map { arg -> arg.typeName.substringAfterLast(".").lowercase() }.toTypedArray())
                else -> throw IllegalArgumentException("Cannot identify simple property type name")
            }
        })
        val direct = scalar.delegate
        val storage = direct.storageDelegate()
        val databaseName = databaseName(node, key, scalar, storage, name)
        val base = XdConstrainedProperty::class.java
        val kind = fact(DeclarationFactKind.KIND, node, scalar, key,
            if (direct.usesGetter("getPropertyType", base)) direct.propertyType else null)
        val indexed = fact(DeclarationFactKind.AUTO_INDEXED, node, scalar, key,
            if (storage.usesGetter("isAutoIndexed", base, XdProperty::class.java, XdNullableProperty::class.java)) {
                storage.isAutoIndexed
            } else null)
        val requirement = fact(DeclarationFactKind.REQUIREMENT, node, scalar, key,
            if (storage.usesGetter("getRequirement", base)) DeclarationRequirement.valueOf(storage.requirement.name) else null)
        val constraints = constraints(node, key, scalar, direct, storage)
        val binding = fact(DeclarationFactKind.BINDING, node, scalar, key, direct.safeBinding())
        if (constraints.conditionalRequired) conditionalCandidates.add(databaseName)
        return ScalarDeclaration(DeclarationPropertyId(owner, name), databaseName, kind, type.name, type.parameters,
            indexed, requirement, constraints.descriptors, binding.descriptor)
    }

    private fun constraints(
        node: XdHierarchyNode,
        key: String,
        scalar: XdHierarchyNode.SimpleProperty,
        direct: XdConstrainedProperty<*, *>,
        storage: XdConstrainedProperty<*, *>
    ): ScalarConstraintFacts {
        val list = storage.safeConstraintList()
        // Legacy membership unwraps once. An inner wrapper has its own empty list.
        val knownCandidate = if (direct is XdWrappedProperty<*, *, *> && direct.wrapped !== storage) {
            false
        } else {
            list?.any { it is RequireIfConstraint<*, *> }
        }
        val known = list?.let {
            ScalarConstraintFacts(
                checkNotNull(knownCandidate), *it.map { constraint -> constraint.captureDeclaration() }.toTypedArray()
            )
        }
        val result = fact(DeclarationFactKind.CONSTRAINTS, node, scalar, key, known)
        require(knownCandidate == null || knownCandidate == result.conditionalRequired) {
            "Cannot override declaration fact conditional requiredness for ${node.entityType.javaClass.name}::$key"
        }
        return result
    }
}

/** Only audited method bodies are safe. An override can be final and still execute model code. */
private fun Any.usesGetter(name: String, vararg audited: Class<*>): Boolean {
    val method = javaClass.methods.singleOrNull { it.name == name && it.parameterCount == 0 } ?: return false
    return audited.any { it === method.declaringClass }
}

private tailrec fun XdConstrainedProperty<*, *>.storageDelegate(): XdConstrainedProperty<*, *> =
    if (this is XdWrappedProperty<*, *, *>) wrapped.storageDelegate() else this

private fun XdConstrainedProperty<*, *>.safeConstraintList(): List<PropertyConstraint<*>>? {
    if (!usesGetter("getConstraints", XdConstrainedProperty::class.java)) return null
    val list = constraints
    return list.takeIf {
        it.javaClass === ArrayList::class.java || it === emptyList<Any>() || it === Collections.emptyList<Any>()
    }
}

private fun XdConstrainedProperty<*, *>.safeBinding(): DirectBindingFact? = when (this) {
    is XdProperty<*, *> -> DirectBindingFact(binding?.let { CustomTypeDeclaration(clazz.name, it.javaClass.name) })
    is XdNullableProperty<*, *> -> DirectBindingFact(binding?.let { CustomTypeDeclaration(clazz.name, it.javaClass.name) })
    is XdCustomTypeProperty<*> -> null
    else -> DirectBindingFact(null)
}

private fun PropertyConstraint<*>.captureDeclaration(): ConstraintDeclaration {
    val implementationId = javaClass.name
    return when (this) {
        is length -> ConstraintDeclaration(
            ConstraintKind.LENGTH, implementationId,
            if (javaClass == length::class.java) ConstraintImplementation.BUILT_IN else ConstraintImplementation.SUBCLASS,
            minLength = min, maxLength = max
        )
        is inRange<*> -> ConstraintDeclaration(
            ConstraintKind.NUMERIC_RANGE, implementationId,
            if (javaClass == inRange::class.java) ConstraintImplementation.BUILT_IN else ConstraintImplementation.SUBCLASS,
            minValue = min, maxValue = max
        )
        else -> ConstraintDeclaration(ConstraintKind.CUSTOM, implementationId, ConstraintImplementation.CUSTOM)
    }
}
