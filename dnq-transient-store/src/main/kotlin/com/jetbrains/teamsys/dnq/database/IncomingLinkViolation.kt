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
package com.jetbrains.teamsys.dnq.database


import jetbrains.exodus.core.dataStructures.NanoSet
import jetbrains.exodus.entitystore.Entity

private const val MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW = 10

open class IncomingLinkViolation(val linkName: String) {
    private val entitiesCausedViolation = ArrayList<Entity>(MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW)
    private var hasMoreEntitiesCausedViolations = false

    // default implementation
    open val description: Collection<String>
        get() = buildDescription(entitiesCausedViolation, hasMoreEntitiesCausedViolations)

    protected open fun buildDescription(entitiesCausedViolation: List<Entity>, hasMoreEntitiesCausedViolations: Boolean): Set<String> {
        return setOf(buildString {
            append(linkName).append(" for {")
            entitiesCausedViolation.joinTo(this, ", ")
            if (hasMoreEntitiesCausedViolations) {
                append(" and more...}")
            } else {
                append("}")
            }
        })
    }

    fun tryAddCause(cause: Entity): Boolean {
        return if (entitiesCausedViolation.size < MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW) {
            entitiesCausedViolation.add(cause)
            true
        } else {
            hasMoreEntitiesCausedViolations = true
            false
        }
    }

    fun createPerInstanceErrorMessage(messageBuilder: MessageBuilder): Collection<String> {
        return entitiesCausedViolation
                .map { messageBuilder.build(null, it, hasMoreEntitiesCausedViolations) }
                .plus(listOfNotNull("and more...".takeIf { hasMoreEntitiesCausedViolations }))
    }

    fun createPerTypeErrorMessage(messageBuilder: MessageBuilder): Collection<String> {
        return NanoSet(messageBuilder.build(entitiesCausedViolation, null, hasMoreEntitiesCausedViolations))
    }
}
