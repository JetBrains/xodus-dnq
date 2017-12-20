/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package kotlinx.dnq.listener

import kotlinx.dnq.XdEntity

interface XdEntityListener<in XD : XdEntity> {
    fun addedSyncBeforeConstraints(added: XD) = Unit
    fun addedSyncAfterConstraints(added: XD) = addedSyncBeforeFlush(added)
    fun addedAsync(added: XD) = Unit
    fun addedSync(added: XD) = Unit

    fun updatedSyncBeforeConstraints(old: XD, current: XD) = Unit
    fun updatedSyncAfterConstraints(old: XD, current: XD) = updatedSyncBeforeFlush(old, current)
    fun updatedSync(old: XD, current: XD) = Unit
    fun updatedAsync(old: XD, current: XD) = Unit

    fun removedSyncBeforeConstraints(removed: XD) = Unit
    fun removedSyncAfterConstraints(removed: XD) = removedSyncBeforeFlush(removed)
    fun removedSync(removed: XD) = Unit
    fun removedAsync(removed: XD) = Unit

    @Deprecated("Use addedSyncAfterConstraints instead", ReplaceWith("addedSyncAfterConstraints"))
    fun addedSyncBeforeFlush(added: XD) = Unit

    @Deprecated("Use updatedSyncAfterConstraints instead", ReplaceWith("updatedSyncAfterConstraints"))
    fun updatedSyncBeforeFlush(old: XD, current: XD) = Unit

    @Deprecated("Use removedSyncAfterConstraints instead", ReplaceWith("removedSyncAfterConstraints"))
    fun removedSyncBeforeFlush(added: XD) = Unit
}
