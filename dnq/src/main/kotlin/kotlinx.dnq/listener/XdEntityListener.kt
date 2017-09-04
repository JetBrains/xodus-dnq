package kotlinx.dnq.listener

import kotlinx.dnq.XdEntity

interface XdEntityListener<in XD : XdEntity> {
    fun addedSyncBeforeConstraints(added: XD) = Unit
    fun addedSyncBeforeFlush(added: XD) = Unit
    fun addedAsync(added: XD) = Unit
    fun addedSync(added: XD) = Unit

    fun updatedSyncBeforeConstraints(old: XD, current: XD) = Unit
    fun updatedSyncBeforeFlush(old: XD, current: XD) = Unit
    fun updatedSync(old: XD, current: XD) = Unit
    fun updatedAsync(old: XD, current: XD) = Unit

    fun removedSyncBeforeConstraints(removed: XD) = Unit
    fun removedSyncBeforeFlush(removed: XD) = Unit
    fun removedSync(removed: XD) = Unit
    fun removedAsync(removed: XD) = Unit
}