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
     * Dual-mode index creation (XD-1283), plumbed from
     * [YTDBDatabaseParams.transactionalIndexCreation].
     *
     * **The default is `true`**: all index definitions of a schema pass are created inside ONE
     * transaction, so the index pass is atomic (and much faster). When `false`, indices are
     * created on YTDB's legacy non-transactional path (createIndex + fillIndex over committed
     * rows).
     *
     * **Transactional index creation requires EMPTY classes** on the current YouTrackDB version
     * (upstream YTDB-1064): creating an index over a class that already holds rows - or whose
     * subtypes hold rows - is rejected at commit. The failure recurs on every RESTART
     * (`applySchema` is idempotent: the index stays absent, the class stays populated) - but an
     * in-process retry does not surface it either, because `ModelMetaDataImpl` memoizes the
     * model before invoking `onPrepared`, so a caught exception leaves a running model with the
     * index silently missing. **A database that already contains data must therefore pin this
     * flag to `false`** until YTDB-1064 is lifted. That applies in particular to a schema upgrade
     * that adds an index over a populated class, and to the application's first `prepare()`
     * after a Xodus -> YouTrackDB migration (the migrator creates no indices, so every class is
     * populated by the time indices are built).
     *
     * The flag retires when YTDB-1064 is lifted.
     */
    val transactionalIndexCreation: Boolean get() = true

    /**
     * Database-wise read-only mode.
     * Always false by default.
     */
    var readOnly: Boolean

    // is it even needed?
    val isOpen: Boolean

    fun close()
}

