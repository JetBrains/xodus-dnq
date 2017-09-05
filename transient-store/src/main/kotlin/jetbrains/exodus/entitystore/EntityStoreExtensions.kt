package jetbrains.exodus.entitystore

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession

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
