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
package jetbrains.exodus.query.metadata

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType

internal enum class SchemaPropertyField { TYPE, LINKED_TYPE, MANDATORY, NOT_NULL }

internal sealed class YouTrackDbSchemaDifference {
    data class MissingClass(val desired: YouTrackDbClassTarget) : YouTrackDbSchemaDifference()
    data class MissingProperty(val className: String, val desired: YouTrackDbPropertyTarget) :
        YouTrackDbSchemaDifference()
    data class ActualOnlyClass(val actual: YouTrackDbStoredClass) : YouTrackDbSchemaDifference()
    data class ActualOnlyProperty(val className: String, val actual: YouTrackDbStoredProperty) :
        YouTrackDbSchemaDifference()
    data class PropertyValue(
        val className: String,
        val actual: YouTrackDbStoredProperty,
        val field: SchemaPropertyField,
        val expected: Any?,
        val observed: Any?
    ) : YouTrackDbSchemaDifference()
}

internal data class SchemaFlagRequirement(val className: String, val mandatory: Boolean, val notNull: Boolean)

internal data class YouTrackDbSchemaFlagConflict(
    val owner: String,
    val propertyName: String,
    val actual: YouTrackDbStoredProperty,
    val requirements: List<SchemaFlagRequirement>
)

internal data class YouTrackDbSchemaUnresolved(
    val className: String,
    val actual: YouTrackDbStoredProperty
)

internal data class YouTrackDbSchemaComparison(
    val actual: YouTrackDbStoredSchema,
    val differences: List<YouTrackDbSchemaDifference>,
    val flagConflicts: List<YouTrackDbSchemaFlagConflict>,
    val unresolved: List<YouTrackDbSchemaUnresolved>
)

/** Reads raw registration and the active session only. Does not prepare or apply the model. */
internal fun ModelMetaDataImpl.compareYouTrackDbSchema(
    session: DatabaseSessionEmbedded
): YouTrackDbSchemaComparison = compareYouTrackDbSchema(youTrackDbSchemaTarget(), session.youTrackDbStoredSchema())

/** Compares observable storage, not logical origins, ancestry relations, links or indexes. */
internal fun compareYouTrackDbSchema(
    desired: Map<String, YouTrackDbClassTarget>,
    actual: YouTrackDbStoredSchema
): YouTrackDbSchemaComparison {
    val differences = mutableListOf<YouTrackDbSchemaDifference>()
    val conflicts = mutableListOf<YouTrackDbSchemaFlagConflict>()
    val unresolved = mutableListOf<YouTrackDbSchemaUnresolved>()
    data class Declaration(val owner: String, val name: String)
    data class Requirement(val context: String, val target: YouTrackDbPropertyTarget, val stored: YouTrackDbStoredProperty)
    val groups = linkedMapOf<Declaration, MutableList<Requirement>>()

    for ((name, target) in desired) {
        val stored = actual.classes[name]
        if (stored == null) {
            differences.add(YouTrackDbSchemaDifference.MissingClass(target))
            continue
        }
        for ((propertyName, property) in target.properties) {
            // Desired names take precedence even for classified infrastructure or uncertain helpers.
            val found = stored.effectiveProperties[propertyName]
            if (found == null) {
                differences.add(YouTrackDbSchemaDifference.MissingProperty(name, property))
                continue
            }
            fun compare(field: SchemaPropertyField, expected: Any?, observed: Any?) {
                if (expected != observed) {
                    differences.add(YouTrackDbSchemaDifference.PropertyValue(name, found, field, expected, observed))
                }
            }
            compare(SchemaPropertyField.TYPE, property.mapping.type, found.type)
            if (property.mapping.type == EngineType.EMBEDDEDSET) {
                compare(SchemaPropertyField.LINKED_TYPE, property.mapping.linkedType, found.linkedType)
            }
            groups.getOrPut(Declaration(found.owner, found.name)) { mutableListOf() }
                .add(Requirement(name, property, found))
        }
    }

    for ((declaration, requirements) in groups) {
        val flags = requirements.map { SchemaFlagRequirement(it.context, it.target.mandatory, it.target.notNull) }
        if (flags.map { it.mandatory to it.notNull }.distinct().size > 1) {
            conflicts.add(YouTrackDbSchemaFlagConflict(
                declaration.owner, declaration.name, requirements.first().stored, flags.toList()
            ))
            continue
        }
        for ((context, target, stored) in requirements) {
            if (target.mandatory != stored.mandatory) {
                differences.add(YouTrackDbSchemaDifference.PropertyValue(
                    context, stored, SchemaPropertyField.MANDATORY, target.mandatory, stored.mandatory
                ))
            }
            if (target.notNull != stored.notNull) {
                differences.add(YouTrackDbSchemaDifference.PropertyValue(
                    context, stored, SchemaPropertyField.NOT_NULL, target.notNull, stored.notNull
                ))
            }
        }
    }

    for ((name, stored) in actual.classes) {
        if (name !in desired && stored.classification.kind == StoredSchemaKind.APPLICATION) {
            differences.add(YouTrackDbSchemaDifference.ActualOnlyClass(stored))
        }
        // Inspect even excluded classes to retain application edge payload.
        for ((propertyName, property) in stored.effectiveProperties) {
            if (propertyName in (desired[name]?.properties ?: emptyMap())) continue
            when (property.classification.kind) {
                StoredSchemaKind.APPLICATION ->
                    differences.add(YouTrackDbSchemaDifference.ActualOnlyProperty(name, property))
                StoredSchemaKind.UNRESOLVED -> unresolved.add(YouTrackDbSchemaUnresolved(name, property))
                StoredSchemaKind.INFRASTRUCTURE -> Unit
            }
        }
    }
    return YouTrackDbSchemaComparison(actual, differences.toList(), conflicts.toList(), unresolved.toList())
}
