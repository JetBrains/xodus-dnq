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

import com.orientechnologies.common.concur.ONeedRetryException
import com.orientechnologies.orient.core.db.ODatabaseSession
import com.orientechnologies.orient.core.record.OVertex
import com.orientechnologies.orient.core.record.impl.ORecordBytes
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator
import jetbrains.exodus.database.*
import jetbrains.exodus.database.exceptions.*
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.iterate.EntityIdSet
import jetbrains.exodus.entitystore.orientdb.OComparableSet
import jetbrains.exodus.entitystore.orientdb.OEntity
import jetbrains.exodus.entitystore.orientdb.OReadonlyVertexEntity
import jetbrains.exodus.entitystore.orientdb.OStoreTransaction
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import jetbrains.exodus.env.ReadonlyTransactionException
import jetbrains.exodus.env.Transaction
import jetbrains.exodus.env.TransactionFinishedException
import jetbrains.exodus.util.UTFUtil
import mu.KLogging
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.*

private fun createChangesTracker(
    readonly: Boolean
): TransientChangesTracker {
    return if (readonly) ReadOnlyTransientChangesTrackerImpl() else TransientChangesTrackerImpl()
}

internal const val CHILD_TO_PARENT_LINK_NAME = "__CHILD_TO_PARENT_LINK_NAME__"
internal const val PARENT_TO_CHILD_LINK_NAME = "__PARENT_TO_CHILD_LINK_NAME__"

class TransientSessionImpl(
    private val store: TransientEntityStoreImpl,
    private var readonly: Boolean,
) : TransientStoreSession, SessionQueryMixin {
    companion object : KLogging()

    init {
        if (store.modelMetaData?.entitiesMetaData?.firstOrNull() == null) {
            logger.warn { "model MetaData is not set for store ${store.persistentStore.location}." }
        }
    }


    private var upgradeHook: Runnable? = null
    private var state = State.Open

    private var quietFlush = false
    private var loadedIds: EntityIdSet = EntityIdSetFactory.newSet()
    private val hashCode = (Math.random() * Integer.MAX_VALUE).toInt()
    private var allowRunnables = true

    val stack = if (TransientEntityStoreImpl.logger.isDebugEnabled) Throwable() else null

    private var flushing = false

    override val isOpened: Boolean
        get() = state == State.Open

    override val isCommitted: Boolean
        get() = state == State.Committed

    override val isAborted: Boolean
        get() = state == State.Aborted

    override var transactionInternal: StoreTransaction = this.store.persistentStore.beginTransaction()
        get() {
            assertOpen("get persistent transaction")
            return field
        }
        private set

    private var changesTracker = createChangesTracker(readonly = this.readonly)

    override val entitiesUpdater = TransientEntitiesUpdaterImpl(this)

    override val transientChangesTracker: TransientChangesTracker
        get() {
            assertOpen("get changes tracker")
            return changesTracker
        }

    private val persistentStore: PersistentEntityStore
        get() = store.persistentStore

    private fun initChangesTracker(readonly: Boolean) {
        transientChangesTracker.dispose()
        this.changesTracker = createChangesTracker(readonly)
    }

    override fun getQueryCancellingPolicy(): QueryCancellingPolicy? {
        return transactionInternal.queryCancellingPolicy
    }

    override fun setQueryCancellingPolicy(policy: QueryCancellingPolicy?) {
        transactionInternal.queryCancellingPolicy = policy
    }

    override fun getStore(): TransientEntityStore = store

    override fun isIdempotent(): Boolean {
        return transactionInternal.isIdempotent && changesTracker.changedEntities.isEmpty()
    }

    override fun isReadonly() = readonly

    override fun isFinished() = transactionInternal.isFinished

    override fun setUpgradeHook(hook: Runnable?) {
        upgradeHook = hook
    }

    internal fun upgradeReadonlyTransactionIfNecessary() {
        if (readonly) {
            readonly = false
            val persistentStore = persistentStore
            if (!persistentStore.environment.environmentConfig.envIsReadonly) {
                upgradeHook?.run()
//                persistentStore.registerTransaction(newTxn)
                changesTracker = this.transientChangesTracker.upgrade()
            } else {
                throw ReadonlyTransactionException()
            }
        }
    }

    override fun toString(): String {
        return "transaction [${hashCode()}] state [$state]"
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun equals(other: Any?) = other === this

    override fun hasChanges() = entitiesUpdater.hasChanges()

    internal fun assertOpen(action: String) {
        if (state != State.Open) throw IllegalStateException("Cannot $action in state [$state]")
    }

    override fun createPersistentEntityIterableWrapper(wrappedIterable: EntityIterable): EntityIterable {
        assertOpen("create wrapper")
        // do not wrap twice
        return when (wrappedIterable) {
            is PersistentEntityIterableWrapper -> wrappedIterable
            else -> PersistentEntityIterableWrapper(store, wrappedIterable)
        }
    }

    override fun revert() {
        logger.debug("Revert transient session {}", this)
        assertOpen("revert")

        if (!transactionInternal.isReadonly) {
            loadedIds = EntityIdSetFactory.newSet()
            entitiesUpdater.clear()
        }
        closePersistentSession()
        this.store.persistentStore.beginReadonlyTransaction()
        initChangesTracker(readonly = true)
    }

    override fun flush(): Boolean {
        if (store.threadSession !== this) throw IllegalStateException("Cannot commit session from another thread")


        logger.debug("Intermediate commit transient session {}", this)

        assertOpen("flush")

        if (!entitiesUpdater.hasChanges()) {
            logger.trace("Nothing to flush")
        } else {
            flushChanges()
            entitiesUpdater.clear()

            loadedIds = EntityIdSetFactory.newSet()

            val oldChangesTracker = transientChangesTracker
            closePersistentSession()
            this.transactionInternal = this.store.persistentStore.beginTransaction()
            this.changesTracker = ReadOnlyTransientChangesTrackerImpl()
            //all changes were already flushed
            this.readonly = true
            notifyFlushedListeners(oldChangesTracker)
        }
        return true
    }


    /**
     * Flushes changes
     */
    private fun flushChanges() {
        if (flushing) throw IllegalStateException("Transaction is already being flushed!")

        try {
            flushing = true
            beforeFlush()
            checkBeforeSaveChangesConstraints()

            if (this.isIdempotent) return

            try {
                while (true) {
                    try {
                        performDeferredEntitiesDeletion()
                        if (transactionInternal.flush()) {
                            return
                        }
                    } catch (_: ONeedRetryException) {
                        // replay changes
                        transactionInternal = this.store.persistentStore.beginTransaction()
                        replayChanges()
                        //recheck constraints
                        checkBeforeSaveChangesConstraints()
                    }
                }

            } catch (exception: Throwable) {
                logger.info { "Catch exception in flush: ${exception.message}" }

                if (exception is DataIntegrityViolationException) {
                    transactionInternal.revert()
                    replayChanges()
                }
                if (exception is ORecordDuplicatedException) {
                    val fieldName = exception.indexName.substringAfter("_").substringBefore("_unique")
                    val cause = SimplePropertyValidationException(
                        "Not unique value",
                        exception.message ?: "Not unique value: ${exception.key}",
                        null,
                        fieldName
                    )
                    throw ConstraintsValidationException(cause)
                }

                throw exception
            }

        } finally {
            flushing = false
        }
    }

    override fun commit(): Boolean {
        // flush until no side effects from listeners
        do {
            flush()
        } while (entitiesUpdater.hasChanges())

        try {
            transientChangesTracker.dispose()
        } finally {
            try {
                closePersistentSession()
            } finally {
                store.unregisterStoreSession(this)
                state = State.Committed
            }
        }
        return true
    }

    override fun abort() {
        if (store.threadSession !== this)
            throw IllegalStateException("Cannot abort session that is not current thread session. Current thread session is [${store.threadSession}]")

        logger.debug("Abort transient session {}", this)

        assertOpen("abort")
        try {
            transientChangesTracker.dispose()
        } finally {
            try {
                closePersistentSession()
            } finally {
                store.unregisterStoreSession(this)
                state = State.Aborted
            }
        }
    }


    /**
     * Creates transient wrapper for existing persistent entity
     */
    override fun newEntity(persistentEntity: Entity): TransientEntity {
        assertOpen("create entity")
        return newEntityImpl(persistentEntity).also { addLoadedId(persistentEntity.id) }
    }

    override fun getEntity(id: EntityId): Entity {
        assertOpen("get entity")
        if (id in loadedIds) {
            return newEntityImpl(persistentStore.getEntity(id))
        }
        return newEntityImpl(transactionInternal.getEntity(id).also {
            addLoadedId(id)
        })
    }

    override fun isCurrent(): Boolean {
        return transactionInternal.isCurrent
    }

    override fun findWithPropSortedByValue(entityType: String, propertyName: String): EntityIterable {
        return transactionInternal.findWithPropSortedByValue(entityType, propertyName)
    }

    override fun mergeSorted(
        sorted: MutableList<EntityIterable>,
        valueGetter: ComparableGetter,
        comparator: Comparator<Comparable<Any>>
    ): EntityIterable {
        return transactionInternal.mergeSorted(sorted, valueGetter, comparator)
    }

    override fun getEnvironmentTransaction(): Transaction {
        return transactionInternal.environmentTransaction
    }

    override fun toEntityId(representation: String): EntityId {
        assertOpen("convert to entity id")
        return transactionInternal.toEntityId(representation)
    }

    override fun getEntityTypes(): List<String> {
        assertOpen("get entity types")
        return transactionInternal.entityTypes
    }

    override fun wrap(action: String, entityIterable: EntityIterable): EntityIterable {
        assertOpen(action)
        return PersistentEntityIterableWrapper(store, entityIterable)
    }

    override fun getSequence(sequenceName: String): Sequence {
        assertOpen("get sequence")
        return transactionInternal.getSequence(sequenceName)
    }

    override fun getSequence(sequenceName: String, initialValue: Long): Sequence {
        assertOpen("get sequence")
        return transactionInternal.getSequence(sequenceName, initialValue)
    }

    private fun closePersistentSession() {
        logger.debug("Close persistent session for transient session {}", this)

        store.persistentStore.currentTransaction?.abort()
    }

    override fun quietIntermediateCommit() {
        val quietFlush = quietFlush
        try {
            this.quietFlush = true
            flush()
        } finally {
            this.quietFlush = quietFlush
        }
    }


    private fun replayChanges() {
        initChangesTracker(readonly = false)
        // some of the managed entities could be deleted
        loadedIds = EntityIdSetFactory.newSet()
        entitiesUpdater.apply()
        changesTracker.changesDescription.filter {
            it.changeType == EntityChangeType.REMOVE
        }.map {
            it.transientEntity
        }.forEach { txnEntity ->
            val processed = HashSet<Entity>()
            val modelMetaData = store.modelMetaData!!
            val entityMetaData = modelMetaData.getEntityMetaData(txnEntity.type)
            if (entityMetaData != null) {
                processed.add(txnEntity)
                // remove associations and cascade delete
                val storeSession = store.threadSessionOrThrow
                ConstraintsUtil.processOnDeleteConstraints(storeSession, txnEntity, entityMetaData, modelMetaData, false, processed)
            }
        }
    }

    /**
     * Removes orphans (entities without parents) or returns OrphanException to throw later.
     */
    private fun removeOrphans(): MutableSet<DataIntegrityViolationException> {
        val orphans = HashSetDecorator<DataIntegrityViolationException>()
        val modelMetaData = store.modelMetaData ?: return orphans

        transientChangesTracker.changedEntities
            .toList()
            .asSequence()
            .filter { !it.isRemoved }
            .mapNotNull { changedEntity ->
                modelMetaData.getEntityMetaData(changedEntity.type)
                    ?.let { entityMetaData -> changedEntity to entityMetaData }
            }
            .forEach { (changedEntity, entityMetaData) ->
                if (entityMetaData.hasAggregationChildEnds() && !EntityMetaDataUtils.hasParent(
                        entityMetaData,
                        changedEntity,
                        transientChangesTracker
                    )
                ) {
                    if (entityMetaData.removeOrphan) {
                        // has no parent - remove
                        logger.debug("Remove orphan: {}", changedEntity)

                        // we don't want this change to be repeated on flush
                        entitiesUpdater.deleteEntityInternal(changedEntity)
                    } else {
                        // has no parent, but orphans shouldn't be removed automatically - exception
                        orphans.add(OrphanChildException(changedEntity, entityMetaData.aggregationChildEnds))
                    }
                }
            }

        return orphans
    }

    /**
     * Creates new transient entity
     */
    override fun newEntity(entityType: String): TransientEntity {
        assertOpen("create entity")
        assertIsNotAbstract(entityType)
        upgradeReadonlyTransactionIfNecessary()
        return TransientEntityImpl(entityType, getStore())
    }

    private fun assertIsNotAbstract(entityType: String) {
        store.modelMetaData?.let {
            it.getEntityMetaData(entityType)?.let {
                if (it.isAbstract) throw IllegalStateException("Can't instantiate abstract entity type '$entityType'")
            }
        }
    }

    override fun newEntity(creator: EntityCreator): TransientEntity {
        val found = creator.find()
        return if (found != null) {
            addEntityCreator(found as TransientEntityImpl, creator)
            found
        } else {
            upgradeReadonlyTransactionIfNecessary()
            TransientEntityImpl(creator, getStore())
        }
    }

    /**
     * Creates local copy of given entity in current session.
     *
     * @param entity
     * @return
     */
    override fun newLocalCopy(entity: TransientEntity): TransientEntity {
        assertOpen("create local copy")
        val tracker = transientChangesTracker
        return when {
            entity.isReadonly || entity.isWrapper -> entity
            tracker.isRemoved(entity) -> {
                logger.warn { "Entity [$entity] was removed by you." }
                throw EntityRemovedException(entity)
            }

            tracker.isNew(entity) -> entity
            else -> {
                val entityId = entity.id
                if (entityId in loadedIds) {
                    return entity
                }
                try {
                    // load persistent entity from database by id
                    newEntityImpl(transactionInternal.getEntity(entityId))
                } catch (e: EntityRemovedInDatabaseException) {
                    logger.warn { "Entity [$entity] was removed in database, can't create local copy" }
                    throw e
                }
            }
        }
    }

    /**
     * Checks if entity was removed in this transaction or in database
     *
     * @param entity
     * @return true if e was removed, false if it wasn't removed at all
     */
    override fun isRemoved(entity: Entity): Boolean {
        if (entity is TransientEntity && state == State.Open) {
            if (entity.isWrapper) {
                return entity.isRemoved
            }
            if (transientChangesTracker.isRemoved(entity)) {
                return true
            } else if (entity.isReadonly || transientChangesTracker.isNew(entity)) {
                return false
            }
        }
        return try {
            transactionInternal.getEntity(entity.id)
            return false
        } catch (e: Throwable) {
            return true
        }
    }

    private fun addLoadedId(id: EntityId) {
        loadedIds = loadedIds.add(id)
    }

    /**
     * Checks constraints before save changes
     */
    private fun checkBeforeSaveChangesConstraints() {
        // 0. remove orphans
        val exceptions = removeOrphans()

        val modelMetaData = store.modelMetaData

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            logger.warn { "Quiet intermediate commit: skip before save changes constraints checking. $this" }
            return
        }

        logger.trace { "Check before save changes constraints. $this" }

        // 1. check incoming links for deleted entities
        exceptions.addAll(ConstraintsUtil.checkIncomingLinks(transientChangesTracker))

        // 2. check associations cardinality
        exceptions.addAll(ConstraintsUtil.checkAssociationsCardinality(transientChangesTracker, modelMetaData))

        // 3. check required properties
        exceptions.addAll(ConstraintsUtil.checkRequiredProperties(transientChangesTracker, modelMetaData))

        // 4. check other property constraints
        exceptions.addAll(ConstraintsUtil.checkOtherPropertyConstraints(transientChangesTracker, modelMetaData))

        // 5. check index fields
        exceptions.addAll(ConstraintsUtil.checkIndexFields(transientChangesTracker, modelMetaData))

        if (exceptions.isNotEmpty()) {
            forAllListeners { it.afterConstraintsFail(this, exceptions) }
            throw ConstraintsValidationException(exceptions)
        }
    }

    /**
     * Checks custom flush constraints before save changes
     */
    private fun executeBeforeFlushTriggers(changedEntities: Set<TransientEntity>) {
        val modelMetaData = store.modelMetaData

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            logger.warn("Quiet intermediate commit: skip before flush triggers. $this")
            return
        }

        logger.debug { "Execute before flush triggers. $this" }

        val exceptions = changedEntities
            .asSequence()
            .filter { !it.isRemoved }
            .flatMap { entity ->
                try {
                    entity.lifecycle?.onBeforeFlush(entity)
                    emptySequence<DataIntegrityViolationException>()
                } catch (cve: ConstraintsValidationException) {
                    cve.causes.asSequence()
                }
            }
            .toCollection(HashSetDecorator())

        if (exceptions.isNotEmpty()) {
            throw ConstraintsValidationException(exceptions)
        }
    }

    private fun performDeferredEntitiesDeletion() {
        changesTracker.getRemovedEntitiesIds().forEach {
            //TODO optimize it. We do not need to load entity to remove it
            persistentStore.currentTransaction?.getEntity(it)?.delete()
        }
    }

    override fun getSnapshot(): OStoreTransaction {
        throw UnsupportedOperationException()
    }

    internal fun getOriginalPropertyValue(e: TransientEntity, propertyName: String): Comparable<*>? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        val onLoadValue = oVertex.getPropertyOnLoadValue<Any>(propertyName)
        return if (onLoadValue is MutableSet<*>) {
            OComparableSet(onLoadValue)
        } else {
            onLoadValue as Comparable<*>?
        }
    }

    internal fun getOriginalBlobStringValue(e: TransientEntity, blobName: String): String? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        val blobHolder = oVertex.getPropertyOnLoadValue<ORecordBytes?>(blobName)
        return blobHolder?.toStream()?.let {
            UTFUtil.readUTF((it).inputStream())
        }
    }

    internal fun getOriginalBlobValue(e: TransientEntity, blobName: String): InputStream? {
        val session = ODatabaseSession.getActiveSession()
        val id = e.entity.id.asOId()
        val oVertex = session.load<OVertex>(id)
        val blobHolder = oVertex.getPropertyOnLoadValue<ORecordBytes?>(blobName)
        return ByteArrayInputStream(blobHolder.toStream())
    }

    private fun getOriginalLinkValue(e: TransientEntity, linkName: String): Comparable<*>? {
        // get from saved changes, if not - from db
        val change = transientChangesTracker.getChangedLinksDetailed(e)?.get(linkName)
        if (change != null) {
            when (change.changeType) {
                LinkChangeType.ADD_AND_REMOVE,
                LinkChangeType.REMOVE -> {
                    return if (change.removedEntitiesSize != 1) {
                        if (change.deletedEntitiesSize == 1) {
                            change.deletedEntities!!.iterator().next()
                        } else {
                            throw IllegalStateException("Can't determine original link value: ${e.type}.$linkName")
                        }
                    } else {
                        change.removedEntities!!.iterator().next()
                    }
                }

                else ->
                    throw IllegalStateException("Incorrect change type for link that is part of index: ${e.type}.$linkName: ${change.changeType.getName()}")
            }
        }

        return e.entity.getLink(linkName)
    }

    private fun beforeFlush() {
        // notify listeners, execute before flush, if were side effects, do the same for side effects
        var changesDescription = transientChangesTracker.changesDescription
        if (transientChangesTracker.changedEntities.isNotEmpty()) {
            val processedEntities = HashSetDecorator<TransientEntity>()
            var changed: Set<TransientEntity> = HashSet(transientChangesTracker.changedEntities)
            while (true) {
                val changesSize = transientChangesTracker.changedEntities.size
                notifyBeforeFlushListeners(Collections.unmodifiableSet(changesDescription))
                executeBeforeFlushTriggers(changed)
                if (changesSize == transientChangesTracker.changedEntities.size) break

                processedEntities.addAll(changed)
                changed = transientChangesTracker.changedEntities - processedEntities
                if (changed.isEmpty()) break

                changesDescription = changed.asSequence()
                    .map { transientChangesTracker.getChangeDescription(it) }
                    .toSet()
            }
        }
    }

    private fun notifyFlushedListeners(oldChangesTracker: TransientChangesTracker) {
        val changesDescription = Collections.unmodifiableSet(oldChangesTracker.changesDescription)

        if (changesDescription.isEmpty()) {
            oldChangesTracker.dispose()
            return
        }

        logger.debug { "Notify flushed listeners $this" }

        forAllListeners { it.flushed(this, changesDescription) }// TODO May be we remove it? It's not used

        val ep = store.changesMultiplexer
        if (ep != null) {
            try {
                ep.flushed(this, oldChangesTracker, changesDescription)
            } catch (e: Exception) {
                logger.error("Exception while inside events multiplexer", e)
                oldChangesTracker.dispose()
            }
        }

        oldChangesTracker.dispose()
    }


    private fun notifyBeforeFlushListeners(changes: Set<TransientEntityChange>?) {
        if (changes == null || changes.isEmpty()) return

        logger.debug { "Notify before flush listeners $this" }
        forAllListeners(rethrowException = true) { it.beforeFlushBeforeConstraints(this, changes) }
    }

    private fun newEntityImpl(persistent: Entity): TransientEntity {
        return if (persistent is OReadonlyVertexEntity) {
            ReadonlyTransientEntityImpl(persistent, store)
        } else {
            TransientEntityImpl(persistent as OEntity, getStore())
        }
    }

    internal fun createEntity(transientEntity: TransientEntityImpl, type: String) {
        val persistentEntity = transactionInternal.newEntity(type) as OEntity
        transientEntity.entity = persistentEntity
        addLoadedId(persistentEntity.id)
        transientChangesTracker.entityAdded(transientEntity)
        addChange { saveEntityInternal(persistentEntity, transientEntity) }
    }

    private fun saveEntityInternal(persistentEntity: OEntity, e: TransientEntityImpl): Boolean {
        transactionInternal.saveEntity(persistentEntity)
        addLoadedId(persistentEntity.id)
        transientChangesTracker.entityAdded(e)
        return true
    }

    internal fun createEntity(transientEntity: TransientEntityImpl, creator: EntityCreator) {
        val persistentEntity = transactionInternal.newEntity(creator.type) as OEntity
        transientEntity.entity = persistentEntity
        addChange {
            val found = creator.find()
            if (found == null) {
                val result = saveEntityInternal(persistentEntity, transientEntity)
                try {
                    allowRunnables = false
                    creator.created(transientEntity)
                } finally {
                    allowRunnables = true
                }
                result
            } else {
                transientEntity.entity = (found as TransientEntityImpl).entity
                false
            }
        }
        addLoadedId(persistentEntity.id)
        transientChangesTracker.entityAdded(transientEntity)
        try {
            allowRunnables = false
            creator.created(transientEntity)
        } finally {
            allowRunnables = true
        }
    }

    private fun addEntityCreator(transientEntity: TransientEntityImpl, creator: EntityCreator) {
        addChange {
            val found = creator.find()
            if (found != null) {
                if (found != transientEntity) {
                    // update existing entity
                    transientEntity.entity = (found as TransientEntityImpl).entity
                }
                false
            } else {
                upgradeReadonlyTransactionIfNecessary()
                // somebody deleted our (initially found) entity! we need to create some again
                transientEntity.entity = transactionInternal.newEntity(creator.type) as OEntity
                transientChangesTracker.entityAdded(transientEntity)
                try {
                    allowRunnables = false
                    creator.created(transientEntity)
                } finally {
                    allowRunnables = true
                }
                true
            }
        }
    }

    fun getParent(child: TransientEntity): Entity? {
        val childToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME) as String?
            ?: return null
        return child.getLink(childToParentLinkName)
    }

    override fun saveEntity(entity: Entity) {
        throw UnsupportedOperationException()
    }

    internal fun newLocalCopySafe(entity: TransientEntity?): TransientEntity? {
        if (entity == null) {
            return null
        }
        return try {
            newLocalCopy(entity)
        } catch (ignore: EntityRemovedInDatabaseException) {
            null
        }
    }

    private fun addChange(change: () -> Boolean): () -> Boolean {
        if (allowRunnables) {
            entitiesUpdater.addChange(change)
        }
        return change
    }

    internal enum class State {
        Open,
        Committed,
        Aborted
    }

    private fun forAllListeners(rethrowException: Boolean = false, event: (TransientStoreSessionListener) -> Unit) {
        store.forAllListeners { listener ->
            try {
                event(listener)
            } catch (e: Exception) {
                if (rethrowException) {
                    throw e
                } else {
                    logger.error(e) { "Exception inside listener [$listener]" }
                    if (e is TransactionFinishedException && e.trace != null) {
                        logger.error { "Transaction was early finished inside listener: ${e.trace}" }
                    }
                }
            }
        }

    }
}
