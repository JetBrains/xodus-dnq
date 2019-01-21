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
package jetbrains.exodus.database

import jetbrains.exodus.entitystore.Entity

interface IEntityListener<T : Entity> {
    fun addedAsync(added: T)
    fun addedSync(added: T)
    fun addedSyncAfterConstraints(added: T)
    fun addedSyncBeforeConstraints(added: T)
    fun updatedAsync(old: T, current: T)
    fun updatedSync(old: T, current: T)
    fun updatedSyncAfterConstraints(old: T, current: T)
    fun updatedSyncBeforeConstraints(old: T, current: T)
    fun removedAsync(removed: T)
    fun removedSync(removed: T)
    fun removedSyncAfterConstraints(added: T)
    fun removedSyncBeforeConstraints(removed: T)

    @Deprecated("Implement addedSyncAfterConstraints() instead")
    fun addedSyncBeforeFlush(added: T)

    @Deprecated("Implement updatedSyncAfterConstraints() instead")
    fun updatedSyncBeforeFlush(old: T, current: T)

    @Deprecated("Implement removedSyncAfterConstraints() instead")
    fun removedSyncBeforeFlush(removed: T)
}
