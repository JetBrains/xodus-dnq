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
import jetbrains.exodus.query.metadata.RegistrationCategory
import jetbrains.exodus.query.metadata.ScalarDeclaration
import jetbrains.exodus.query.metadata.TypeDeclaration
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdNullableProperty
import kotlinx.dnq.simple.XdProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.XdWrappedProperty
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.reflect.jvm.javaType

/** Copies original local type, scalar and composite index facts from a fully registered node. */
internal fun XdHierarchyNode.captureTypeDeclaration(): TypeDeclaration {
    val owner = entityType.entityType
    val naturalType = entityType as? XdNaturalEntityType<*>
    val conditionalCandidates = linkedSetOf<String>()
    val scalars = simpleProperties.values.map { scalar ->
        // A concrete descendant can populate an abstract node after the node's constructor ran.
        val constraints = scalar.delegate.registeredConstraints()
        if (constraints.any { it is RequireIfConstraint<*, *> }) {
            conditionalCandidates.add(scalar.dbPropertyName)
        }
        scalar.captureDeclaration(owner, constraints)
    }
    return TypeDeclaration(
        name = owner,
        isAbstract = Modifier.isAbstract(entityType.enclosingEntityClass.modifiers),
        ancestors = generateSequence(parentNode) { it.parentNode }.map { it.entityType.entityType }.toList().asReversed(),
        registrationCategory = if (naturalType == null) RegistrationCategory.NON_NATURAL else RegistrationCategory.NATURAL,
        scalars = scalars,
        conditionalRequiredCandidates = conditionalCandidates,
        indexes = naturalType?.compositeIndices.orEmpty().map { index ->
            IndexDeclaration(owner, index.map { reference ->
                val field = resolveMetaProperty(reference)
                    ?: throw IllegalArgumentException("Cannot build composite index by property ${owner}::${reference.name}")
                IndexFieldDeclaration(field.dbPropertyName, field is XdHierarchyNode.SimpleProperty)
            })
        },
        identity = IdentityDeclaration("localEntityId", "LONG")
    )
}

private tailrec fun XdConstrainedProperty<*, *>.registeredConstraints(): List<PropertyConstraint<*>> =
    if (this is XdWrappedProperty<*, *, *>) wrapped.registeredConstraints() else constraints

private fun XdHierarchyNode.SimpleProperty.captureDeclaration(
    owner: String,
    constraints: List<PropertyConstraint<*>>
): ScalarDeclaration {
    // The Kotlin property's logical type supplies the metadata name, even for a wrapped delegate.
    val (typeName, parameters) = when (val javaType = property.returnType.javaType) {
        is Class<*> -> javaType.simpleName to null
        is ParameterizedType -> (javaType.rawType as Class<*>).simpleName to
            javaType.actualTypeArguments.map { it.typeName.substringAfterLast(".").lowercase() }
        else -> throw IllegalArgumentException("Cannot identify simple property type name")
    }
    val customType = when (val direct = delegate) {
        is XdProperty<*, *> -> direct.binding?.let { CustomTypeDeclaration(direct.clazz.name, it.javaClass.name) }
        is XdNullableProperty<*, *> -> direct.binding?.let { CustomTypeDeclaration(direct.clazz.name, it.javaClass.name) }
        else -> null
    }
    return ScalarDeclaration(
        property = DeclarationPropertyId(owner, property.name),
        databaseName = dbPropertyName,
        kind = delegate.propertyType,
        primitiveTypeName = typeName,
        parameters = parameters,
        autoIndexed = delegate.isAutoIndexed,
        requirement = when (delegate.requirement) {
            XdPropertyRequirement.OPTIONAL -> DeclarationRequirement.OPTIONAL
            XdPropertyRequirement.REQUIRED -> DeclarationRequirement.REQUIRED
            XdPropertyRequirement.UNIQUE -> DeclarationRequirement.UNIQUE
        },
        constraints = constraints.map { it.captureDeclaration() },
        customType = customType
    )
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
