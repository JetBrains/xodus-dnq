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

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.EntityStoreException

abstract class DataIntegrityViolationException : EntityStoreException {

    val entityId: EntityId?
    val displayMessage: String

    open val entityFieldHandler: EntityFieldHandler?
        get() = null

    @JvmOverloads
    constructor(message: String, displayMessage: String = message, entity: Entity? = null) : super(message) {
        this.displayMessage = displayMessage
        this.entityId = entity?.id
    }

    @JvmOverloads
    constructor(message: String, displayMessage: String = message, entity: Entity? = null, cause: Throwable) : super(message, cause) {
        this.displayMessage = displayMessage
        this.entityId = entity?.id
    }

    open fun relatesTo(entity: TransientEntity, fieldIdentity: Any?) = entity.id == entityId
}
