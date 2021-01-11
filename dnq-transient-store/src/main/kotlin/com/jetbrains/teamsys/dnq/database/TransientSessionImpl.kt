/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import jetbrains.exodus.ByteIterable
import jetbrains.exodus.ExodusException
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator
import jetbrains.exodus.core.dataStructures.decorators.QueueDecorator
import jetbrains.exodus.database.*
import jetbrains.exodus.database.exceptions.*
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.iterate.EntityIdSet
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import jetbrains.exodus.env.TransactionFinishedException
import jetbrains.exodus.query.metadata.EntityMetaData
import jetbrains.exodus.query.metadata.Index
import mu.KLogging
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.collections.HashSet
import kotlin.concurrent.withLock

private fun PersistentStoreTransaction.createChangesTracker(readonly: Boolean): TransientChangesTracker {
    return if (readonly) ReadOnlyTransientChangesTrackerImpl(this) else TransientChangesTrackerImpl(this)
}

private const val CHILD_TO_PARENT_LINK_NAME = "__CHILD_TO_PARENT_LINK_NAME__"
private const val PARENT_TO_CHILD_LINK_NAME = "__PARENT_TO_CHILD_LINK_NAME__"

class TransientSessionImpl(private val store: TransientEntityStoreImpl, private val readonly: Boolean) : TransientStoreSession, SessionQueryMixin {

    companion object : KLogging() {
        private val assertLinkTypes = "true" == System.getProperty("xodus.dnq.links.assertTypes", "true")
    }

    init {
        if (store.modelMetaData?.entitiesMetaData?.firstOrNull() == null) {
            logger.warn { "model MetaData is not set for store ${store.persistentStore.location}." }
        }
        this.store.persistentStore.beginReadonlyTransaction()
    }

    private var txnWhichWasUpgraded: ReadonlyPersistentStoreTransaction? = null
    private var upgradeHook: Runnable? = null
    private var state = State.Open

    private var quietFlush = false
    private var loadedIds: EntityIdSet = EntityIdSetFactory.newSet()
    private val changes = QueueDecorator<() -> Boolean>()
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

    override val persistentTransactionInternal: PersistentStoreTransaction
        get() = store.persistentStore.currentTransactionOrThrow

    override val persistentTransaction: PersistentStoreTransaction
        get() {
            assertOpen("get persistent transaction")
            return persistentTransactionInternal
        }

    private var changesTracker = snapshot.createChangesTracker(readonly = true)
    override val transientChangesTracker: TransientChangesTracker
        get() {
            assertOpen("get changes tracker")
            return changesTracker
        }

    private val persistentStore: PersistentEntityStoreImpl
        get() = store.persistentStore as PersistentEntityStoreImpl

    private fun initChangesTracker(readonly: Boolean) {
        transientChangesTracker.dispose()
        this.changesTracker = snapshot.createChangesTracker(readonly)
    }

    override fun getQueryCancellingPolicy(): QueryCancellingPolicy? {
        return persistentTransactionInternal.queryCancellingPolicy
    }

    override fun setQueryCancellingPolicy(policy: QueryCancellingPolicy?) {
        persistentTransactionInternal.queryCancellingPolicy = policy
    }

    override fun getStore(): TransientEntityStore = store

    override fun isIdempotent() = persistentTransaction.isIdempotent

    override fun isReadonly() = readonly

    override fun isFinished() = persistentTransaction.isFinished

    override fun setUpgradeHook(hook: Runnable?) {
        upgradeHook = hook
    }

    private fun upgradeReadonlyTransactionIfNecessary() {
        val currentTxn = persistentTransactionInternal
        if (!readonly && currentTxn.isReadonly) {
            val persistentStore = persistentStore
            if (!persistentStore.environment.environmentConfig.envIsReadonly) {
                upgradeHook?.run()
                val roTxn = currentTxn as ReadonlyPersistentStoreTransaction
                val newTxn = roTxn.upgradedTransaction
                persistentStore.registerTransaction(newTxn)
                newTxn.queryCancellingPolicy = roTxn.queryCancellingPolicy
                changesTracker = this.transientChangesTracker.upgrade()
                txnWhichWasUpgraded = roTxn
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

    override fun hasChanges() = changes.isNotEmpty()

    private fun assertOpen(action: String) {
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
        logger.debug("Revert transient session ${this}")
        assertOpen("revert")

        if (!persistentTransactionInternal.isReadonly) {
            loadedIds = EntityIdSetFactory.newSet()
            changes.clear()
        }
        closePersistentSession()
        this.store.persistentStore.beginReadonlyTransaction()
        initChangesTracker(readonly = true)
    }

    override fun flush(): Boolean {
        if (store.threadSession !== this) throw IllegalStateException("Cannot commit session from another thread")


        logger.debug("Intermediate commit transient session ${this}")

        assertOpen("flush")

        if (changes.isEmpty()) {
            logger.trace("Nothing to flush")
        } else {
            flushChanges()
            changes.clear()

            loadedIds = EntityIdSetFactory.newSet()

            val oldChangesTracker = transientChangesTracker
            closePersistentSession()
            this.store.persistentStore.beginReadonlyTransaction()
            this.changesTracker = ReadOnlyTransientChangesTrackerImpl(snapshot)
            notifyFlushedListeners(oldChangesTracker)
        }
        return true
    }

    override fun commit(): Boolean {
        // flush until no side-effects from listeners
        do {
            flush()
        } while (!changes.isEmpty())

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

        logger.debug("Abort transient session ${this}")

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
        if (persistentEntity !is PersistentEntity)
            throw IllegalArgumentException("Cannot create transient entity wrapper for non persistent entity")
        assertOpen("create entity")
        return newEntityImpl(persistentEntity).also { addLoadedId(persistentEntity.id) }
    }

    override fun getEntity(id: EntityId): Entity {
        assertOpen("get entity")
        if (id in loadedIds) {
            return newEntityImpl(persistentStore.getEntity(id))
        }
        return newEntityImpl(transientChangesTracker.snapshot.getEntity(id).also {
            addLoadedId(id)
        })
    }

    override fun toEntityId(representation: String): EntityId {
        assertOpen("convert to entity id")
        return persistentTransactionInternal.toEntityId(representation)
    }

    override fun getEntityTypes(): List<String> {
        assertOpen("get entity types")
        return persistentTransactionInternal.entityTypes
    }

    override fun wrap(action: String, entityIterable: EntityIterable): EntityIterable {
        assertOpen(action)
        return PersistentEntityIterableWrapper(store, entityIterable)
    }

    override fun getSequence(sequenceName: String): Sequence {
        assertOpen("get sequence")
        return persistentTransactionInternal.getSequence(sequenceName)
    }

    override fun getSequence(sequenceName: String, initialValue: Long): Sequence {
        assertOpen("get sequence")
        return persistentTransactionInternal.getSequence(sequenceName, initialValue)
    }

    private fun closePersistentSession() {
        logger.debug("Close persistent session for transient session ${this}")

        store.persistentStore.currentTransaction?.abort()
        txnWhichWasUpgraded?.abort()
        txnWhichWasUpgraded = null
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
        // some of managed entities could be deleted
        loadedIds = EntityIdSetFactory.newSet()
        changes.forEach { it() }
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
                    if (entityMetaData.hasAggregationChildEnds() && !EntityMetaDataUtils.hasParent(entityMetaData, changedEntity, transientChangesTracker)) {
                        if (entityMetaData.removeOrphan) {
                            // has no parent - remove
                            logger.debug("Remove orphan: $changedEntity")

                            // we don't want this change to be repeated on flush
                            deleteEntityInternal(changedEntity)
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
                    newEntity(persistentTransactionInternal.getEntity(entityId)).also {
                        addLoadedId(entityId)
                    }
                } catch (e: EntityRemovedInDatabaseException) {
                    logger.warn { "Entity [$entity] was removed in database, can't create local copy" }
                    throw e
                }
            }
        }
    }

    /**
     * Checks if entity entity was removed in this transaction or in database
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
        return persistentStore.getLastVersion(persistentTransactionInternal, entity.id) < 0
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
            logger.warn { "Quiet intermediate commit: skip before save changes constraints checking. ${this}" }
            return
        }

        logger.trace { "Check before save changes constraints. ${this}" }

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

    /**
     * Flushes changes
     */
    private fun flushChanges() {
        if (flushing) throw IllegalStateException("Transaction is already being flushed!")

        try {
            flushing = true
            beforeFlush()
            checkBeforeSaveChangesConstraints()

            val txn = persistentTransactionInternal
            if (txn.isIdempotent) return

            try {
                prepare()
                store.flushLock.withLock {
                    while (true) {
                        if (txn.flush()) {
                            return
                        }
                        // replay changes
                        replayChanges()
                        //recheck constraints against new database root
                        checkBeforeSaveChangesConstraints()
                        prepare()
                    }
                }
            } catch (exception: Throwable) {
                logger.info { "Catch exception in flush: ${exception.message}" }

                if (exception is DataIntegrityViolationException) {
                    txn.revert()
                    replayChanges()
                }
                throw exception
            }

        } finally {
            flushing = false
        }
    }

    private fun prepare() {
        try {
            allowRunnables = false
            flushIndexes()
        } finally {
            allowRunnables = true
        }

        logger.trace("Flush persistent transaction in transient session ${this}")

    }

    override fun getSnapshot(): PersistentStoreTransaction {
        return persistentTransaction.snapshot
    }

    private fun flushIndexes() {
        if (TransientStoreUtil.isPostponeUniqueIndexes) return

        val uniqueKeyDeletions = ArrayList<Pair<TransientEntity, Index>>()
        val uniqueKeyInsertions = ArrayList<Pair<TransientEntity, Index>>()

        transientChangesTracker.changedEntities
                .filter { !it.isRemoved }
                .forEach { changedEntity ->
                    val entityMetaData = getEntityMetaData(changedEntity)
                    if (entityMetaData != null) {
                        // create/update
                        val changedPropertyNames = transientChangesTracker.getChangedProperties(changedEntity).orEmpty()
                        val changedLinkNames = transientChangesTracker.getChangedLinksDetailed(changedEntity).orEmpty()
                        (changedPropertyNames.asSequence() + changedLinkNames.keys.asSequence())
                                .flatMap { propertyName -> entityMetaData.getIndexes(propertyName).asSequence() }
                                .toCollection(HashSetDecorator())
                                .forEach { index ->
                                    val entry = Pair(changedEntity, index)
                                    if (!changedEntity.isNew) {
                                        uniqueKeyDeletions.add(entry)
                                    }
                                    uniqueKeyInsertions.add(entry)
                                }
                    }
                }

        val persistentTransaction = persistentTransaction
        val ukiEngine = store.queryEngine.uniqueKeyIndicesEngine

        for (deletion in uniqueKeyDeletions) {
            val e = deletion.first
            val index = deletion.second
            val originalValues = getIndexFieldsOriginalValues(e, index)
            // ignore null values; work around for JT-43108
            if (!originalValues.contains(null)) {
                ukiEngine.deleteUniqueKey(persistentTransaction, index, originalValues)
            }
        }

        for ((e, index) in uniqueKeyInsertions) {
            try {
                ukiEngine.insertUniqueKey(persistentTransaction, index, getIndexFieldsFinalValues(e, index), e)
            } catch (ex: ExodusException) {
                throw ConstraintsValidationException(UniqueIndexViolationException(e, index))
            }

        }
    }

    private fun getIndexesValuesBeforeDelete(e: TransientEntity): Set<Pair<Index, List<Comparable<*>?>>> {
        if (transientChangesTracker.isNew(e)) return emptySet()
        val entityMetaData = getEntityMetaData(e) ?: return emptySet()
        return entityMetaData.indexes
                .map { index -> index to getIndexFieldsOriginalValues(e, index) }
                .toSet()
    }

    private fun deleteIndexes(e: TransientEntity, indexes: Set<Pair<Index, List<Comparable<*>?>>>) {
        if (indexes.isEmpty()) return

        val persistentTransaction = persistentTransaction
        val ukiEngine = store.queryEngine.uniqueKeyIndicesEngine
        for ((index, propertyValues) in indexes) {
            try {
                ukiEngine.deleteUniqueKey(persistentTransaction, index, propertyValues)
            } catch (ex: ExodusException) {
                throw ConstraintsValidationException(UniqueIndexIntegrityException(e, index, ex))
            }
        }
    }

    private fun getIndexFieldsOriginalValues(e: TransientEntity, index: Index): List<Comparable<*>?> {
        return index.fields.map { field ->
            if (field.isProperty) {
                getOriginalPropertyValue(e, field.name)
            } else {
                getOriginalLinkValue(e, field.name)
            }
        }
    }

    private fun getOriginalPropertyValue(e: TransientEntity, propertyName: String): Comparable<*>? {
        return e.persistentEntity.getSnapshot(transientChangesTracker.snapshot).getProperty(propertyName)
    }

    private fun getOriginalRawPropertyValue(e: TransientEntity, propertyName: String): ByteIterable? {
        return e.persistentEntity.getSnapshot(transientChangesTracker.snapshot).getRawProperty(propertyName)
    }

    private fun getOriginalBlobStringValue(e: TransientEntity, blobName: String): String? {
        return e.persistentEntity.getSnapshot(transientChangesTracker.snapshot).getBlobString(blobName)
    }

    private fun getOriginalBlobValue(e: TransientEntity, blobName: String): InputStream? {
        return e.persistentEntity.getSnapshot(transientChangesTracker.snapshot).getBlob(blobName)
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

        return e.persistentEntity.getSnapshot(transientChangesTracker.snapshot).getLink(linkName)
    }

    private fun getIndexFieldsFinalValues(e: TransientEntity, index: Index): List<Comparable<*>?> {
        return index.fields.map { field ->
            if (field.isProperty) {
                e.getProperty(field.name)
            } else {
                e.getLink(field.name)
            }
        }
    }

    private fun getEntityMetaData(e: TransientEntity): EntityMetaData? {
        return store.modelMetaData?.getEntityMetaData(e.type)
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

        forAllListeners { it.flushed(this, changesDescription) }

        //explicitly notify EventsMultiplexer - it will dispose changes tracker in async job
        val ep = store.changesMultiplexer
        if (ep != null) {
            try {
                ep.flushed(this, oldChangesTracker, changesDescription)
            } catch (e: Exception) {
                logger.error("Exception while inside events multiplexer", e)
                oldChangesTracker.dispose()
            }
        } else {
            oldChangesTracker.dispose()
        }
    }

    private fun notifyBeforeFlushListeners(changes: Set<TransientEntityChange>?) {
        if (changes == null || changes.isEmpty()) return

        logger.debug { "Notify before flush listeners $this" }
        forAllListeners(rethrowException = true) { it.beforeFlushBeforeConstraints(this, changes) }
    }

    private fun newEntityImpl(persistent: PersistentEntity): TransientEntity {
        return if (persistent is ReadOnlyPersistentEntity) {
            ReadonlyTransientEntityImpl(persistent, store)
        } else {
            TransientEntityImpl(persistent, getStore())
        }
    }

    internal fun createEntity(transientEntity: TransientEntityImpl, type: String) {
        val persistentEntity = persistentTransaction.newEntity(type)
        transientEntity.persistentEntity = persistentEntity
        addLoadedId(persistentEntity.id)
        transientChangesTracker.entityAdded(transientEntity)
        addChange { saveEntityInternal(persistentEntity, transientEntity) }
    }

    private fun saveEntityInternal(persistentEntity: PersistentEntity, e: TransientEntityImpl): Boolean {
        persistentTransaction.saveEntity(persistentEntity)
        addLoadedId(persistentEntity.id)
        transientChangesTracker.entityAdded(e)
        return true
    }

    internal fun createEntity(transientEntity: TransientEntityImpl, creator: EntityCreator) {
        val persistentEntity = persistentTransaction.newEntity(creator.type)
        transientEntity.persistentEntity = persistentEntity
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
                transientEntity.persistentEntity = (found as TransientEntityImpl).persistentEntity
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
                    transientEntity.persistentEntity = (found as TransientEntityImpl).persistentEntity
                }
                false
            } else {
                upgradeReadonlyTransactionIfNecessary()
                // somebody deleted our (initially found) entity! we need to create some again
                transientEntity.persistentEntity = persistentTransaction.newEntity(creator.type)
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

    internal fun setProperty(transientEntity: TransientEntity, propertyName: String, propertyNewValue: Comparable<*>): Boolean {
        return addChangeAndRun { setPropertyInternal(transientEntity, propertyName, propertyNewValue) }
    }

    private fun setPropertyInternal(transientEntity: TransientEntity, propertyName: String, propertyNewValue: Comparable<*>): Boolean {
        return if (transientEntity.persistentEntity.setProperty(propertyName, propertyNewValue)) {
            val oldValue = getOriginalPropertyValue(transientEntity, propertyName)
            if (propertyNewValue === oldValue || propertyNewValue == oldValue) {
                transientChangesTracker.removePropertyChanged(transientEntity, propertyName)
            } else {
                transientChangesTracker.propertyChanged(transientEntity, propertyName)
            }
            true
        } else {
            false
        }
    }

    internal fun deleteProperty(transientEntity: TransientEntity, propertyName: String): Boolean {
        return addChangeAndRun { deletePropertyInternal(transientEntity, propertyName) }
    }

    private fun deletePropertyInternal(transientEntity: TransientEntity, propertyName: String): Boolean {
        return if (transientEntity.persistentEntity.deleteProperty(propertyName)) {
            val oldValue = getOriginalPropertyValue(transientEntity, propertyName)
            if (oldValue == null) {
                transientChangesTracker.removePropertyChanged(transientEntity, propertyName)
            } else {
                transientChangesTracker.propertyChanged(transientEntity, propertyName)
            }
            true
        } else {
            false
        }
    }

    internal fun setBlob(transientEntity: TransientEntity, blobName: String, stream: InputStream) {
        val copy = try {
            store.persistentStore.blobVault.cloneStream(stream, true)
        } catch (ioe: IOException) {
            throw RuntimeException(ioe)
        }

        copy.mark(Integer.MAX_VALUE)
        addChangeAndRun {
            copy.reset()
            transientEntity.persistentEntity.setBlob(blobName, copy)
            transientChangesTracker.propertyChanged(transientEntity, blobName)
            true
        }
    }

    internal fun setBlob(transientEntity: TransientEntity, blobName: String, file: File) {
        addChangeAndRun {
            transientEntity.persistentEntity.setBlob(blobName, file)
            transientChangesTracker.propertyChanged(transientEntity, blobName)
            true
        }
    }

    internal fun setBlobString(transientEntity: TransientEntity, blobName: String, newValue: String): Boolean {
        return addChangeAndRun {
            if (transientEntity.persistentEntity.setBlobString(blobName, newValue)) {
                val oldValue = getOriginalBlobStringValue(transientEntity, blobName)
                if (newValue === oldValue || newValue == oldValue) {
                    transientChangesTracker.removePropertyChanged(transientEntity, blobName)
                } else {
                    transientChangesTracker.propertyChanged(transientEntity, blobName)
                }
                true
            } else {
                false
            }
        }
    }

    internal fun deleteBlob(transientEntity: TransientEntity, blobName: String): Boolean {
        return addChangeAndRun {
            if (transientEntity.persistentEntity.deleteBlob(blobName)) {
                val oldValue = getOriginalBlobValue(transientEntity, blobName)
                if (oldValue == null) {
                    transientChangesTracker.removePropertyChanged(transientEntity, blobName)
                } else {
                    transientChangesTracker.propertyChanged(transientEntity, blobName)
                    oldValue.close()
                }
                true
            } else {
                false
            }
        }
    }

    internal fun setLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return addChangeAndRun { setLinkInternal(source, linkName, target) }
    }

    private fun setLinkInternal(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        val oldTarget = source.getLink(linkName) as TransientEntity?
        assertLinkTypeIsSupported(source, linkName, target)
        return if (source.persistentEntity.setLink(linkName, target.persistentEntity)) {
            transientChangesTracker.linkChanged(source, linkName, target, oldTarget, true)
            true
        } else {
            false
        }
    }

    internal fun addLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return addChangeAndRun { addLinkInternal(source, linkName, target) }
    }

    private fun addLinkInternal(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        assertLinkTypeIsSupported(source, linkName, target)
        return if (source.persistentEntity.addLink(linkName, target.persistentEntity)) {
            transientChangesTracker.linkChanged(source, linkName, target, null, true)
            true
        } else {
            false
        }
    }

    private fun assertLinkTypeIsSupported(source: TransientEntity, linkName: String, target: TransientEntity) {
        if (assertLinkTypes) {
            store.modelMetaData?.let {
                val linkMetaData = it.getEntityMetaData(source.type)?.getAssociationEndMetaData(linkName)
                if (linkMetaData != null) {
                    val subTypes = linkMetaData.oppositeEntityMetaData.allSubTypes
                    val ownType = linkMetaData.oppositeEntityMetaData.type
                    if (target.type != ownType && !subTypes.contains(target.type)) {
                        val allowed = (subTypes + ownType).joinToString()
                        throw IllegalStateException("'${source.type}.$linkName' can contain only '$allowed' types. '${target.type}' type is not supported.")
                    }
                }
            }
        }
    }

    internal fun deleteLink(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return addChangeAndRun { deleteLinkInternal(source, linkName, target) }
    }

    private fun deleteLinkInternal(source: TransientEntity, linkName: String, target: TransientEntity): Boolean {
        return if (source.persistentEntity.deleteLink(linkName, target.persistentEntity)) {
            transientChangesTracker.linkChanged(source, linkName, target, null, false)
            true
        } else {
            false
        }
    }

    internal fun deleteLinks(source: TransientEntity, linkName: String) {
        addChangeAndRun {
            transientChangesTracker.linksRemoved(source, linkName, source.getLinks(linkName))
            source.persistentEntity.deleteLinks(linkName)
            true
        }
    }

    internal fun deleteEntity(transientEntity: TransientEntity): Boolean {
        return addChangeAndRun { deleteEntityInternal(transientEntity) }
    }

    private fun deleteEntityInternal(e: TransientEntity): Boolean {
        if (TransientStoreUtil.isPostponeUniqueIndexes) {
            if (e.persistentEntity.delete()) {
                transientChangesTracker.entityRemoved(e)
            }
        } else {
            // remember index values first
            val indexes = getIndexesValuesBeforeDelete(e)
            if (e.persistentEntity.delete()) {
                deleteIndexes(e, indexes)
                transientChangesTracker.entityRemoved(e)
            }
        }
        return true
    }

    internal fun setToOne(source: TransientEntity, linkName: String, target: TransientEntity?) {
        addChangeAndRun {
            if (target == null) {
                val oldTarget = source.getLink(linkName) as TransientEntity?
                if (oldTarget != null) {
                    deleteLinkInternal(source, linkName, oldTarget)
                }
            } else {
                setLinkInternal(source, linkName, target)
            }
            true
        }
    }

    internal fun setManyToOne(
            many: TransientEntity,
            manyToOneLinkName: String,
            oneToManyLinkName: String,
            one: TransientEntity?
    ) {
        addChangeAndRun {
            val m = newLocalCopySafe(many)
            if (m != null) {
                val o = newLocalCopySafe(one)
                val oldOne = m.getLink(manyToOneLinkName) as TransientEntity?
                if (oldOne != null) {
                    deleteLinkInternal(oldOne, oneToManyLinkName, m)
                    if (o == null) {
                        deleteLinkInternal(m, manyToOneLinkName, oldOne)
                    }
                }
                if (o != null) {
                    addLinkInternal(o, oneToManyLinkName, m)
                    setLinkInternal(m, manyToOneLinkName, o)
                }
            }
            true
        }
    }

    internal fun clearOneToMany(one: TransientEntity, manyToOneLinkName: String, oneToManyLinkName: String) {
        addChangeAndRun {
            for (target in one.getLinks(oneToManyLinkName)) {
                val many = target as TransientEntity
                deleteLinkInternal(one, oneToManyLinkName, many)
                deleteLinkInternal(many, manyToOneLinkName, one)
            }
            true
        }
    }

    fun createManyToMany(
            e1: TransientEntity,
            e1Toe2LinkName: String,
            e2Toe1LinkName: String,
            e2: TransientEntity
    ) {
        addChangeAndRun {
            addLinkInternal(e1, e1Toe2LinkName, e2)
            addLinkInternal(e2, e2Toe1LinkName, e1)
            true
        }
    }

    fun clearManyToMany(e1: TransientEntity, e1Toe2LinkName: String, e2Toe1LinkName: String) {
        addChangeAndRun {
            for (target in e1.getLinks(e1Toe2LinkName)) {
                val e2 = target as TransientEntity
                deleteLinkInternal(e1, e1Toe2LinkName, e2)
                deleteLinkInternal(e2, e2Toe1LinkName, e1)
            }
            true
        }
    }

    fun setOneToOne(
            e1: TransientEntityImpl,
            e1Toe2LinkName: String,
            e2Toe1LinkName: String,
            e2: TransientEntity?
    ) {
        addChangeAndRun {
            val prevE2 = e1.getLink(e1Toe2LinkName) as TransientEntity?
            if (prevE2 == null || prevE2 != e1) {
                if (prevE2 != null) {
                    deleteLinkInternal(prevE2, e2Toe1LinkName, e1)
                    deleteLinkInternal(e1, e1Toe2LinkName, prevE2)
                }
                if (e2 != null) {
                    val prevE1 = e2.getLink(e2Toe1LinkName) as TransientEntity?
                    if (prevE1 != null) {
                        deleteLinkInternal(prevE1, e1Toe2LinkName, e2)
                    }
                    setLinkInternal(e1, e1Toe2LinkName, e2)
                    setLinkInternal(e2, e2Toe1LinkName, e1)
                }
            }
            true
        }
    }

    fun removeOneToMany(
            one: TransientEntityImpl,
            manyToOneLinkName: String,
            oneToManyLinkName: String,
            many: TransientEntity
    ) {
        addChangeAndRun {
            val oldOne = many.getLink(manyToOneLinkName) as TransientEntity?
            if (one == oldOne) {
                deleteLinkInternal(many, manyToOneLinkName, oldOne)
            }
            deleteLinkInternal(one, oneToManyLinkName, many)
            true
        }
    }

    fun removeFromParent(
            child: TransientEntity,
            parentToChildLinkName: String,
            childToParentLinkName: String
    ) {
        addChangeAndRun {
            val parent = child.getLink(childToParentLinkName) as TransientEntity?
            if (parent != null) {
                // may be changed or removed
                removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child)
            }
            true
        }
    }

    fun removeChild(
            parent: TransientEntityImpl,
            parentToChildLinkName: String,
            childToParentLinkName: String
    ) {
        addChangeAndRun {
            val child = parent.getLink(parentToChildLinkName) as TransientEntity?
            if (child != null) {
                // may be changed or removed
                removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child)
            }
            true
        }
    }

    private fun removeChildFromParentInternal(
            parent: TransientEntity,
            parentToChildLinkName: String,
            childToParentLinkName: String?,
            child: TransientEntity
    ) {
        deleteLinkInternal(parent, parentToChildLinkName, child)
        deletePropertyInternal(child, PARENT_TO_CHILD_LINK_NAME)
        if (childToParentLinkName != null) {
            deleteLinkInternal(child, childToParentLinkName, parent)
            deletePropertyInternal(child, CHILD_TO_PARENT_LINK_NAME)
        }
    }

    fun setChild(
            parent: TransientEntity,
            parentToChildLinkName: String,
            childToParentLinkName: String,
            child: TransientEntity
    ) {
        addChangeAndRun {
            if (removeChildFromCurrentParentInternal(child, childToParentLinkName, parentToChildLinkName, parent)) {
                val oldChild = parent.getLink(parentToChildLinkName) as TransientEntity?
                if (oldChild != null) {
                    removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, oldChild)
                }
                setLinkInternal(parent, parentToChildLinkName, child)
                setLinkInternal(child, childToParentLinkName, parent)
                setPropertyInternal(child, PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName)
                setPropertyInternal(child, CHILD_TO_PARENT_LINK_NAME, childToParentLinkName)
            }
            true
        }
    }

    private fun removeChildFromCurrentParentInternal(
            child: TransientEntity,
            childToParentLinkName: String,
            parentToChildLinkName: String,
            newParent: TransientEntity
    ): Boolean {
        val oldChildToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME) as String?
        if (oldChildToParentLinkName != null) {
            if (childToParentLinkName == oldChildToParentLinkName) {
                val oldParent = child.getLink(childToParentLinkName) as TransientEntity?
                if (oldParent != null) {
                    if (oldParent == newParent) {
                        return false
                    }
                    // child to parent link will be overwritten, so don't delete it directly
                    deleteLinkInternal(oldParent, parentToChildLinkName, child)
                }
            } else {
                val oldParent = child.getLink(oldChildToParentLinkName) as TransientEntity?
                if (oldParent != null) {
                    val oldParentToChildLinkName = child.getProperty(PARENT_TO_CHILD_LINK_NAME) as String?
                    deleteLinkInternal(oldParent, oldParentToChildLinkName ?: parentToChildLinkName, child)
                    deleteLinkInternal(child, oldChildToParentLinkName, oldParent)
                }
            }
        }
        return true
    }

    fun clearChildren(parent: TransientEntity, parentToChildLinkName: String) {
        addChangeAndRun {
            for (child in parent.getLinks(parentToChildLinkName)) {
                val childToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME) as String?
                removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child as TransientEntity)
            }
            true
        }
    }

    fun addChild(
            parent: TransientEntity,
            parentToChildLinkName: String,
            childToParentLinkName: String,
            child: TransientEntity
    ) {
        addChangeAndRun {
            if (removeChildFromCurrentParentInternal(child, childToParentLinkName, parentToChildLinkName, parent)) {
                addLinkInternal(parent, parentToChildLinkName, child)
                setLinkInternal(child, childToParentLinkName, parent)
                setPropertyInternal(child, PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName)
                setPropertyInternal(child, CHILD_TO_PARENT_LINK_NAME, childToParentLinkName)
            }
            true
        }
    }

    fun getParent(child: TransientEntity): Entity? {
        val childToParentLinkName = child.getProperty(CHILD_TO_PARENT_LINK_NAME) as String?
                ?: return null
        return child.getLink(childToParentLinkName)
    }

    fun addChangeAndRun(change: () -> Boolean): Boolean {
        upgradeReadonlyTransactionIfNecessary()
        return addChange(change).invoke()
    }

    override fun saveEntity(entity: Entity) {
        throw UnsupportedOperationException()
    }

    private fun newLocalCopySafe(entity: TransientEntity?): TransientEntity? {
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
            changes.offer(change)
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
