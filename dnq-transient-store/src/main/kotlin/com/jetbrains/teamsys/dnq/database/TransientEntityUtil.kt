/**
 * Copyright 2006 - 2019 JetBrains s.r.o.
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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.entitystore.PersistentStoreTransaction

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
    get() = threadSession
            ?: throw IllegalStateException("There is no transient transaction in current thread")

val PersistentEntityStore.currentTransactionOrThrow
    get() = currentTransaction as PersistentStoreTransaction?
            ?: throw IllegalStateException("There is no persistent transaction in current thread")

val TransientEntity.persistentClassInstance: BasePersistentClassImpl?
    get() = (store as? TransientEntityStoreImpl)?.getCachedPersistentClassInstance(type)
