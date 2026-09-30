/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
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
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.query.metadata.*
import mu.KLogging

object ConstraintsUtil: KLogging() {

    @JvmStatic
    fun checkCardinality(e: TransientEntity, md: AssociationEndMetaData): Boolean {
        val cardinality = md.cardinality
        if (cardinality == AssociationEndCardinality._0_n) return true

        // Only "none / one / more than one" matters, so read at most as many targets as the rule
        // can distinguish: one for `_1_n`, two for `_0_1` and `_1`.
        val limit = if (cardinality == AssociationEndCardinality._1_n) 1 else 2
        val size = countLinksUpTo(e, md.name, limit)

        return when (cardinality) {
            AssociationEndCardinality._0_1 -> size <= 1
            AssociationEndCardinality._1 -> size == 1
            AssociationEndCardinality._1_n -> size >= 1
            else -> throw IllegalArgumentException("Unknown cardinality [$cardinality]")
        }
    }

    /**
     * `min(limit, number of targets)`. Only the plain persistent vertex has the bounded read;
     * any other wrapper keeps its own `getLinks` dispatch.
     */
    private fun countLinksUpTo(e: TransientEntity, linkName: String, limit: Int): Int {
        val persistent = e.entity
        if (persistent.javaClass == YTDBVertexEntity::class.java) {
            return (persistent as YTDBVertexEntity).countLinksUpTo(linkName, limit)
        }
        val iter = persistent.getLinks(linkName).iterator()
        var size = 0
        while (size < limit && iter.hasNext()) {
            iter.next()
            size++
        }
        return size
    }

    @JvmStatic
    fun checkIncomingLinks(changesTracker: TransientChangesTracker): Set<DataIntegrityViolationException> {
        return changesTracker.changedEntities
                .asSequence()
                .filter { it.isRemoved }
                .map { targetEntity ->
                    // Reobserve after before-flush work, orphan removal, and on every replay.
                    // No raw read is needed when metadata cannot produce an incoming query.
                    val hasIncomingMetadata = if (targetEntity.javaClass == TransientEntityImpl::class.java) {
                        val modelMetaData = (targetEntity as TransientEntityImpl).getStore().modelMetaData
                        modelMetaData?.let { metadata ->
                            metadata.getEntityMetaData(targetEntity.type)
                                ?.getIncomingAssociations(metadata)?.isNotEmpty()
                        } == true
                    } else false
                    val badIncomingLinks = if (hasIncomingMetadata &&
                        (targetEntity as TransientEntityImpl).hasNoIncomingLinksForDeletion()) {
                        emptyList()
                    } else collectIncomingLinkViolations(targetEntity)
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

    /**
     * One violation per metadata `(source type, link name)` pair that has at least one live source,
     * in `incomingLinks` order. Lifecycle factories run only after every read finished and in pair
     * order, so grouping the reads does not change their order.
     */
    private fun collectIncomingLinkViolations(target: TransientEntity): List<IncomingLinkViolation> {
        val linkSources = if (target is TransientEntityImpl) {
            groupedIncomingSources(target)
        } else {
            target.incomingLinks.map { (linkName, sources) ->
                linkName to liveIncomingSources(target, linkName, sources.asSequence())
            }
        }
        return linkSources.mapNotNull { (linkName, sources) ->
            var violation: IncomingLinkViolation? = null
            for (source in sources) {
                val current = violation
                    ?: createIncomingLinkViolation(source, linkName).also { violation = it }
                if (!current.tryAddCause(source)) break
            }
            violation
        }
    }

    /**
     * The first sources of [sources] that still reference [target] through [linkName], stopping after
     * the one that overflows the violation report. Never reads further than the report needs.
     */
    private fun liveIncomingSources(
            target: TransientEntity,
            linkName: String,
            sources: Sequence<Entity>): List<TransientEntity> =
        sources
                .filterIsInstance<TransientEntity>()
                .filter { source -> !source.isRemoved && target !in source.getRemovedLinks(linkName) }
                .take(MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW + 1)
                .toList()

    private class IncomingPairScan(val linkName: String, val declaredType: String, modelMetaData: ModelMetaData) {
        // A typed polymorphic read returns instances of the type and of all its subtypes.
        val sourceTypes: Set<String> = HashSet<String>().apply {
            add(declaredType)
            modelMetaData.getEntityMetaData(declaredType)?.allSubTypes?.let(::addAll)
        }
        val live = ArrayList<TransientEntity>()
        val isFull: Boolean get() = live.size > MAXIMUM_BAD_LINKED_ENTITIES_TO_SHOW
    }

    /**
     * Live incoming sources for every `(source type, link name)` pair, in the order of
     * `incomingLinks`. Pairs sharing a link name are served by a single untyped read that is
     * dispatched by source type in memory; a lone pair keeps its typed read. Each pair keeps at most
     * as many sources as its violation report can use, and the read stops once all pairs are full.
     */
    private fun groupedIncomingSources(target: TransientEntityImpl): List<Pair<String, List<TransientEntity>>> {
        val store = target.getStore()
        val modelMetaData = store.modelMetaData ?: return emptyList()
        val incomingAssociations = modelMetaData.getEntityMetaData(target.type)
            ?.getIncomingAssociations(modelMetaData) ?: return emptyList()
        val session = store.threadSessionOrThrow
        val scans = incomingAssociations.flatMap { (sourceType, linkNames) ->
            linkNames.map { linkName -> IncomingPairScan(linkName, sourceType, modelMetaData) }
        }
        scans.groupBy { it.linkName }.forEach { (linkName, group) ->
            if (group.size == 1) {
                val scan = group.single()
                scan.live += liveIncomingSources(
                    target, linkName, session.findLinks(scan.declaredType, target, linkName).asSequence())
            } else {
                val sources = session.createPersistentEntityIterableWrapper(
                    (session.transactionInternal as YTDBStoreTransaction).findLinksUntyped(target, linkName))
                for (source in sources) {
                    if (source !is TransientEntity) continue
                    val sourceType = source.type
                    var stillReferences: Boolean? = null
                    for (scan in group) {
                        if (scan.isFull || sourceType !in scan.sourceTypes) continue
                        val referenced = stillReferences
                            ?: (!source.isRemoved && target !in source.getRemovedLinks(linkName))
                                .also { stillReferences = it }
                        if (!referenced) break
                        scan.live += source
                    }
                    if (group.all { it.isFull }) break
                }
            }
        }
        return scans.map { it.linkName to it.live }
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
            processed: MutableSet<Entity>,
            checkEntityRemoved: Boolean = true) {

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
                    processOnSourceDeleteConstrains(entity, associationEndMetaData, callDestructorsPhase, processed, checkEntityRemoved)
                }

        // incoming associations — one untyped DB query per distinct linkName, then dispatch in-memory.
        // Groups whose source policies cannot act in this phase are not read at all (FAIL is enforced
        // at validation, CLEAR acts only in the mutation phase).
        val ytdbTransaction = session.transactionInternal as YTDBStoreTransaction
        val incomingAssociations = entityMetaData.getIncomingAssociations(modelMetaData)
        val actionableGroups = incomingAssociations
                .asSequence()
                .flatMap { (oppositeType, linkNames) ->
                    linkNames.asSequence().map { linkName -> linkName to oppositeType }
                }
                .groupBy({ it.first }, { it.second })
                .filter { (linkName, oppositeTypes) ->
                    oppositeTypes.any { hasOnTargetDeleteWork(modelMetaData, it, linkName, callDestructorsPhase) }
                }
        if (actionableGroups.isEmpty()) return
        // Outgoing policies and callbacks above can add links. Observe once per phase, only
        // when an incoming group can act; never reuse across phases or replay.
        if (entity.javaClass == TransientEntityImpl::class.java &&
            (entity as TransientEntityImpl).hasNoIncomingLinksForDeletion()) return
        actionableGroups.forEach { (linkName, oppositeTypes) ->
            val allSources = session.createPersistentEntityIterableWrapper(
                ytdbTransaction.findLinksUntyped(entity, linkName)
            ).toList()
            for (oppositeType in oppositeTypes) {
                processOnTargetDeleteConstraints(entity, modelMetaData, oppositeType, linkName, allSources, session, callDestructorsPhase, processed)
            }
        }
    }

    /**
     * Whether [processOnTargetDeleteConstraints] can act for this source type in the given phase.
     * Missing entity metadata is reported as work so that dispatch keeps throwing for it.
     */
    private fun hasOnTargetDeleteWork(
            modelMetaData: ModelMetaData,
            oppositeType: String,
            linkName: String,
            callDestructorsPhase: Boolean): Boolean {
        val oppositeEntityMetaData = modelMetaData.getEntityMetaData(oppositeType) ?: return true
        val associationEndMetaData = oppositeEntityMetaData.getAssociationEndMetaData(linkName) ?: return false
        return associationEndMetaData.targetCascadeDelete ||
                (associationEndMetaData.targetClearOnDelete && !callDestructorsPhase)
    }

    private fun processOnSourceDeleteConstrains(
            entity: Entity,
            associationEndMetaData: AssociationEndMetaData,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>,
            checkEntityRemoved: Boolean) {
        // The destructor phase only cascades; clearing targets is a mutation-phase action. Without
        // a cascade obligation nothing would be done with the targets, so do not load them.
        if (callDestructorsPhase && !associationEndMetaData.cascadesToTargets()) return
        when (associationEndMetaData.cardinality) {
            AssociationEndCardinality._0_1,
            AssociationEndCardinality._1 ->
                processOnSourceDeleteConstraintForSingleLink(entity, associationEndMetaData, callDestructorsPhase, processed, checkEntityRemoved)
            AssociationEndCardinality._0_n,
            AssociationEndCardinality._1_n ->
                processOnSourceDeleteConstraintForMultipleLink(entity, associationEndMetaData, callDestructorsPhase, processed, checkEntityRemoved)
        }
    }

    private fun processOnSourceDeleteConstraintForSingleLink(
            source: Entity,
            associationEndMetaData: AssociationEndMetaData,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>,
            checkEntityRemoved: Boolean) {
        val target = AssociationSemantics.getToOne(source, associationEndMetaData.name, checkEntityRemoved)
        if (target != null && !EntityOperations.isRemoved(target)) {
            if (associationEndMetaData.cascadesToTargets()) {
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
            processed: MutableSet<Entity>,
            checkEntityRemoved: Boolean) {
        // Preserve specialized getLinks dispatch (notably ReadonlyTransientEntity snapshots).
        // Only the ordinary transient wrapper can use the raw adjacency. Reattach on every
        // invocation so the mutation phase and replay both read the current transaction.
        val attached = source.reattachTransient(checkEntityRemoved = checkEntityRemoved)
        val targets = if (attached.javaClass == TransientEntityImpl::class.java) {
            (attached as TransientEntityImpl).outgoingLinksForDeletion(associationEndMetaData.name)
        } else null
        val candidates = targets ?: attached.getLinks(associationEndMetaData.name).toList()
        if (attached.javaClass == TransientEntityImpl::class.java &&
            canClearTargetsInBulk(associationEndMetaData, callDestructorsPhase, checkEntityRemoved)) {
            // Nothing is cascaded, so the selection cannot change while it is cleared.
            val selected = candidates.filterNot { EntityOperations.isRemoved(it) }
            if (selected.size > 1 && selected.all { it.javaClass == TransientEntityImpl::class.java }) {
                (attached as TransientEntityImpl).deleteSelectedLinksForDeletion(
                    associationEndMetaData.name,
                    selected.map { it.reattachTransient() }
                )
                return
            }
        }
        candidates
                .asSequence()
                .filterNot { EntityOperations.isRemoved(it) }
                .forEach {
                    if (associationEndMetaData.cascadesToTargets()) {
                        EntityOperations.remove(it, callDestructorsPhase, processed)
                    } else if (!callDestructorsPhase) {
                        removeOneLinkFromMultipleLink(source, associationEndMetaData, associationEndMetaData.oppositeEndOrNull, it)
                    }
                }
    }

    /**
     * The mutation-phase clearing of a directed, non-cascading plural link on the normal deletion
     * dispatch. Replay reprocessing of a removed source (`checkEntityRemoved = false`), aggregation
     * and undirected ends keep the per-target dispatch.
     */
    private fun canClearTargetsInBulk(
            sourceEnd: AssociationEndMetaData,
            callDestructorsPhase: Boolean,
            checkEntityRemoved: Boolean): Boolean =
        !callDestructorsPhase &&
                checkEntityRemoved &&
                !sourceEnd.cascadesToTargets() &&
                sourceEnd.associationEndType == AssociationEndType.DirectedAssociationEnd

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
            allSources: List<Entity>,
            session: TransientStoreSession,
            callDestructorsPhase: Boolean,
            processed: MutableSet<Entity>) {

        val oppositeEntityMetaData = modelMetaData.getEntityMetaData(oppositeType)
                ?: throw RuntimeException("Cannot find metadata for entity type $oppositeType as opposite to ${target.type}")
        val associationEndMetaData = oppositeEntityMetaData.getAssociationEndMetaData(linkName)
        if (associationEndMetaData == null) {
            logger.debug("Cannot check onTargetDelete constraints for link [$oppositeType.$linkName]. Association end metadata for it is undefined")
            return
        }
        val changesTracker = session.transientChangesTracker

        allSources
                .asSequence()
                .filterIsInstance<TransientEntity>()
                .filter { it.type == oppositeType }
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

    /** True when deleting the source must delete the link targets (own or opposite-end cascade). */
    private fun AssociationEndMetaData.cascadesToTargets(): Boolean =
        cascadeDelete || oppositeEndOrNull?.targetCascadeDelete == true

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
