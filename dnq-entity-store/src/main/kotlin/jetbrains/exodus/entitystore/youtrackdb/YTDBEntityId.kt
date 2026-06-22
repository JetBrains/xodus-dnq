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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.entitystore.EntityId

interface YTDBEntityId : EntityId {
    fun asOId(): RID

    fun getTypeName(): String

    /** The resolved schema class name, or `null` if it isn't known. Unlike [getTypeName] this never
     *  returns a sentinel — callers that need a real class (e.g. to scope a query to `FROM <class>`)
     *  must skip scoping when it is `null`. */
    fun getTypeNameOrNull(): String?
}

/**
 * The entity's schema class name, for scoping a by-id query to `FROM <class>` instead of `FROM V`:
 * the class carried by the id, or — only if that is absent — the authoritative `typeId -> class`
 * lookup, which is always resolvable for a live type. Returns `null` only if even that fails (e.g.
 * the type was removed), in which case callers skip scoping. The [getType] lookup runs only on the
 * (rare) miss, so the common path stays allocation-free.
 */
fun YTDBEntityId.resolveTypeName(tx: YTDBStoreTransaction): String? =
    getTypeNameOrNull() ?: runCatching { tx.getType(typeId) }.getOrNull()

/** [resolveTypeName] resolving the transaction from [store]'s active transaction. */
fun YTDBEntityId.resolveTypeName(store: YTDBEntityStore): String? =
    getTypeNameOrNull() ?: runCatching { store.requireActiveTransaction().getType(typeId) }.getOrNull()