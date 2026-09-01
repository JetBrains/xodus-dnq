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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * The two index paths needed while YTDB-1064 rejects transactional index creation over populated
 * source collections. The transactional part must be applied before [nonTransactional], because a
 * non-transactional schema write does not engage YTDB's metadata write mutex.
 */
internal data class IndexCreationPlan(
    val transactional: Map<String, Set<DeferredIndex>>,
    val nonTransactional: Map<String, Set<DeferredIndex>>
)

/**
 * Plans index creation before the commit which would otherwise be rejected by YTDB-1064.
 *
 * This deliberately lives outside [IndicesCreator]: that class is also the low-level engine
 * contract canary and must continue to expose the raw transactional failure to its callers.
 * [createdClasses] is the set captured by the preceding schema pass; it is what makes the normal
 * fresh-install path free of collection-count reads even though DDL and index creation commit in
 * separate transactions.
 *
 * The predicate follows YTDB's own rejection order over the same polymorphic collection ids:
 * newly-created owner/subclass closures and provisional-only collections are empty by construction;
 * otherwise the O(1) approximate count rejects populated owners, and the exact count confirms the
 * zero case in the caller's active transaction. Any uncertainty is conservative and routes the
 * owner to the legacy non-transactional path. Callers that disable that fallback bypass this
 * predicate and receive all requested indices in the transactional bucket instead.
 */
internal fun DatabaseSessionEmbedded.planIndexCreation(
    result: SchemaApplicationResult,
    allowNonTransactionalIndexFallback: Boolean = true
): IndexCreationPlan =
    if (!allowNonTransactionalIndexFallback) {
        // Strict mode deliberately bypasses all preflight work. The caller asked for a purely
        // transactional attempt, so let YTDB report YTDB-1064 at commit for populated owners.
        IndexCreationPlan(result.indices, emptyMap())
    } else {
        planIndexCreation(result.indices, result.createdClasses)
    }

internal fun DatabaseSessionEmbedded.planIndexCreation(
    indices: Map<String, Set<DeferredIndex>>,
    createdClasses: Set<String>
): IndexCreationPlan {
    check(isTxActive) {
        "Index creation preflight must run inside the transaction that applies the transactional subset"
    }
    val pendingByOwner = HashMap<String, Set<DeferredIndex>>()
    for ((ownerName, ownerIndices) in indices) {
        val pending = try {
            ownerIndices.filterNot { schema.indexExists(it.indexName) }.toSet()
        } catch (e: Throwable) {
            // A failed existence check must not make us optimistic about the transactional path.
            log.debug(e) { "Could not check existing indices for $ownerName; using non-transactional creation" }
            ownerIndices
        }
        if (pending.isNotEmpty()) pendingByOwner[ownerName] = pending
    }

    val transactional = HashMap<String, Set<DeferredIndex>>()
    val nonTransactional = HashMap<String, Set<DeferredIndex>>()
    for ((ownerName, ownerIndices) in pendingByOwner) {
        val ownerClass = try {
            schema.getClass(ownerName)
        } catch (e: Throwable) {
            log.debug(e) { "Could not inspect $ownerName for index creation; using non-transactional creation" }
            null
        }

        val isEmpty = try {
            ownerClass != null && (
                ownerClass.isEmptyForTransactionalIndexCreation(createdClasses) ||
                    holdsNoCommittedRecords(ownerClass)
                )
        } catch (e: Throwable) {
            log.debug(e) { "Could not establish whether $ownerName is empty; using non-transactional creation" }
            false
        }
        if (isEmpty) {
            transactional[ownerName] = ownerIndices
        } else {
            nonTransactional[ownerName] = ownerIndices
        }
    }
    return IndexCreationPlan(transactional, nonTransactional)
}

private fun SchemaClass.isEmptyForTransactionalIndexCreation(createdClasses: Set<String>): Boolean {
    return name in createdClasses && getAllSubclasses().all { it.name in createdClasses }
}

/**
 * Checks exactly the committed collections covered by the class, matching YTDB-1064's source
 * collection predicate. This method is called from an active index transaction when the exact
 * fallback is needed.
 */
private fun DatabaseSessionEmbedded.holdsNoCommittedRecords(schemaClass: SchemaClass): Boolean {
    return try {
        val committedCollectionIds = schemaClass.polymorphicCollectionIds
            .filterNot(SchemaShared::isProvisionalCollectionId)
            .toIntArray()
        if (committedCollectionIds.isEmpty()) {
            true
        } else if (getApproximateCollectionElementsCount(committedCollectionIds) > 0) {
            false
        } else {
            countCollectionElements(committedCollectionIds, false) == 0L
        }
    } catch (e: Throwable) {
        log.debug(e) { "Could not establish whether ${schemaClass.name} is empty; using non-transactional creation" }
        false
    }
}
