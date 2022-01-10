/**
 * Copyright 2006 - 2022 JetBrains s.r.o.
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

import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.EntityMetaData

interface EntityLifecycle {

    fun onBeforeFlush(entity: Entity)

    fun onRemove(entity: Entity)

    fun requireIfConstraints(entity: Entity): Map<String, Iterable<PropertyConstraint<*>>>

    fun propertyConstraints(entity: Entity): Map<String, Iterable<PropertyConstraint<*>>>

    fun getRequiredIfProperties(emd: EntityMetaData, entity: Entity): Set<String>

    fun createIncomingLinksException(linkViolations: List<IncomingLinkViolation>, entity: Entity): DataIntegrityViolationException

    fun createIncomingLinkViolation(linkName: String, entity: Entity): IncomingLinkViolation?

}