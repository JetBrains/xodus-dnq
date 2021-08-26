/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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
package jetbrains.exodus.database.exceptions

import jetbrains.exodus.entitystore.Entity

open class CantRemoveEntityException(
        entity: Entity,
        displayMessage: String,
        val entityPresentation: String,
        val incomingLinkDescriptions: Collection<Collection<String>>
) : DataIntegrityViolationException(buildMessage(entityPresentation, incomingLinkDescriptions), displayMessage, entity = entity) {
    val causes get() = incomingLinkDescriptions
}

private fun buildMessage(entityPresentation: String, incomingLinkDescriptions: Collection<Collection<String>>) = buildString {
    append("Could not delete $entityPresentation, because it is referenced as: ")
    incomingLinkDescriptions.forEach { description ->
        description.joinTo(this, ", ")
        append("; ")
    }
}