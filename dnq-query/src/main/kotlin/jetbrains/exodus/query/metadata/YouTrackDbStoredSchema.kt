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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty
import java.util.Collections
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType

internal enum class StoredSchemaKind { APPLICATION, INFRASTRUCTURE, UNRESOLVED }

internal data class StoredSchemaClassification(val kind: StoredSchemaKind, val reason: String)

internal data class YouTrackDbStoredProperty(
    val name: String,
    val owner: String,
    val type: EngineType,
    val linkedType: EngineType?,
    val linkedClass: String?,
    val mandatory: Boolean,
    val notNull: Boolean,
    val classification: StoredSchemaClassification
)

internal data class YouTrackDbStoredClass(
    val name: String,
    val superClasses: List<String>,
    val ancestors: List<String>,
    val declaredProperties: Map<String, YouTrackDbStoredProperty>,
    val effectiveProperties: Map<String, YouTrackDbStoredProperty>,
    val classification: StoredSchemaClassification
)

internal data class YouTrackDbStoredSchema(val classes: Map<String, YouTrackDbStoredClass>)

private fun <K, V> Map<K, V>.frozen(): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(this))
private fun <T> Collection<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))

/** No model input or preparation. Snapshot acquisition and every read failure reach the caller. */
internal fun DatabaseSessionEmbedded.youTrackDbStoredSchema(): YouTrackDbStoredSchema =
    readYouTrackDbStoredSchema { checkNotNull(metadata.immutableSchemaSnapshot) { "No stored schema snapshot" } }

/** The supplier also permits failure tests at the snapshot boundary without a database mock. */
internal fun readYouTrackDbStoredSchema(snapshot: () -> Schema): YouTrackDbStoredSchema {
    val schema = snapshot()
    fun classification(kind: StoredSchemaKind, reason: String) = StoredSchemaClassification(kind, reason)
    fun property(p: SchemaProperty) = YouTrackDbStoredProperty(
        p.name, p.ownerClass.name, p.type, p.linkedType, p.linkedClass?.name, p.isMandatory, p.isNotNull,
        classification(StoredSchemaKind.APPLICATION, "Stored application property")
    )
    // Enumerate names, then use engine lookup to preserve child shadowing and parent precedence.
    val raw = schema.classes.associate { c ->
        c.name to YouTrackDbStoredClass(
            c.name, c.superClassesNames.frozen(), c.allSuperClasses.map { it.name }.frozen(),
            c.declaredProperties.associate { it.name to property(it) }.frozen(),
            c.propertiesMap.keys.associateWith { property(checkNotNull(c.getProperty(it))) }.frozen(),
            classification(StoredSchemaKind.APPLICATION, "Stored application class")
        )
    }
    fun YouTrackDbStoredClass.isEdge() = name == "E" || "E" in ancestors
    fun YouTrackDbStoredClass.isVertex() = name == "V" || "V" in ancestors
    val engineClasses = setOf("O", "V", "E", "OIdentity", "OUser", "ORole",
        "OFunction", "OSchedule", "OSequence", "OSecurityPolicy")
    val suffix = "_targetEntityId"
    fun edgeName(rawLink: String) = rawLink.replace('/', '_') + "_link"
    val rawLinks = raw.values.flatMap { it.declaredProperties.values }
        .filter { it.type == EngineType.LINKBAG && it.name.endsWith(suffix) }
        .map { it.name.removeSuffix(suffix) }.distinct().groupBy { edgeName(it) }

    fun classify(c: YouTrackDbStoredClass, p: YouTrackDbStoredProperty): StoredSchemaClassification {
        if (c.name in engineClasses) {
            return classification(StoredSchemaKind.INFRASTRUCTURE, "Property of exact engine base class ${c.name}")
        }
        if (c.isEdge() && p.name in setOf("in", "out") && p.type == EngineType.LINK) {
            return classification(StoredSchemaKind.INFRASTRUCTURE, "Stored edge endpoint LINK")
        }
        val adjacency = p.name.startsWith("out_") || p.name.startsWith("in_")
        val indexed = p.name.endsWith(suffix)
        if (!adjacency && !indexed) return p.classification
        if (p.type != EngineType.LINKBAG) {
            return classification(StoredSchemaKind.APPLICATION, "Helper-like name with non-LINKBAG storage")
        }
        // Raw link names may themselves begin with in_ or out_. Do not choose by prefix alone.
        val candidates = buildList {
            if (adjacency) {
                val edge = p.name.substringAfter('_')
                // The engine uses bare out_/in_ for E, not out_E/in_E.
                if (edge != "E") add(edge.ifEmpty { "E" } to false)
            }
            if (indexed) add(edgeName(p.name.removeSuffix(suffix)) to true)
        }.filter { (edge, _) -> raw[edge]?.isEdge() == true }
        if (!c.isVertex() || candidates.isEmpty()) {
            return classification(StoredSchemaKind.UNRESOLVED, "LINKBAG candidate lacks vertex owner or stored edge")
        }
        if (candidates.size != 1) {
            return classification(StoredSchemaKind.UNRESOLVED, "Ambiguous adjacency and indexed-link structures")
        }
        val (edge, isIndexed) = candidates.single()
        if (p.linkedType != null || p.linkedClass != null && (isIndexed || p.linkedClass != edge)) {
            return classification(StoredSchemaKind.UNRESOLVED, "Conflicting linked metadata for helper [$edge]")
        }
        if (isIndexed && rawLinks.getValue(edge).size > 1) {
            return classification(StoredSchemaKind.UNRESOLVED, "Ambiguous raw link names normalize to [$edge]")
        }
        return classification(StoredSchemaKind.INFRASTRUCTURE,
            if (isIndexed) "Indexed-link LINKBAG for stored edge [$edge]"
            else "Vertex adjacency LINKBAG for stored edge [$edge]")
    }
    val declared = raw.mapValues { (_, c) ->
        c.declaredProperties.mapValues { (_, p) -> p.copy(classification = classify(c, p)) }.frozen()
    }
    return YouTrackDbStoredSchema(raw.mapValues { (_, c) ->
        val kind = when {
            c.name in engineClasses -> classification(StoredSchemaKind.INFRASTRUCTURE, "Exact engine base class")
            c.isEdge() -> classification(StoredSchemaKind.INFRASTRUCTURE, "Stored edge ancestry")
            else -> c.classification
        }
        c.copy(
            declaredProperties = declared.getValue(c.name),
            effectiveProperties = c.effectiveProperties.mapValues { (_, p) ->
                // Preserve the actual declaring owner, including unrelated same-name properties.
                declared.getValue(p.owner).getValue(p.name)
            }.frozen(),
            classification = kind
        )
    }.frozen())
}
