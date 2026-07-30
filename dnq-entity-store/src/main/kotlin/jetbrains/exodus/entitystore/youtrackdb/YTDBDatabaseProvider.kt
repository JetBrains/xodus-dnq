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
     * [YTDBDatabaseParams.transactionalIndexCreation]. When false (the default), indices are
     * created on YTDB's legacy non-transactional path (createIndex + fillIndex over committed
     * rows). When true, index creation runs inside explicit transactions - rejected at commit
     * for populated classes until YTDB-1064 is lifted. The default flips to true (and the
     * flag retires) when YTDB-1064 is lifted.
     */
    val transactionalIndexCreation: Boolean get() = false

    /**
     * Database-wise read-only mode.
     * Always false by default.
     */
    var readOnly: Boolean

    // is it even needed?
    val isOpen: Boolean

    fun close()
}

