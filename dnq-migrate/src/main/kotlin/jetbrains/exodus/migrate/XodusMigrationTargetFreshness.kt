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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded

internal class XodusMigrationFreshnessRefusal(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Shared post-startup check used by both migration entries before migration-owned preparation.
 * Exclusive access and human validation of standard-class contents and security remain prerequisites.
 * Passing does not prove record emptiness, physical storage state, or provenance.
 */
internal object XodusMigrationTargetFreshness {
    private val permittedClasses = setOf(
        "OIdentity", "OSecurityPolicy", "ORole", "OUser", "OFunction", "OSchedule", "OSequence", "O", "V", "E"
    )

    fun inspect(session: DatabaseSessionEmbedded) {
        try {
            // beginReadOnly joins an active transaction on the pinned engine. Never join caller work.
            if (session.isTxActive) refuse("Target inspection requires no active caller transaction")
            try {
                session.beginReadOnly()
                for (clazz in session.schema.classes) {
                    val name = clazz.name
                    // Exact names only. Empty classes and descendants need their own permitted name.
                    if (name !in permittedClasses) refuse("Target contains nonstandard class $name")
                }
                val sequences = session.metadata.sequenceLibrary
                // sequenceNames alone may use a stale shared cache. Loading joins this owned scope.
                sequences.load()
                if (sequences.sequenceNames.isNotEmpty()) refuse("Target sequence library is not empty")
            } finally {
                // No commit or session/provider close.
                session.rollback()
            }
        } catch (e: XodusMigrationFreshnessRefusal) {
            throw e
        } catch (e: Exception) {
            throw XodusMigrationFreshnessRefusal("Cannot reliably observe target class names or sequence view", e)
        }
    }

    private fun refuse(message: String): Nothing = throw XodusMigrationFreshnessRefusal(message)
}
