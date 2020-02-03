/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

internal object TransientEntityStoreExt {
    fun <T> transactional(
            store: TransientEntityStore,
            readonly: Boolean = false,
            queryCancellingPolicy: QueryCancellingPolicy? = null,
            isNew: Boolean = false,
            block: (TransientStoreSession) -> T
    ): T {
        val superSession = store.threadSession
        val superIsSuspended: Boolean
        if (isNew) {
            store.suspendThreadSession()
            superIsSuspended = true
        } else {
            if (superSession == null) {
                superIsSuspended = false
            } else {
                if (superSession.isReadonly != readonly) {
                    store.suspendThreadSession()
                    superIsSuspended = true
                } else {
                    return block(superSession)
                }
            }
        }
        try {
            val newSession = store.beginSession(readonly, queryCancellingPolicy)
            var wasEx = true
            try {
                val result = block(newSession)
                wasEx = false
                return result
            } finally {
                if ((superSession == null || superIsSuspended) && newSession.isOpened) {
                    if (wasEx) {
                        newSession.abort()
                    } else {
                        doCommit(newSession)
                    }
                }
            }
        } finally {
            if (superIsSuspended) {
                store.resumeSession(superSession)
                if (queryCancellingPolicy != null && superSession != null) {
                    superSession.queryCancellingPolicy = queryCancellingPolicy
                }
            }
        }
    }

    private fun TransientEntityStore.beginSession(readonly: Boolean, queryCancellingPolicy: QueryCancellingPolicy?): TransientStoreSession {
        val transaction = if (readonly) {
            this.beginReadonlyTransaction()
        } else {
            this.beginSession()
        }
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
