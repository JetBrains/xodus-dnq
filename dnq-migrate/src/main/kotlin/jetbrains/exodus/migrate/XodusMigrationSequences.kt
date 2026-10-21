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
package jetbrains.exodus.migrate

import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.CLASS_ID_SEQUENCE_NAME
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import jetbrains.shaded.exodus.bindings.LongBinding
import jetbrains.shaded.exodus.entitystore.PersistentEntityStore
import jetbrains.shaded.exodus.entitystore.PersistentStoreTransaction
import jetbrains.shaded.exodus.env.StoreConfig
import java.util.Locale

/** Copies the pinned engine's comparison rule. Dependency upgrades require compatibility checks. */
internal fun normalizedMigrationSequenceName(name: String): String = name.uppercase(Locale.ROOT)

/** Saved rows establish presence. Getter-only handles and unsaved cache values are not migration input. */
internal fun migrationSequenceState(source: PersistentEntityStore): Map<String, Long> = source.withReadonlyTx { tx ->
    val environment = source.environment
    val rawTx = (tx as PersistentStoreTransaction).environmentTransaction
    val saved = linkedMapOf<String, Long>()
    if (environment.storeExists("sequences", rawTx)) {
        environment.openStore("sequences", StoreConfig.USE_EXISTING, rawTx).openCursor(rawTx).use { cursor ->
            while (cursor.next) {
                val key = cursor.key
                val name = String(key.bytesUnsafe, 0, key.length, Charsets.UTF_8)
                saved[name] = LongBinding.compressedEntryToLong(cursor.value)
            }
        }
    }
    val prefix = source.name
    val localPrefix = "$prefix.entities.sequences#"
    val irrelevant = setOf(
        "$prefix.property.types.sequence", "$prefix.link.types.sequence",
        "$prefix.property.custom.types.sequence", "blob.handles.sequence"
    )
    val planned = linkedMapOf<String, Long>()
    val normalized = HashMap<String, String>()
    fun add(name: String, value: Long) {
        val nativeName = normalizedMigrationSequenceName(name)
        val previous = normalized.putIfAbsent(nativeName, name)
        require(previous == null) { "Sequence destination collision: $previous and $name -> $nativeName" }
        planned[name] = value
    }
    add(CLASS_ID_SEQUENCE_NAME, saved["$prefix.entity.types.sequence"] ?: -1L)
    for (type in tx.entityTypes) {
        add(localEntityIdSequenceName(type), saved["$localPrefix${source.getEntityTypeId(type)}"] ?: -1L)
    }
    for ((name, value) in saved) {
        val suffix = name.removePrefix(localPrefix)
        val localAllocator = name.startsWith(localPrefix) &&
            suffix.toIntOrNull()?.let { it >= 0 && it.toString() == suffix } == true
        if (name == "$prefix.entity.types.sequence" || name in irrelevant || localAllocator) continue
        add(name, value)
    }
    planned
}
