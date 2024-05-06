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

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics
import com.jetbrains.teamsys.dnq.association.AssociationSemantics
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics
import com.jetbrains.teamsys.dnq.association.UndirectedAssociationSemantics
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator
import jetbrains.exodus.database.TransientChangesTracker
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.exceptions.CantRemoveEntityException
import jetbrains.exodus.database.exceptions.CardinalityViolationException
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.database.exceptions.NullPropertyException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.query.metadata.*
import mu.KLogging

object ConstraintsUtil: KLogging() {

    @JvmStatic
    fun checkCardinality(e: TransientEntity, md: AssociationEndMetaData): Boolean {
        val cardinality = md.cardinality
        if (cardinality == AssociationEndCardinality._0_n) return true

        val links = e.entity.getLinks(md.name)

        val iter = links.iterator()
        var size = 0
        while (size < 2 && iter.hasNext()) {
            iter.next()
            size++
        }

        return when (cardinality) {
            AssociationEndCardinality._0_1 -> size <= 1
            AssociationEndCardinality._1 -> size == 1
            AssociationEndCardinality._1_n -> size >= 1
            else -> throw IllegalArgumentException("Unknown cardinality [$cardinality]")
        }
    }

    @JvmStatic
    fun checkIncomingLinks(changesTracker: TransientChangesTracker): Set<DataIntegrityViolationException> {
        return changesTracker.changedEntities
                .asSequence()
                .filter { it.isRemoved }
                .map { targetEntity ->
                    val badIncomingLinks = targetEntity.incomingLinks
                            .asSequence()
                            .mapNotNull { (linkName, linkedEntities) ->
                                var incomingLinkViolation: IncomingLinkViolation? = null
                                linkedEntities
                                        .asSequence()
                                        .filterIsInstance<TransientEntity>()
                                        .filter { sourceEntity -> !sourceEntity.isRemoved && targetEntity !in sourceEntity.getRemovedLinks(linkName) }
                                        .takeWhile { sourceEntity ->
                                            val violation = incomingLinkViolation
                                                    ?: createIncomingLinkViolation(sourceEntity, linkName)
                                                            .also { newViolation ->
                                                                incomingLinkViolation = newViolation
                                                            }
                                            violation.tryAddCause(sourceEntity)
                                        }
                                        .toList()
                                incomingLinkViolation
                            }
                            .toList()
                    targetEntity to badIncomingLinks
                }
                .filter { (_, badIncomingLinks) -> badIncomingLinks.isNotEmpty() }
                .map { (targetEntity, badIncomingLinks) -> createIncomingLinksException(targetEntity, badIncomingLinks) }
                .toCollection(HashSetDecorator())
    }

    private fun createIncomingLinkViolation(linkSource: TransientEntity, linkName: String): IncomingLinkViolation {
        return linkSource.lifecycle
                ?.createIncomingLinkViolation(linkName, linkSource)
                ?: IncomingLinkViolation(linkName)
    }

    private fun createIncomingLinksException(targetEntity: TransientEntity, badIncomingLinks: List<IncomingLinkViolation>): DataIntegrityViolationException {
        val lifecycle = targetEntity.lifecycle
        return if (lifecycle != null) {
            lifecycle.createIncomingLinksException(badIncomingLinks, targetEntity)
        } else {
            val linkDescriptions = badIncomingLinks.map { it.description }
            val displayName = targetEntity.debugPresentation
            val displayMessage = "Could not delete $displayName, because it is referenced"
            return CantRemoveEntityException(targetEntity, displayMessage, displayName, linkDescriptions)
        }
    }

    @JvmStatic
    fun checkAssociationsCardinality(changesTracker: TransientChangesTracker, modelMetaData: ModelMetaData): Set<DataIntegrityViolationException> {
        return changesTracker.changedEntities
                .asSequence()
                .filter { !it.isRemoved }
                .mapNotNull { changedEntity ->
                    val entityMetaData = modelMetaData.getEntityMetaData(changedEntity.type)
                    if (entityMetaData != null) {
                        changedEntity to entityMetaData
                    } else {
                        logger.debug { "Cannot check links cardinality for entity $changedEntity. Entity metadata for its type [${changedEntity.type}] is undefined" }
                        null
                    }
                }
                .flatMap { (changedEntity, entityMetaData) ->
                    // if entity is new - check cardinality of all links
                    // if entity saved - check cardinality of changed links only
                    // meta-data may be null for persistent enums
                    // check only changed links of saved entity
                    when {
                        changedEntity.isNew -> entityMetaData.associationEndsMetaData
                                .asSequence()
                                .filter { !checkCardinality(changedEntity, it) }
                                .map { CardinalityViolationException(changedEntity, it) }
                        changedEntity.isSaved -> changesTracker.getChangedLinksDetailed(changedEntity)
                                ?.keys.orEmpty()
                                .asSequence()
                                .mapNotNull { changedLinkName ->
                                    entityMetaData.getAssociationEndMetaData(changedLinkName)
                                            .also { associationEndMetaData ->
                                                if (associationEndMetaData == null) {
                                                    logger.debug("Cannot check cardinality for link [${changedEntity.type}.$changedLinkName]. Association end metadata for it is undefined")
                                                }
                                            }
                                }
                                .filter { associationEndMetaData -> !checkCardinality(changedEntity, associationEndMetaData) }
                                .map { associationEndMetaData -> CardinalityViolationException(changedEntity, associationEndMetaData) }
                        else -> emptySequence()
                    }
                }
                .toCollection(HashSetDecorator())
    }

    @JvmStatic
    fun processOnDeleteConstraints(
            session: TransientStoreSession,
            entity: TransientEntity,
            entityMetaData: EntityMetaData,
            modelMetaData: ModelMetaData,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>) {

        // outgoing associations
        entityMetaData.associationEndsMetaData
                .asSequence()
                .filter { it.cascadeDelete || it.clearOnDelete }
                .forEach { associationEndMetaData ->
                    if (associationEndMetaData.cascadeDelete) {
                        logger.debug { "Cascade delete targets for link [$entity].${associationEndMetaData.name}" }
                    }
                    if (associationEndMetaData.clearOnDelete) {
                        logger.debug { "Clear associations with targets for link [$entity].${associationEndMetaData.name}" }
                    }
                    processOnSourceDeleteConstrains(entity, associationEndMetaData, callDestructorsPhase, processed)
                }

        // incoming associations
        entityMetaData.getIncomingAssociations(modelMetaData)
                .asSequence()
                .flatMap { (oppositeType, linkNames) ->
                    linkNames.asSequence().map { linkName -> oppositeType to linkName }
                }
                .forEach { (oppositeType, linkName) ->
                    processOnTargetDeleteConstraints(entity, modelMetaData, oppositeType, linkName, session, callDestructorsPhase, processed)
                }
    }

    private fun processOnSourceDeleteConstrains(
            entity: Entity,
            associationEndMetaData: AssociationEndMetaData,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>) {
        when (associationEndMetaData.cardinality) {
            AssociationEndCardinality._0_1,
            AssociationEndCardinality._1 ->
                processOnSourceDeleteConstraintForSingleLink(entity, associationEndMetaData, callDestructorsPhase, processed)
            AssociationEndCardinality._0_n,
            AssociationEndCardinality._1_n ->
                processOnSourceDeleteConstraintForMultipleLink(entity, associationEndMetaData, callDestructorsPhase, processed)
        }
    }

    private fun processOnSourceDeleteConstraintForSingleLink(
            source: Entity,
            associationEndMetaData: AssociationEndMetaData,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>) {
        val target = AssociationSemantics.getToOne(source, associationEndMetaData.name)
        if (target != null && !EntityOperations.isRemoved(target)) {
            if (associationEndMetaData.cascadeDelete || associationEndMetaData.oppositeEndOrNull?.targetCascadeDelete == true) {
                EntityOperations.remove(target, callDestructorsPhase, processed)
            } else if (!callDestructorsPhase) {
                removeSingleLink(source, associationEndMetaData, associationEndMetaData.oppositeEndOrNull, target)
            }
        }
    }

    private fun removeSingleLink(
            source: Entity,
            sourceEnd: AssociationEndMetaData,
            targetEnd: AssociationEndMetaData?,
            target: Entity) {
        when (sourceEnd.associationEndType) {
            AssociationEndType.ParentEnd ->
                if (targetEnd != null) {
                    AggregationAssociationSemantics.setOneToOne(source, sourceEnd.name, targetEnd.name, null)
                }

            AssociationEndType.ChildEnd ->
                if (targetEnd != null) {
                    // Here is cardinality check because we can remove parent-child link only from the parent side
                    when (targetEnd.cardinality) {
                        AssociationEndCardinality._0_1,
                        AssociationEndCardinality._1 ->
                            AggregationAssociationSemantics.setOneToOne(target, targetEnd.name, sourceEnd.name, null)
                        AssociationEndCardinality._0_n,
                        AssociationEndCardinality._1_n ->
                            AggregationAssociationSemantics.removeOneToMany(target, targetEnd.name, sourceEnd.name, source)
                    }
                }

            AssociationEndType.UndirectedAssociationEnd ->
                if (targetEnd != null) {
                    when (targetEnd.cardinality) {
                        AssociationEndCardinality._0_1,
                        AssociationEndCardinality._1 ->
                            // one to one
                            UndirectedAssociationSemantics.setOneToOne(source, sourceEnd.name, targetEnd.name, null)

                        AssociationEndCardinality._0_n,
                        AssociationEndCardinality._1_n ->
                            // many to one
                            UndirectedAssociationSemantics.removeOneToMany(target, targetEnd.name, sourceEnd.name, source)
                    }
                }

            AssociationEndType.DirectedAssociationEnd ->
                DirectedAssociationSemantics.setToOne(source, sourceEnd.name, null)

            else ->
                throw IllegalArgumentException("Cascade delete is not supported for association end type [${sourceEnd.associationEndType}] and [..1] cardinality")
        }
    }

    private fun processOnSourceDeleteConstraintForMultipleLink(
            source: Entity,
            associationEndMetaData: AssociationEndMetaData,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>) {
        AssociationSemantics.getToMany(source, associationEndMetaData.name)
                .toList()
                .asSequence()
                .filterNot { EntityOperations.isRemoved(it) }
                .forEach {
                    if (associationEndMetaData.cascadeDelete || associationEndMetaData.oppositeEndOrNull?.targetCascadeDelete == true) {
                        EntityOperations.remove(it, callDestructorsPhase, processed)
                    } else if (!callDestructorsPhase) {
                        removeOneLinkFromMultipleLink(source, associationEndMetaData, associationEndMetaData.oppositeEndOrNull, it)
                    }
                }
    }

    private fun removeOneLinkFromMultipleLink(
            source: Entity,
            sourceEnd: AssociationEndMetaData,
            targetEnd: AssociationEndMetaData?,
            target: Entity) {
        when (sourceEnd.associationEndType) {
            AssociationEndType.ParentEnd ->
                if (targetEnd != null) {
                    AggregationAssociationSemantics.removeOneToMany(source, sourceEnd.name, targetEnd.name, target)
                }

            AssociationEndType.UndirectedAssociationEnd ->
                if (targetEnd != null) {
                    when (targetEnd.cardinality) {
                        AssociationEndCardinality._0_1,
                        AssociationEndCardinality._1 ->
                            // one to many
                            UndirectedAssociationSemantics.removeOneToMany(source, sourceEnd.name, targetEnd.name, target)
                        AssociationEndCardinality._0_n,
                        AssociationEndCardinality._1_n ->
                            // many to many
                            UndirectedAssociationSemantics.removeManyToMany(source, sourceEnd.name, targetEnd.name, target)
                    }
                }

            AssociationEndType.DirectedAssociationEnd ->
                DirectedAssociationSemantics.removeToMany(source, sourceEnd.name, target)

            else ->
                throw IllegalArgumentException("Cascade delete is not supported for association end type [${sourceEnd.associationEndType}] and [..n] cardinality")
        }
    }

    private fun processOnTargetDeleteConstraints(
            target: TransientEntity,
            modelMetaData: ModelMetaData,
            oppositeType: String,
            linkName: String,
            session: TransientStoreSession,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>) {

        val oppositeEntityMetaData = modelMetaData.getEntityMetaData(oppositeType)
                ?: throw RuntimeException("Cannot find metadata for entity type $oppositeType as opposite to ${target.type}")
        val associationEndMetaData = oppositeEntityMetaData.getAssociationEndMetaData(linkName)
        val changesTracker = session.transientChangesTracker

        session.findLinks(oppositeType, target, linkName)
                .asSequence()
                .filterIsInstance<TransientEntity>()
                .filter { !it.isRemoved }
                .forEach { source ->
                    val linkRemoved = changesTracker.getChangedLinksDetailed(source)
                            // Change can be null if current link is not changed, but some was
                            ?.get(linkName)
                            ?.removedEntities
                            ?.contains(target)
                            ?: false

                    if (!linkRemoved) {
                        if (associationEndMetaData.targetCascadeDelete) {
                            logger.debug { "Cascade delete targets for link [$source].$linkName" }
                            EntityOperations.remove(source, callDestructorsPhase, processed)
                        } else if (associationEndMetaData.targetClearOnDelete && !callDestructorsPhase) {
                            logger.debug { "Clear associations with targets for link [$source].$linkName" }
                            removeLink(source, target, associationEndMetaData)
                        }
                    }
                }
    }

    private fun removeLink(source: Entity, target: Entity, sourceEnd: AssociationEndMetaData) {
        val targetEnd = sourceEnd.oppositeEndOrNull
        when (sourceEnd.cardinality) {
            AssociationEndCardinality._0_1,
            AssociationEndCardinality._1 ->
                removeSingleLink(source, sourceEnd, targetEnd, target)

            AssociationEndCardinality._0_n,
            AssociationEndCardinality._1_n ->
                removeOneLinkFromMultipleLink(source, sourceEnd, targetEnd, target)
        }
    }

    private val AssociationEndMetaData.oppositeEndOrNull: AssociationEndMetaData?
        get() = if (associationEndType != AssociationEndType.DirectedAssociationEnd) {
            associationMetaData.getOppositeEnd(this)
        } else {
            // there is no opposite end in directed association
            null
        }


    @JvmStatic
    fun checkRequiredProperties(
            tracker: TransientChangesTracker,
            modelMetaData: ModelMetaData): Set<DataIntegrityViolationException> {

        return tracker.changedEntities
                .asSequence()
                .filter { !it.isRemoved }
                .mapNotNull { changedEntity ->
                    modelMetaData.getEntityMetaData(changedEntity.type)
                            ?.let { entityMetaData -> changedEntity to entityMetaData }
                }
                .flatMap { (changedEntity, entityMetaData) ->
                    val changedProperties = tracker.getChangedProperties(changedEntity)
                    if (changedEntity.isNew || changedProperties != null && changedProperties.isNotEmpty()) {
                        val requiredProperties = entityMetaData
                                .requiredProperties
                                .asSequence()
                        val requiredIfProperties = EntityMetaDataUtils
                                .getRequiredIfProperties(entityMetaData, changedEntity)

                        val changedAndRequiredIfProperties = if (requiredIfProperties.isEmpty()) changedProperties else ((changedProperties
                                ?: emptySet()) + requiredIfProperties)

                        (requiredProperties + requiredIfProperties)
                                .mapNotNull { checkProperty(changedEntity, changedAndRequiredIfProperties, entityMetaData, it) }
                    } else {
                        emptySequence()
                    }
                }
                .toCollection(HashSetDecorator())
    }

    @JvmStatic
    fun checkOtherPropertyConstraints(
            tracker: TransientChangesTracker,
            modelMetaData: ModelMetaData): Set<DataIntegrityViolationException> {

        return tracker.changedEntities
                .asSequence()
                .filter { !it.isRemoved }
                .mapNotNull { changedEntity ->
                    modelMetaData.getEntityMetaData(changedEntity.type)
                            ?.let { entityMetaData -> changedEntity to entityMetaData }
                }
                .flatMap { (changedEntity, entityMetaData) ->
                    val propertyConstraints = changedEntity.lifecycle?.propertyConstraints(changedEntity).orEmpty()

                    getChangedPropertiesWithConstraints(tracker, changedEntity, propertyConstraints)
                            .mapNotNull { (propertyName, constraints) ->
                                entityMetaData.getPropertyMetaData(propertyName)
                                        ?.let { propertyMetaData -> Triple(propertyName, constraints, propertyMetaData) }
                            }
                            .flatMap { (propertyName, constraints, propertyMetaData) ->
                                val type = getPropertyType(propertyMetaData)
                                val propertyValue = getPropertyValue(changedEntity, propertyName, type)
                                constraints.asSequence()
                                        .mapNotNull {
                                            it as PropertyConstraint<Any?>
                                            it.check(changedEntity, propertyMetaData, propertyValue)
                                        }
                            }
                }
                .toCollection(HashSetDecorator())
    }

    private fun getChangedPropertiesWithConstraints(
            tracker: TransientChangesTracker,
            changedEntity: TransientEntity,
            constrainedProperties: Map<String, Iterable<PropertyConstraint<*>>>
    ): Sequence<Pair<String, Iterable<PropertyConstraint<*>>>> {
        return if (changedEntity.isNew) {
            // All properties with constraints
            constrainedProperties
                    .asSequence()
                    .map { (key, value) -> key to value }
        } else {
            // Changed properties with constraints
            tracker.getChangedProperties(changedEntity)
                    .orEmpty()
                    .asSequence()
                    .mapNotNull { key -> constrainedProperties[key]?.let { value -> key to value } }
        }
    }

    /**
     * Properties and associations, that are part of indexes, can't be empty
     *
     * @param tracker changes tracker
     * @param modelMetaData      model metadata
     * @return index fields errors set
     */
    @JvmStatic
    fun checkIndexFields(
            tracker: TransientChangesTracker,
            modelMetaData: ModelMetaData
    ): Set<DataIntegrityViolationException> {

        return tracker.changedEntities
                .asSequence()
                .filter { !it.isRemoved }
                .mapNotNull { changedEntity ->
                    modelMetaData.getEntityMetaData(changedEntity.type)
                            ?.let { entityMetaData -> changedEntity to entityMetaData }
                }
                .flatMap { (changedEntity, entityMetaData) ->
                    val changedProperties = tracker.getChangedProperties(changedEntity)

                    entityMetaData.indexes
                            .asSequence()
                            .flatMap { index -> index.fields.asSequence() }
                            .mapNotNull { indexField ->
                                if (indexField.isProperty) {
                                    if (changedEntity.isNew || changedProperties != null && changedProperties.isNotEmpty()) {
                                        checkProperty(changedEntity, changedProperties, entityMetaData, indexField.name)
                                    } else {
                                        null
                                    }
                                } else {
                                    // link
                                    if (!checkCardinality(changedEntity, entityMetaData.getAssociationEndMetaData(indexField.name))) {
                                        CardinalityViolationException("Association [${indexField.name}] cannot be empty, because it's part of unique constraint", changedEntity, indexField.name)
                                    } else {
                                        null
                                    }
                                }
                            }
                }
                .toCollection(HashSetDecorator())
    }

    private fun checkProperty(
            entity: TransientEntity,
            changedProperties: Set<String>?,
            entityMetaData: EntityMetaData,
            name: String
    ): NullPropertyException? {

        return if (entity.isNew || name in changedProperties.orEmpty()) {
            val type = getPropertyType(entityMetaData.getPropertyMetaData(name))

            if (isPropertyUndefined(entity, name, type)) {
                NullPropertyException(entity, name)
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun getPropertyType(propertyMetaData: PropertyMetaData?): PropertyType {
        return if (propertyMetaData != null) {
            propertyMetaData.type
        } else {
            logger.warn("Cannot determine property type. Try to get property value as if it of primitive type.")
            PropertyType.PRIMITIVE
        }
    }

    private fun isPropertyUndefined(entity: TransientEntity, name: String, type: PropertyType): Boolean {
        return when (type) {
            PropertyType.PRIMITIVE -> entity.getProperty(name).isEmptyPrimitiveProperty()
            PropertyType.BLOB -> entity.getBlob(name) == null
            PropertyType.TEXT -> entity.getBlobString(name).isEmptyPrimitiveProperty()
            else -> throw IllegalArgumentException("Unknown property type: $name")
        }
    }

    private fun getPropertyValue(e: TransientEntity, name: String, type: PropertyType): Any? {
        return when (type) {
            PropertyType.PRIMITIVE -> e.getProperty(name)
            PropertyType.BLOB -> e.getBlob(name)
            PropertyType.TEXT -> e.getBlobString(name)
            else -> throw IllegalArgumentException("Unknown property type: $name")
        }
    }

    private fun Comparable<*>?.isEmptyPrimitiveProperty(): Boolean {
        return this == null || this == ""
    }

}
