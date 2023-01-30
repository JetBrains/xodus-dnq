/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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
package jetbrains.exodus.database

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityStore
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.entitystore.QueryCancellingPolicy
import jetbrains.exodus.query.QueryEngine
import jetbrains.exodus.query.metadata.ModelMetaData

/**
 * Allows to suspend and resume session.
 */
interface TransientEntityStore : EntityStore, EntityStoreRefactorings {

    val persistentStore: PersistentEntityStore

    val threadSession: TransientStoreSession?

    var modelMetaData: ModelMetaData?

    val queryEngine: QueryEngine

    val isOpen: Boolean

    val changesMultiplexer: ITransientChangesMultiplexer?

    val invocationTransport: ListenerInvocationTransport?

    override fun beginReadonlyTransaction(): TransientStoreSession

    fun beginSession(): TransientStoreSession

    fun suspendThreadSession(): TransientStoreSession?

    /**
     * Resumes previously suspended session
     */
    fun resumeSession(session: TransientStoreSession?)

    fun addListener(listener: TransientStoreSessionListener)

    /**
     * Adds listener with a priority.
     * The higher priority the earlier listener will be visited by the TransientEntityStoreImpl.forAllListeners().
     */
    fun addListener(listener: TransientStoreSessionListener, priority: Int)

    fun removeListener(listener: TransientStoreSessionListener)

    fun getCachedEnumValue(className: String, propName: String): Entity?

    /**
     * Executes `block` in Xodus-DNQ transaction.
     *
     * Xodus-DNQ transactions are reenterable, i.e. this method may be invoked inside opened transaction.
     * Effect of such an invocation depends on the value of parameter `isNew`. If `isNew` is `false` then
     * no transaction is actually opened. If `isNew` is `false` then currently running transaction is suspended and
     * new transaction is opened.
     *
     * @receiver Xodus-DNQ entity store (database) which will be read or updated in the transaction.
     * @param T type of value returned by the executed code block.
     * @param readonly if `true` database update operations are not allowed in the transaction. Is `false` by default.
     * @param isNew if `false` and the method is invoked in the context of already opened transaction then no new
     *        transaction is opened. Is `false` by default.
     * @param block code to execute in the transaction.
     *
     */
    fun <T> transactional(
            readonly: Boolean = false,
            queryCancellingPolicy: QueryCancellingPolicy? = null,
            isNew: Boolean = false,
            block: (TransientStoreSession) -> T
    ): T
}
