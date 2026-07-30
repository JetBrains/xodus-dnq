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
package jetbrains.exodus.entitystore.youtrackdb

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded

/**
 * Begins a transaction on this session, runs [block] and commits the session's transaction.
 *
 * Contract: the block may legally commit and re-begin transactions internally (batched
 * writes), which makes the handle returned by the initial `begin()` stale - therefore the
 * final commit resolves the session's *live* active transaction instead. If the block
 * returns having committed everything itself (no transaction left open), there is nothing
 * left to commit and withTx returns normally: an early commit is already effective, so
 * failing the success path over it would turn a benign pattern into an opaque
 * DatabaseException from the activeTransaction getter.
 *
 * On failure the live transaction (if any) is rolled back; a rollback failure is attached
 * to the original exception as suppressed instead of masking it.
 */
fun <R> DatabaseSessionEmbedded.withTx(block: (DatabaseSessionEmbedded) -> R): R {
    begin()
    try {
        val result = block(this)
        activeTransactionOrNull?.commit()
        return result
    } catch (e: Throwable) {
        /*
         * After a failed commit() YTDB has already rolled the transaction back internally
         * (status ROLLED_BACK); calling rollback() on it throws a TransactionException that
         * would mask the root cause. FrontendTransaction.isActive() is true for BEGUN,
         * COMMITTING and ROLLBACKING - a ROLLBACKING transaction must still be rolled back,
         * a ROLLED_BACK one must not. getActiveTransactionOrNull() returns the transaction
         * only while it is active, which is exactly the guard we need.
         *
         * We also deliberately resolve the session's *current* transaction instead of keeping
         * the handle returned by begin(): the block may legally commit and re-begin
         * transactions (batched writes), which makes the original handle stale.
         */
        try {
            activeTransactionOrNull?.rollback()
        } catch (rollbackException: Throwable) {
            e.addSuppressed(rollbackException)
        }
        throw e
    }
}
