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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph

interface YTDBDatabaseProvider {
    val databaseLocation: String

    /**
     * The single shared [YTDBGraph] instance backed by the session pool.
     * The graph uses thread-local state internally, so each thread gets its own session.
     *
     * Must NOT be closed — [com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded.close]
     * would close the shared pool.
     */
    val graph: YTDBGraph

    fun <R> withSession(block: (DatabaseSessionEmbedded) -> R): R

    /**
     * Whether the index-mode preflight may use the legacy non-transactional path for populated or
     * uncertain index owners. When `false`, preflight is bypassed and all indices are attempted
     * transactionally.
     */
    val allowNonTransactionalIndexFallback: Boolean get() = true

    /**
     * Whether schema initialization uses batched class-id sequence acquisition, as configured by
     * [YTDBDatabaseParams.useBatchedSequenceAcquisition]. This is intended for tests and
     * benchmarks only; the default preserves the historical per-class acquisition behavior.
     */
    val useBatchedSequenceAcquisition: Boolean get() = false

    /**
     * Whether `prepare()` creates the automatic index of every auto-indexed simple property
     * (EXPERIMENTAL, JT-95771/XD-1283) - plumbed from
     * [YTDBDatabaseParams.autoIndexSimpleProperties]. `false` trades simple-property lookups for
     * a scan and is meant for test/benchmark databases only.
     */
    val autoIndexSimpleProperties: Boolean get() = true

    /**
     * Whether `prepare()` skips schema application entirely (EXPERIMENTAL, JT-95771/XD-1283) -
     * plumbed from [YTDBDatabaseParams.skipSchemaApplication]. The caller must have established
     * that the database's schema already matches the model.
     */
    val skipSchemaApplication: Boolean get() = false

    /**
     * Database-wise read-only mode.
     * Always false by default.
     */
    var readOnly: Boolean

    // is it even needed?
    val isOpen: Boolean

    fun close()
}

