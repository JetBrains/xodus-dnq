/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
package jetbrains.exodus.migrate

import jetbrains.exodus.entitystore.youtrackdb.YTDBPersistentEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.shaded.exodus.entitystore.PersistentEntityStore
import jetbrains.shaded.exodus.entitystore.StoreTransaction

/** Releases owned resources in reverse acquisition order and keeps every failure observable. */
internal class MigrationResources {
    private val releases = ArrayDeque<() -> Unit>()

    fun <T> own(resource: T, release: (T) -> Unit): T {
        releases.addFirst { release(resource) }
        return resource
    }

    fun release(primary: Throwable?): Throwable? {
        var failure = primary
        while (releases.isNotEmpty()) {
            try {
                releases.removeFirst().invoke()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure
                else if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            }
        }
        return failure
    }
}

/** A no-op returns false. Only successful work followed by successful release permits cleanup. */
internal fun runMigrationLifecycle(
    closeTarget: (() -> Unit)?,
    cleanup: () -> Unit,
    work: MigrationResources.() -> Boolean
) {
    val resources = MigrationResources()
    if (closeTarget != null) resources.own(closeTarget) { it() }
    var completed = false
    var failure: Throwable? = null
    try {
        completed = resources.work()
    } catch (primary: Throwable) {
        failure = primary
    }
    resources.release(failure)?.let { throw it }
    if (completed) cleanup()
}

fun <R> PersistentEntityStore.withReadonlyTx(block: (StoreTransaction) -> R): R {
    val tx = this.beginReadonlyTransaction()
    try {
        val result = block(tx)
        tx.abort()
        return result
    } catch (e: Throwable) {
        if (!tx.isFinished) {
            tx.abort()
        }
        throw e
    }
}

fun <R> YTDBPersistentEntityStore.withReadonlyTx(block: (YTDBStoreTransaction) -> R): R {
    val tx = this.beginReadonlyTransaction()
    try {
        val result = block(tx)
        tx.abort()
        return result
    } catch (e: Throwable) {
        if (!tx.isFinished) {
            tx.abort()
        }
        throw e
    }
}