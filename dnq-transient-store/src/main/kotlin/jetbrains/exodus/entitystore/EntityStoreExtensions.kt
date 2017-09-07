/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession

object EntityStoreExtensions {
    @JvmStatic fun run(store: TransientEntityStore, action: Runnable) = store.run(action)
}

fun TransientEntityStore.run(action: Runnable) {
    val superSession = threadSession
    var superIsSuspended = false
    if (superSession != null && superSession.isReadonly) {
        suspendThreadSession()
        superIsSuspended = true
    }
    try {
        val newSession = beginSession(false)
        var wasEx = true
        try {
            action.run()
            wasEx = false
        } finally {
            if ((superSession == null || superIsSuspended) && newSession.isOpened()) {
                if (wasEx) {
                    newSession.abort()
                } else {
                    doCommit(newSession)
                }
            }
        }
    } finally {
        if (superIsSuspended && superSession != null) {
            resumeTransientSession(superSession)
        }
    }
}

fun TransientEntityStore.runReadonly(action: Runnable) {
    val superSession = suspendThreadSession()
    try {
        val newSession = beginSession(true)
        try {
            action.run()
        } finally {
            newSession.abort()
        }
    } finally {
        if (superSession != null) {
            resumeTransientSession(superSession)
        }
    }
}

fun TransientEntityStore.beginSession(readonly: Boolean): TransientStoreSession {
    var session: TransientStoreSession? = null
    try {
        val result = if (readonly)
            beginReadonlyTransaction()
        else
            beginSession()
        computePolicy(result)
        session = result
        return result
    } catch (ex: RuntimeException) {
        if (session != null) {
            try {
                session.abort()
            } catch (e: RuntimeException) {
                // ignore
            }
        }
        throw ex
    }
}

fun TransientEntityStore.resumeTransientSession(session: TransientStoreSession) {
    resumeSession(session)
    computePolicy(session)
}

fun computePolicy(session: TransientStoreSession) {
    // TODO
}

fun doCommit(session: TransientStoreSession) {
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
