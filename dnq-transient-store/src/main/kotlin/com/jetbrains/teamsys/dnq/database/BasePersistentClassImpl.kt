/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityStore
import jetbrains.exodus.database.exceptions.CantRemoveEntityException
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import java.util.concurrent.Callable

abstract class BasePersistentClassImpl : Runnable {
    private var _propertyConstraints: MutableMap<String, MutableList<PropertyConstraint<Any?>>>? = null

    val propertyConstraints: Map<String, Iterable<PropertyConstraint<Any?>>>
        get() = _propertyConstraints.orEmpty()

    lateinit var entityStore: TransientEntityStore

    open val propertyDisplayNames: Map<String, Callable<String>>
        get() = emptyMap()

    open protected fun _constructor(_entityType_: String): Entity {
        return entityStore.threadSessionOrThrow.newEntity(_entityType_)
    }

    open fun isPropertyRequired(name: String, entity: Entity): Boolean {
        return false
    }

    open fun getPropertyDisplayName(name: String): String {
        val displayName = this.propertyDisplayNames[name]
        return if (displayName != null) displayName.call() else name
    }

    open fun destructor(entity: Entity) {}

    open fun executeBeforeFlushTrigger(entity: Entity) {}

    @Deprecated("")
    open fun evaluateSaveHistoryCondition(entity: Entity): Boolean {
        return false
    }

    @Deprecated("")
    open fun saveHistoryCallback(entity: Entity) {
    }

    fun <T> addPropertyConstraint(propertyName: String, constraint: PropertyConstraint<T>) {
        val allConstraintsForType = _propertyConstraints ?: LinkedHashMap<String, MutableList<PropertyConstraint<Any?>>>().also { _propertyConstraints = it }
        val constraintsForProperty = allConstraintsForType.getOrPut(propertyName) { ArrayList() }
        @Suppress("UNCHECKED_CAST")
        constraintsForProperty.add(constraint as PropertyConstraint<Any?>)
    }

    fun createIncomingLinksException(linkViolations: List<IncomingLinkViolation>, entity: Entity): DataIntegrityViolationException {
        val linkDescriptions = linkViolations.map { it.description }
        val displayName = getDisplayName(entity)
        val displayMessage = "Could not delete $displayName, because it is referenced"
        return CantRemoveEntityException(entity, displayMessage, displayName, linkDescriptions)
    }

    open fun createIncomingLinkViolation(linkName: String): IncomingLinkViolation {
        return IncomingLinkViolation(linkName)
    }

    open fun getDisplayName(entity: Entity): String {
        return toString(entity)
    }

    fun toString(entity: Entity): String {
        return (entity as? TransientEntity)?.debugPresentation ?: entity.toString()
    }

    companion object {
        @JvmStatic
        fun <T> buildSet(data: Array<T>) = data.toSet()
    }

}
