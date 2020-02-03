/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore

import jetbrains.exodus.database.IEntityListener

abstract class EntityAdapter<T : Entity> : IEntityListener<T> {
    override fun addedAsync(added: T) = Unit
    override fun addedSync(added: T) = Unit
    override fun addedSyncAfterConstraints(added: T) = @Suppress("DEPRECATION") addedSyncBeforeFlush(added)
    override fun addedSyncBeforeConstraints(added: T) = Unit
    override fun updatedAsync(old: T, current: T) = Unit
    override fun updatedSync(old: T, current: T) = Unit
    override fun updatedSyncAfterConstraints(old: T, current: T) = @Suppress("DEPRECATION") updatedSyncBeforeFlush(old, current)
    override fun updatedSyncBeforeConstraints(old: T, current: T) = Unit
    override fun removedAsync(removed: T) = Unit
    override fun removedSync(removed: T) = Unit
    override fun removedSyncAfterConstraints(added: T) = @Suppress("DEPRECATION") removedSyncBeforeFlush(added)
    override fun removedSyncBeforeConstraints(removed: T) = Unit

    @Deprecated("Override addedSyncAfterConstraints() instead")
    override fun addedSyncBeforeFlush(added: T) = Unit

    @Deprecated("Override updatedSyncAfterConstraints() instead")
    override fun updatedSyncBeforeFlush(old: T, current: T) = Unit

    @Deprecated("Override removedSyncAfterConstraints() instead")
    override fun removedSyncBeforeFlush(removed: T) = Unit
}
