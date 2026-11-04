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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.LOCAL_ENTITY_ID_PROPERTY_NAME

internal data class YouTrackDbPropertyTarget(
    val mapping: YouTrackDbPropertyMapping,
    val mandatory: Boolean,
    val notNull: Boolean
)

internal data class YouTrackDbClassTarget(
    val name: String,
    val properties: Map<String, YouTrackDbPropertyTarget>
)

/**
 * Reads registered metadata only. Callers must finish registration before projection.
 * The registration copy is not a deep snapshot of concurrently mutable entity metadata.
 */
internal fun ModelMetaDataImpl.youTrackDbSchemaTarget(): Map<String, YouTrackDbClassTarget> {
    val raw = linkedMapOf<String, EntityMetaData>()
    for (entity in registeredEntityMetaDatas) {
        ModelMetaDataImpl.validateClassMetaData(entity)
        require(raw.put(entity.type, entity) == null) { "Duplicate entity [${entity.type}]" }
    }

    fun metadata(name: String): EntityMetaData =
        requireNotNull(raw[name]) { "No entity metadata for super type [$name]" }

    fun ancestry(entity: EntityMetaData): List<EntityMetaData> {
        val result = mutableListOf<EntityMetaData>()
        val visited = mutableSetOf<String>()
        var type: String? = entity.type
        while (type != null) {
            require(visited.add(type)) { "Cyclic entity ancestry at [$type]" }
            val data = metadata(type)
            result.add(data)
            type = data.superType
        }
        return result
    }

    return raw.mapValues { (_, entity) ->
        val required = entity.requiredProperties.toMutableSet()
        for (context in ancestry(entity)) {
            for (index in context.ownIndexes) {
                required.addAll(index.fields.filter { it.isProperty }.map { it.name })
            }
        }
        val properties = linkedMapOf<String, YouTrackDbPropertyTarget>()
        // DNQ supplies inherited properties and requirements in each entity's own metadata.
        for (property in entity.propertiesMetaData.filterIsInstance<PropertyMetaDataImpl>()) {
            val mapping = property.toYouTrackDbProperty()
            val isRequired = property.name in required
            properties[property.name] = YouTrackDbPropertyTarget(mapping, isRequired, isRequired)
        }
        properties[LOCAL_ENTITY_ID_PROPERTY_NAME] = YouTrackDbPropertyTarget(
            YouTrackDbPropertyMapping(LOCAL_ENTITY_ID_PROPERTY_NAME, PropertyType.PRIMITIVE, EngineType.LONG),
            mandatory = true, notNull = true
        )
        YouTrackDbClassTarget(entity.type, properties)
    }
}
