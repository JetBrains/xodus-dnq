/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
import jetbrains.exodus.entitystore.orientdb.OStoreTransaction

internal object TransientEntityStoreExt {
    fun <T> transactional(
        store: TransientEntityStore,
        queryCancellingPolicy: QueryCancellingPolicy? = null,
        isNew: Boolean,
        block: (TransientStoreSession) -> T
    ): T {
        val currentSession = store.threadSession
        var currentPersistentSession: OStoreTransaction? = null
        var sessionSuspended = false

        if (currentSession != null) {
            if (isNew) {
                currentPersistentSession = currentSession.transactionInternal as OStoreTransaction
                currentPersistentSession.deactivateOnCurrentThread()
                store.suspendThreadSession()
                sessionSuspended = true
            } else {
                return block(currentSession)
            }
        }

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
        } finally {
            if (sessionSuspended) {
                store.resumeSession(currentSession)
                currentPersistentSession?.activateOnCurrentThread()
            }
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
        } finally {
            if (wasEx && session.isOpened) {
                session.abort()
            }
        }
    }
}
