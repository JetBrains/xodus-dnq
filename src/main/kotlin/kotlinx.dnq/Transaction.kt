package kotlinx.dnq

import jetbrains.teamsys.dnq.runtime.txn._Txn
import jetbrains.teamsys.dnq.runtime.util.DnqUtils


fun <T> transactional(block: () -> T): T {
    val superSession = DnqUtils.getCurrentTransientSession()
    var superIsSuspended = false
    if (superSession != null && superSession.isReadonly) {
        DnqUtils.suspendCurrentTransientSession()
        superIsSuspended = true
    }
    try {
        val newSession = DnqUtils.beginTransientSession()
        var wasEx = true
        try {
            val result = block.invoke()
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
            DnqUtils.resumeTransientSession(superSession)
        }
    }
}

fun flush() {
    DnqUtils.getCurrentTransientSession().flush()
}
