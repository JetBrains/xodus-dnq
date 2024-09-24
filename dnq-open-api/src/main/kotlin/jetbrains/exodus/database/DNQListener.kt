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
package jetbrains.exodus.database

import jetbrains.exodus.entitystore.orientdb.OEntityId
import kotlin.reflect.KProperty

interface DNQListener<in T : Any> {

    fun addedSyncBeforeConstraints(added: T)
    fun addedSync(added: T)

    fun updatedSyncBeforeConstraints(old: T, current: T)
    fun updatedSync(old: T, current: T)

    fun removedSyncBeforeConstraints(removed: T, requestListenerStorage: () -> DnqListenerTransientData<T>)
    fun removedSync(removed: OEntityId, requestListenerStorage: () -> DnqListenerTransientData<T>)
}

interface DnqListenerTransientData<out T> {
    fun <T> getValue(name: String): T?
    fun <T> storeValue(name: String, value: T)
    fun getRemoved(): T
    fun setRemoved(entity: Any)

    fun <T> getValue(property: KProperty<T>): T? {
        return getValue(property.name) as T?
    }

    fun <T> storeValue(property: KProperty<T>, value: T) {
        storeValue(property.name, value)
    }
}
