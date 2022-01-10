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
package kotlinx.dnq.store

import com.jetbrains.teamsys.dnq.database.EntityLifecycle
import com.jetbrains.teamsys.dnq.database.IncomingLinkViolation
import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import com.jetbrains.teamsys.dnq.database.TransientEntityImpl
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator
import jetbrains.exodus.database.exceptions.CantRemoveEntityException
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.EntityMetaData
import kotlinx.dnq.NamedXdEntity
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.simple.RequireIfConstraint
import kotlinx.dnq.toXd

class EntityLifecycleImpl : EntityLifecycle {

    override fun onBeforeFlush(entity: Entity) {
        entity.toXd<XdEntity>().beforeFlush()
    }

    override fun onRemove(entity: Entity) {
        entity.toXd<XdEntity>().destructor()
    }

    override fun requireIfConstraints(entity: Entity): Map<String, Iterable<PropertyConstraint<*>>> {
        return XdModel[entity.type]?.requireIfConstraints.orEmpty()
    }

    override fun propertyConstraints(entity: Entity): Map<String, Iterable<PropertyConstraint<*>>> {
        return XdModel[entity.type]?.propertyConstraints.orEmpty()
    }

    override fun getRequiredIfProperties(emd: EntityMetaData, entity: Entity): Set<String> {
        val requireIfConstraints = XdModel[entity.type]?.requireIfConstraints.orEmpty()
        val xdEntity = entity.toXd<XdEntity>()
        return emd.getRequiredIfProperties(entity)
                .asSequence()
                .filter { requireIfConstraints.isPropertyRequired(it, xdEntity) }
                .toCollection(HashSetDecorator<String>())
    }

    override fun createIncomingLinkViolation(linkName: String, entity: Entity): IncomingLinkViolation? {
        return XdModel[entity.type]?.createIncomingLinkViolation(linkName)
    }

    override fun createIncomingLinksException(linkViolations: List<IncomingLinkViolation>, entity: Entity): DataIntegrityViolationException {
        val xdEntity = entity.toXd<XdEntity>()
        val linkDescriptions = linkViolations.map { it.description }
        val displayName = when {
            xdEntity is NamedXdEntity -> xdEntity.displayName
            entity is TransientEntityImpl -> entity.debugPresentation
            else -> entity.toString()
        }

        val displayMessage = "Could not delete $displayName, because it is referenced"
        return CantRemoveEntityException(entity, displayMessage, displayName, linkDescriptions)
    }

    private fun Map<String, List<RequireIfConstraint<*, *>>>.isPropertyRequired(propertyName: String, xdEntity: XdEntity): Boolean {
        return getOrElse(propertyName) { emptyList() }.any {
            @Suppress("UNCHECKED_CAST")
            with(xdEntity, it.predicate as XdEntity.() -> Boolean)
        }

    }

}