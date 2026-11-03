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

internal data class YouTrackDbPropertyMapping(
    val name: String,
    val kind: PropertyType,
    val type: EngineType,
    val linkedType: EngineType? = null
)

/** Kind takes precedence over the Java metadata implementation and primitive type name. */
internal fun PropertyMetaData.toYouTrackDbProperty(): YouTrackDbPropertyMapping {
    val type = when (this.type) {
        PropertyType.TEXT, PropertyType.BLOB -> EngineType.LINK
        PropertyType.PRIMITIVE -> {
            require(this is SimplePropertyMetaDataImpl) {
                "$name is a primitive property but it is not an instance of SimplePropertyMetaDataImpl. Happy fixing!"
            }
            val primitive = primitiveTypeName ?: throw IllegalArgumentException("primitiveTypeName is null")
            if (primitive.lowercase() == "set") {
                val element = typeParameterNames?.firstOrNull()
                    ?: throw IllegalStateException("$name is Set but does not contain information about the type parameter")
                return YouTrackDbPropertyMapping(name, this.type, EngineType.EMBEDDEDSET, engineScalarType(element))
            }
            engineScalarType(primitive)
        }
    }
    return YouTrackDbPropertyMapping(name, this.type, type)
}

private fun engineScalarType(name: String): EngineType = when (name.lowercase()) {
    "boolean" -> EngineType.BOOLEAN
    "string" -> EngineType.STRING
    "byte" -> EngineType.BYTE
    "short" -> EngineType.SHORT
    "int", "integer" -> EngineType.INTEGER
    "long", "datetime" -> EngineType.LONG
    "float" -> EngineType.FLOAT
    "double" -> EngineType.DOUBLE
    else -> throw IllegalArgumentException("$name is not supported. Feel free to support it.")
}
