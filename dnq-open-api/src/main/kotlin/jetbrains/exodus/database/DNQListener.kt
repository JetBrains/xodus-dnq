package jetbrains.exodus.database

interface DNQListener<in T : Any> {

    fun addedSyncBeforeConstraints(added: T)
    fun addedSync(added: T)
    fun addedAsync(added: T)

    fun updatedSyncBeforeConstraints(old: T, current: T)
    fun updatedSync(old: T, current: T)
    fun updatedAsync(old: T, current: T)

    fun removedSyncBeforeConstraints(removed: T)
    fun removedSync(removed: T)
    fun removedAsync(removed: T)

}