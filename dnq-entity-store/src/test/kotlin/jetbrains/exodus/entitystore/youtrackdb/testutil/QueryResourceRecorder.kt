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
package jetbrains.exodus.entitystore.youtrackdb.testutil

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.query.RegisteredQuery
import com.jetbrains.youtrackdb.internal.core.tx.Transaction
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.IdentityHashMap

/** Retains real query resources so weak registry values cannot hide leaks during assertions. */
class QueryResourceRecorder : AutoCloseable {
    private data class Registration(
        val session: DatabaseSessionEmbedded,
        val id: String,
        val resource: RegisteredQuery,
        val transaction: Transaction
    )

    private val sessions = IdentityHashMap<DatabaseSessionEmbedded, Boolean>()
    private val registrations = ArrayList<Registration>()
    private var closed = false

    val resources: List<RegisteredQuery> get() = registrations.map { it.resource }

    /** Attachment is idempotent by session identity. Scopes on one session must not overlap. */
    fun attach(session: DatabaseSessionEmbedded) {
        check(!closed) { "Recorder is closed" }
        if (sessions.containsKey(session)) return
        try {
            mockkObject(session)
            every { session.queryStarted(any(), any()) } answers {
                registrations.add(Registration(session, firstArg(), secondArg(), session.activeTransaction))
                callOriginal()
            }
            sessions[session] = true
        } catch (failure: Throwable) {
            try {
                unmockkObject(session)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup
                else if (failure !== cleanup) failure!!.addSuppressed(cleanup)
            }
        }
        sessions.keys.forEach { session -> attempt { unmockkObject(session) } }
        registrations.forEach { (session, id, resource, transaction) ->
            attempt {
                // Teardown owns resources of inactive sessions or finished transactions.
                if (session.isActiveOnCurrentThread && !session.isClosed && transaction.isActive &&
                    session.activeTransaction === transaction && session.activeQueries[id] === resource
                ) {
                    resource.close()
                }
            }
        }
        registrations.clear()
        sessions.clear()
        failure?.let { throw it }
    }
}

/** The translator override is database-scoped, so restore it outside the recorder's cleanup. */
fun <T> withNativeQueryRecorder(
    session: DatabaseSessionEmbedded,
    block: (QueryResourceRecorder) -> T
): T {
    val flag = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED
    val configuration = requireNotNull(session.configuration)
    val previous = configuration.setValue(flag, false)
    var failure: Throwable? = null
    try {
        return QueryResourceRecorder().use { recorder ->
            recorder.attach(session)
            block(recorder)
        }
    } catch (primary: Throwable) {
        failure = primary
        throw primary
    } finally {
        try {
            configuration.setValue(flag, previous)
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
    }
}
