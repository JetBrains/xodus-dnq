package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity

/**
 * Attach entity to current transient session if possible.
 */
fun TransientEntity.reattach(): TransientEntity {
    val s = store.threadSessionOrThrow
    return s.newLocalCopy(this)
}

fun Entity.reattachTransient(): TransientEntity {
    return (this as TransientEntity).reattach()
}

val TransientEntityStore.threadSessionOrThrow
    get() = threadSession ?: throw IllegalStateException("There's no current session to attach transient entity to.")
