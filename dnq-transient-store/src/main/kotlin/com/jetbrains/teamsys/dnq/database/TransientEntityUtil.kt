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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.entitystore.orientdb.OStoreTransaction

/**
 * Attach entity to current transient session if possible.
 */
fun TransientEntity.reattach(session: TransientStoreSession? = null): TransientEntity {
    if (isReadonly || isWrapper) return this
    val s = session ?: store.threadSessionOrThrow
    return s.newLocalCopy(this)
}

val Entity.threadSessionOrThrow: TransientStoreSession get() = (this as TransientEntity).store.threadSessionOrThrow

fun Entity.reattachTransient(session: TransientStoreSession? = null): TransientEntity {
    return (this as TransientEntity).reattach(session)
}

val TransientEntityStore.threadSessionOrThrow
    get() = threadSession
        ?: throw IllegalStateException("There is no transient transaction in current thread")

val PersistentEntityStore.currentTransactionOrThrow
    get() = currentTransaction as? OStoreTransaction ?: throw IllegalStateException("There is no persistent transaction in current thread")

val TransientEntity.lifecycle: EntityLifecycle?
    get() = (store as? TransientEntityStoreImpl)?.entityLifecycle

fun TransientEntity.getLinkEx(linkName: String, session: TransientStoreSession) =
    if (this is TransientEntityImpl) getLink(linkName, session) else getLink(linkName)
