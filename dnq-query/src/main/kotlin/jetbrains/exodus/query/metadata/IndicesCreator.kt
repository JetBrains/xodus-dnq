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
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction
import com.jetbrains.youtrackdb.internal.core.index.IndexException
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.edgeClassName
import jetbrains.exodus.entitystore.youtrackdb.getTargetLocalEntityIds
import jetbrains.exodus.entitystore.youtrackdb.setTargetLocalEntityIds
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Creates the indices inside the caller-provided active transaction. Callers using the temporary
 * index-mode preflight pass only owners proven empty; direct callers intentionally retain YTDB's
 * raw YTDB-1064 behavior.
 * Must be called with an active transaction on the session.
 */
internal fun DatabaseSessionEmbedded.applyIndices(indices: Map<String, Set<DeferredIndex>>) {
    check(isTxActive) {
        "Transactional index creation must run inside an active transaction - " +
            "outside one it would silently degrade to the unguarded legacy path"
    }
    IndicesCreator(indices).createIndices(this)
}

/**
 * Creates the indices on YTDB's legacy non-transactional path (XD-1283 temporary bridge for
 * populated owners): createIndex registers the index and fills it from the committed rows
 * (fillIndex), so populated classes are supported. Must be called with no active transaction on
 * the session.
 *
 * Failure semantics: a fillIndex failure (e.g. a genuine duplicate under a unique index)
 * leaves the index registered but empty - it is dropped and the failure is rethrown loudly,
 * see the catch in [IndicesCreator.createIndices].
 */
internal fun DatabaseSessionEmbedded.applyIndicesNonTx(indices: Map<String, Set<DeferredIndex>>) {
    check(!isTxActive) {
        "Non-transactional index creation must not run inside an active transaction"
    }
    IndicesCreator(indices, transactional = false).createIndices(this)
}

internal class IndicesCreator(
    private val indicesByOwnerVertexName: Map<String, Set<DeferredIndex>>,
    private val transactional: Boolean = true
) {
    private val logger = PaddedLogger.logger(log)

    fun createIndices(dbSession: DatabaseSessionEmbedded) {
        try {
            with(logger) {
                appendLine("applying indices to OrientDB")

                appendLine("creating indices if absent:")
                for ((ownerVertexName, indices) in indicesByOwnerVertexName) {
                    val dbClass =
                        dbSession.schema.getClass(ownerVertexName)
                            ?: throw IllegalStateException("$ownerVertexName not found")
                    appendLine("${dbClass.name}:")
                    withPadding {
                        for ((_, indexName, properties, unique) in indices) {
                            append(indexName)
                            if (!dbSession.schema.indexExists(indexName)) {
                                val indexType =
                                    if (unique) SchemaClass.INDEX_TYPE.UNIQUE else SchemaClass.INDEX_TYPE.NOTUNIQUE
                                if (transactional) {
                                    // createIndex runs in the caller-provided transaction (XD-1283).
                                    // In-tx index creation over classes with pre-existing committed
                                    // rows fails at commit until YTDB-1064 is lifted - accepted.
                                    try {
                                        dbClass.createIndex(indexName, indexType, *properties.toTypedArray())
                                        appendLine(", created")
                                    } catch (e: IndexException) {
                                        /*
                                         * Concurrent-creation race tolerance (XD-1283): another
                                         * session may commit the same index between the existence
                                         * check and createIndex (the loser's tx-local schema copy
                                         * is seeded after the winner's commit, so the re-check
                                         * sees the winner's index).
                                         */
                                        if (dbSession.schema.indexExists(indexName)) {
                                            appendLine(", already created (concurrently)")
                                        } else {
                                            throw e
                                        }
                                    }
                                } else {
                                    // Legacy non-tx path: createIndex registers the index and
                                    // fills it from the committed rows (fillIndex). This temporary
                                    // branch is removed once YTDB-1064 is lifted and in-tx index
                                    // builds over populated classes are supported upstream.
                                    try {
                                        dbClass.createIndex(indexName, indexType, *properties.toTypedArray())
                                        appendLine(", created")
                                    } catch (e: Throwable) {
                                        /*
                                         * AD-E1/AD-E2 (XD-1283): the legacy path REGISTERS the
                                         * index before filling it, so a fillIndex failure (e.g.
                                         * a genuine duplicate under a unique index) leaves the
                                         * index registered but EMPTY. Left in place, the
                                         * indexExists pre-check above would silently skip the
                                         * broken index on the next startup. Drop the poisoned
                                         * index and rethrow loudly. (If the failure came from a
                                         * concurrent creation of the same index, that index is
                                         * dropped too and this call fails - non-tx creation
                                         * runs at startup and on association-add, where such a
                                         * race is not a supported scenario.)
                                         */
                                        try {
                                            if (dbSession.schema.indexExists(indexName)) {
                                                dbSession.sharedContext.indexManager
                                                    .dropIndex(dbSession, indexName)
                                            }
                                        } catch (dropException: Throwable) {
                                            e.addSuppressed(dropException)
                                        }
                                        throw e
                                    }
                                }
                            } else {
                                appendLine(", already created")
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logger.flush()
            throw e
        } finally {
            logger.flush()
        }
    }
}

internal fun DatabaseSessionEmbedded.initializeComplementaryPropertiesForNewIndexedLinks(
    newIndexedLinks: Map<String, Set<String>>, // ClassName -> set of link names
    commitEvery: Int = 50
) {
    if (newIndexedLinks.isEmpty()) return

    var counter = 0
    withTx {
        for ((className, indexedLinks) in newIndexedLinks) {
            /*
             * Materialize the RIDs first (XD-1283): the batching below commits and re-begins
             * the transaction mid-loop, which invalidates a lazy vertex stream (and the
             * vertex handles it yields) bound to the already-committed transaction - writes
             * on such stale handles are lost. RIDs are plain values that survive transaction
             * boundaries; every vertex is loaded afresh in the transaction that is current
             * at its turn.
             */
            val rids = activeTransaction.query("select from $className").vertexStream()
                .map { (it as Vertex).identity }
                .toList()
            for (rid in rids) {
                val vertex = activeTransaction.loadVertex(rid)
                for (indexedLink in indexedLinks) {
                    val edgeClassName = edgeClassName(indexedLink)
                    val targetLocalEntityIds = vertex.getTargetLocalEntityIds(indexedLink)
                    for (target in vertex.getVertices(Direction.OUT, edgeClassName)) {
                        targetLocalEntityIds.add(target.identity)
                    }
                    vertex.setTargetLocalEntityIds(indexedLink, targetLocalEntityIds)
                    counter++
                }
                // commit at vertex boundaries only: a mid-vertex commit would leave the
                // freshly loaded handle stale for the vertex's remaining links
                if (counter >= commitEvery) {
                    counter = 0
                    activeTransaction.commit()
                    begin()
                }
            }
        }
    }
}

internal data class DeferredIndex(
    val ownerVertexName: String,
    val indexName: String,
    val properties: Set<String>,
    val unique: Boolean
) {
    constructor(ownerVertexName: String, properties: Set<String>, unique: Boolean) : this(
        ownerVertexName,
        indexName = "${ownerVertexName}_${properties.sorted().joinToString("_")}${if (unique) "_unique" else ""}".replace(' ', '_'),
        properties,
        unique = unique
    )
}

internal fun SchemaClass.makeDeferredIndexForEmbeddedSet(propertyName: String): DeferredIndex {
    return DeferredIndex(
        ownerVertexName = this.name,
        setOf(propertyName),
        unique = false
    )
}