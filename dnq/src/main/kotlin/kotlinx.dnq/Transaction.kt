package kotlinx.dnq

import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.QueryCancellingPolicy
import jetbrains.teamsys.dnq.runtime.txn._Txn


fun <T> TransientEntityStore.transactional(
    readonly: Boolean = false,
    queryCancellingPolicy: QueryCancellingPolicy? = null,
    isNew: Boolean = false,
    block: (TransientStoreSession) -> T
): T {
    val superSession = threadSession
    var superIsSuspended = false
    if (isNew || superSession != null && superSession.isReadonly) {
        suspendThreadSession()
        superIsSuspended = true
    }
    try {
        val newSession = beginSession(readonly, queryCancellingPolicy)
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
                    _Txn.doCommit(newSession)
                }
            }
        }
    } finally {
        if (superIsSuspended) {
            resumeSession(superSession)
            if (queryCancellingPolicy != null) {
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
