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

import jetbrains.exodus.entitystore.EntityId
import kotlin.reflect.KProperty

interface DNQListener<in T : Any> {

    fun addedSyncBeforeConstraints(added: T)
    fun addedSync(added: T)

    fun updatedSyncBeforeConstraints(old: T, current: T)
    fun updatedSync(old: T, current: T)

    /**
     * Processes an entity that has been removed before applying constraints. Links and properties of the removed entity are still available and can be stored in removedEntityData for further use.
     *
     * @param removed The entity that has been removed.
     * @param removedEntityData Data related to the removed entity.
     */
    fun removedSyncBeforeConstraints(removed: T, removedEntityData: RemovedEntityData<T>)
    /**
     * Processes an entity that has been removed. If any property or link is required in the removedSync handler, it should be stored in the removedSyncBeforeConstraints with removedEntityData.storeValue(...)
     *
     * @param removedEntityData Data related to the removed entity.
     */
    fun removedSync(removedEntityData: RemovedEntityData<T>)
}

/**
 * Interface representing data related to a removed entity.
 *
 * @param E The type of the removed entity.
 */
interface RemovedEntityData<out E> {

    /**
     * The entity that has been removed. Mostly it's {@link com.jetbrains.teamsys.dnq.database.RemovedTransientEntity}. Fields and links should be accessed only within removedSyncBeforeConstraints
     */
    val removed: E
    /**
     * Unique identifier of the removed entity.
     */
    val removedId: EntityId
    /**
     * Retrieves the value associated with the given property name.
     *
     * @param name The name of the property whose value is to be retrieved.
     * @return The value of the specified property, or null if not found.
     */
    fun <T> getValue(name: String): T?
    /**
     * Retrieves the value associated with the specified property.
     *
     * @param T the type of the property.
     * @param property the property whose value is to be retrieved.
     * @return the value of the specified property, or null if the property does not have a value.
     */
    fun <T> getValue(property: KProperty<T>): T?
    /**
     * Stores a value associated with the given name.
     *
     * @param name The name with which the specified value is to be associated.
     * @param value The value to be stored.
     */
    fun <T> storeValue(name: String, value: T)
    /**
     * Stores a given value associated with the specified property.
     *
     * @param T The type of the property and value.
     * @param property The property, whose associated value is to be stored.
     * @param value The value to be stored.
     */
    fun <T> storeValue(property: KProperty<T>, value: T)
}

open class BasicRemovedEntityData<out E>(override val removed: E, override val removedId: EntityId) : RemovedEntityData<E> {
    private val data = HashMap<String, Any>()

    override fun <T> getValue(name: String): T? {
        @Suppress("UNCHECKED_CAST")
        return data[name] as? T
    }

    override fun <T> getValue(property: KProperty<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return data[property.name] as T?
    }

    override fun <T> storeValue(name: String, value: T) {
        data[name] = value as Any
    }

    override fun <T> storeValue(property: KProperty<T>, value: T) {
        data[property.name] = value as Any
    }
}
