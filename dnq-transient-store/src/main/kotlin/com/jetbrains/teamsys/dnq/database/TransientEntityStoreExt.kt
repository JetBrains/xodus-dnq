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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.QueryCancellingPolicy
import mu.KLogging

internal object TransientEntityStoreExt : KLogging() {
    fun <T> transactional(
        store: TransientEntityStore,
        isNew: Boolean = false,
        queryCancellingPolicy: QueryCancellingPolicy? = null,
        block: (TransientStoreSession) -> T
    ): T {
        val currentSession = store.threadSession
        return when {
            currentSession == null -> startNewAndRun(store, queryCancellingPolicy, block)
            isNew -> suspendAndRun(store, queryCancellingPolicy, block)
            else -> block(currentSession)
        }
    }

    /**
     * Runs [block] in a new independent transaction while an outer transaction is active on the current thread.
     *
     * The outer DNQ session is suspended via [TransientEntityStore.withSuspendedSession]; the
     * persistent-store transaction and active graph are suspended/restored by
     * [YTDBPersistentEntityStore.withSuspendedTransaction]. The inner transaction gets its own
     * [YTDBGraph][com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph] instance so that
     * commit/abort/revert inside [block] cannot affect the outer transaction.
     */
    private fun <T> suspendAndRun(
        store: TransientEntityStore,
        queryCancellingPolicy: QueryCancellingPolicy?,
        block: (TransientStoreSession) -> T
    ): T {
        return store.withSuspendedSession {
            store.persistentStore.withSuspendedTransaction {
                startNewAndRun(store, queryCancellingPolicy, block)
            }
        }
    }

    /**
     * Opens a new DNQ session (which starts a new persistent transaction),
     * runs [block], and commits on success or aborts on exception.
     */
    private fun <T> startNewAndRun(
        store: TransientEntityStore,
        queryCancellingPolicy: QueryCancellingPolicy?,
        block: (TransientStoreSession) -> T
    ): T {
        try {
            val newSession = store.beginSession(queryCancellingPolicy)
            var wasEx = true
            try {
                val result = block(newSession)
                wasEx = false
                return result
            } finally {
                if (newSession.isOpened) {
                    if (wasEx) {
                        newSession.abort()
                    } else {
                        doCommit(newSession)
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private fun TransientEntityStore.beginSession(
        queryCancellingPolicy: QueryCancellingPolicy?
    ): TransientStoreSession {
        val transaction = this.beginSession()
        return try {
            // Exception could be thrown due to race condition in inited ServiceLocator
            if (queryCancellingPolicy != null) {
                transaction.queryCancellingPolicy = queryCancellingPolicy
            }
            transaction
        } catch (ex: RuntimeException) {
            try {
                transaction.abort()
            } catch (e: RuntimeException) {
                // ignore
            }
            throw ex
        }
    }

    private fun doCommit(session: TransientStoreSession) {
        var wasEx = true
        try {
            session.commit()
            wasEx = false
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            if (wasEx && session.isOpened) {
                try {
                    session.abort()
                } catch (err: Exception) {
                    // we don't want to miss the original error
                    logger.error("Error while aborting uncommited session: ${err.message}", err)
                }
            }
        }
    }
}
