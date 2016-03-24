package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.core.dataStructures.decorators.QueueDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.*;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.metadata.EntityMetaData;
import jetbrains.exodus.entitystore.metadata.Index;
import jetbrains.exodus.entitystore.metadata.IndexField;
import jetbrains.exodus.entitystore.metadata.ModelMetaData;
import jetbrains.exodus.env.ReadonlyTransactionException;
import jetbrains.exodus.util.ByteArraySizedInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 */
public class TransientSessionImpl implements TransientStoreSession {

    protected static final Log log = LogFactory.getLog(TransientSessionImpl.class);

    private static final String CHILD_TO_PARENT_LINK_NAME = "__CHILD_TO_PARENT_LINK_NAME__";
    private static final String PARENT_TO_CHILD_LINK_NAME = "__PARENT_TO_CHILD_LINK_NAME__";

    protected final TransientEntityStoreImpl store;
    protected final boolean readonly;
    @Nullable
    protected ReadonlyPersistentStoreTransaction txnWhichWasUpgraded;
    protected Runnable upgradeHook;
    protected State state;
    protected boolean quietFlush = false;
    protected TransientChangesTracker changesTracker;
    // stores transient entities that were created for loaded persistent entities to avoid double loading
    protected Map<EntityId, TransientEntity> managedEntities;
    private Queue<MyRunnable> changes = new QueueDecorator<MyRunnable>();
    private final int hashCode = (int) (Math.random() * Integer.MAX_VALUE);
    private boolean allowRunnables = true;
    private Throwable stack = null;
    private boolean flushing = false;

    protected TransientSessionImpl(final TransientEntityStoreImpl store, boolean readonly) {
        this.store = store;
        this.readonly = readonly;
        txnWhichWasUpgraded = null;
        this.store.getPersistentStore().beginReadonlyTransaction();
        this.state = State.Open;
        this.managedEntities = new HashMapDecorator<EntityId, TransientEntity>();
        if (LogFactory.getLog(TransientEntityStoreImpl.class).isDebugEnabled()) {
            stack = new Throwable();
        }
        initChangesTracker(true);
    }

    public Throwable getStack() {
        return stack;
    }

    private TransientChangesTracker initChangesTracker(boolean readonly) {
        if (this.changesTracker != null) changesTracker.dispose();
        TransientChangesTracker oldChangesTracker = changesTracker;
        final PersistentStoreTransaction snapshot = getSnapshot();
        this.changesTracker = readonly ? new ReadOnlyTransientChangesTrackerImpl(snapshot) : new TransientChangesTrackerImpl(snapshot);

        return oldChangesTracker;
    }

    public void setQueryCancellingPolicy(QueryCancellingPolicy policy) {
        getPersistentTransactionInternal().setQueryCancellingPolicy(policy);
    }

    public QueryCancellingPolicy getQueryCancellingPolicy() {
        return getPersistentTransactionInternal().getQueryCancellingPolicy();
    }

    @NotNull
    public TransientEntityStore getStore() {
        return store;
    }

    @Override
    public boolean isIdempotent() {
        return getPersistentTransaction().isIdempotent();
    }

    @Override
    public boolean isReadonly() {
        return readonly;
    }

    @Override
    public void setUpgradeHook(@Nullable final Runnable hook) {
        upgradeHook = hook;
    }

    protected PersistentStoreTransaction getPersistentTransactionInternal() {
        return (PersistentStoreTransaction) store.getPersistentStore().getCurrentTransaction();
    }

    protected void upgradeReadonlyTransactionIfNecessary() {
        final StoreTransaction currentTxn = getPersistentTransactionInternal();
        if (!readonly && currentTxn.isReadonly()) {
            final PersistentEntityStoreImpl persistentStore = getPersistentStore();
            if (persistentStore.getEnvironment().getEnvironmentConfig().getEnvIsReadonly()) {
                throw new ReadonlyTransactionException("Can't upgrade transient transaction in read-only mode");
            }
            if (upgradeHook != null) {
                upgradeHook.run();
            }
            final ReadonlyPersistentStoreTransaction roTxn = (ReadonlyPersistentStoreTransaction) currentTxn;
            final PersistentStoreTransaction newTxn = roTxn.getUpgradedTransaction();
            TxnUtil.registerTransation(persistentStore, newTxn);
            this.changesTracker = this.changesTracker.upgrade();
            txnWhichWasUpgraded = roTxn;
        }
    }

    public String toString() {
        return "transaction [" + hashCode() + "] state [" + state + "]";
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Override
    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public boolean isOpened() {
        return state == State.Open;
    }

    public boolean isCommitted() {
        return state == State.Committed;
    }

    public boolean isAborted() {
        return state == State.Aborted;
    }

    void assertOpen(final String action) {
        if (state != State.Open) {
            throw new IllegalStateException("Can't " + action + " in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable createPersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable) {
        assertOpen("create wrapper");
        // do not wrap twice
        if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
            return wrappedIterable;
        } else {
            return new PersistentEntityIterableWrapper(store, wrappedIterable);
        }
    }

    @Override
    public void revert() {
        if (log.isDebugEnabled()) {
            log.debug("Revert transient session " + this);
        }
        assertOpen("revert");

        PersistentStoreTransaction txn = (PersistentStoreTransaction) getPersistentTransactionInternal();
        if (!txn.isReadonly()) {
            managedEntities = new HashMapDecorator<EntityId, TransientEntity>();
            changes = new QueueDecorator<MyRunnable>();
        }
        closePersistentSession();
        this.store.getPersistentStore().beginReadonlyTransaction();
        initChangesTracker(true);
    }

    @Override
    public boolean flush() {
        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't commit session from another thread.");
        }

        if (log.isDebugEnabled()) {
            log.debug("Intermidiate commit transient session " + this);
        }

        assertOpen("flush");

        if (changes.isEmpty()) {
            log.trace("Nothing to flush.");
        } else {
            flushChanges();
            changes = new QueueDecorator<MyRunnable>();

            for (TransientEntity r : changesTracker.getRemovedEntities()) {
                managedEntities.remove(r.getId());
            }

            final TransientChangesTracker oldChangesTracker = changesTracker;
            closePersistentSession();
            this.store.getPersistentStore().beginReadonlyTransaction();
            this.changesTracker = new ReadOnlyTransientChangesTrackerImpl(getSnapshot());
            notifyFlushedListeners(oldChangesTracker);
        }
        return true;
    }

    public boolean commit() {
        // flush until no side-effects from listeners
        do {
            flush();
        } while (!changes.isEmpty());

        try {
            changesTracker.dispose();
        } finally {
            try {
                closePersistentSession();
            } finally {
                store.unregisterStoreSession(this);
                state = State.Committed;
            }
        }
        return true;
    }

    public void abort() {
        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't abort session that is not current thread session. Current thread session is [" + store.getThreadSession() + "]");
        }

        if (log.isDebugEnabled()) {
            log.debug("Abort transient session " + this);
        }
        assertOpen("abort");
        try {
            changesTracker.dispose();
        } finally {
            try {
                closePersistentSession();
            } finally {
                store.unregisterStoreSession(this);
                state = State.Aborted;
            }
        }
    }

    @NotNull
    public StoreTransaction getPersistentTransaction() {
        assertOpen("get persistent transaction");
        return getPersistentTransactionInternal();
    }


    /**
     * Creates transient wrapper for existing persistent entity
     *
     * @param persistent
     * @return
     */
    @NotNull
    public TransientEntity newEntity(@NotNull Entity persistent) {
        if (persistent instanceof TransientEntity) {
            throw new IllegalArgumentException("Can't create transient entity wrapper for another transient entity.");
        }
        assertOpen("create entity");
        return newEntityImpl((PersistentEntity) persistent);
    }

    @NotNull
    public Entity getEntity(@NotNull final EntityId id) {
        assertOpen("get entity");
        TransientEntity e = managedEntities.get(id);
        if (e == null) {
            return newEntity(changesTracker.getSnapshot().getEntity(id));
        } else {
            return e;
        }
    }

    @NotNull
    public EntityId toEntityId(@NotNull final String representation) {
        assertOpen("convert to entity id");
        return getPersistentTransactionInternal().toEntityId(representation);
    }

    @NotNull
    public List<String> getEntityTypes() {
        assertOpen("get entity types");
        return getPersistentTransactionInternal().getEntityTypes();
    }

    @NotNull
    public EntityIterable getAll(@NotNull final String entityType) {
        assertOpen("getAll");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().getAll(entityType));
    }

    @NotNull
    public EntityIterable getSingletonIterable(@NotNull final Entity entity) {
        assertOpen("getSingletonIterable");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().getSingletonIterable(((TransientEntityImpl) entity).getPersistentEntity()));
    }

    @NotNull
    public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable value) {
        assertOpen("find");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().find(entityType, propertyName, value));
    }

    @NotNull
    public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable minValue, @NotNull final Comparable maxValue) {
        assertOpen("find");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().find(entityType, propertyName, minValue, maxValue));
    }

    @NotNull
    @Override
    public EntityIterable findIds(@NotNull String entityType, long minValue, long maxValue) {
        assertOpen("findIds");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findIds(entityType, minValue, maxValue));
    }

    public EntityIterable findWithProp(@NotNull final String entityType, @NotNull final String propertyName) {
        assertOpen("findWithProp");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findWithProp(entityType, propertyName));
    }

    @NotNull
    public EntityIterable findStartingWith(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final String value) {
        assertOpen("startsWith");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findStartingWith(entityType, propertyName, value));
    }

    @NotNull
    public EntityIterable findWithBlob(@NotNull final String entityType, @NotNull final String propertyName) {
        assertOpen("findWithBlob");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findWithBlob(entityType, propertyName));
    }

    @NotNull
    public EntityIterable findLinks(@NotNull final String entityType, @NotNull final Entity entity, @NotNull final String linkName) {
        assertOpen("findLinks");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findLinks(entityType, entity, linkName));
    }

    @NotNull
    public EntityIterable findLinks(@NotNull String entityType, @NotNull EntityIterable entities, @NotNull String linkName) {
        assertOpen("findLinks");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findLinks(entityType, entities.getSource(), linkName));
    }

    @NotNull
    public EntityIterable findWithLinks(@NotNull String entityType, @NotNull String linkName) {
        assertOpen("findWithLinks");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().findWithLinks(entityType, linkName));
    }

    @NotNull
    public EntityIterable findWithLinks(@NotNull String entityType,
                                        @NotNull String linkName,
                                        @NotNull String oppositeEntityType,
                                        @NotNull String oppositeLinkName) {
        assertOpen("findWithLinks");
        return new PersistentEntityIterableWrapper(store,
                getPersistentTransactionInternal().findWithLinks(entityType, linkName, oppositeEntityType, oppositeLinkName));
    }

    @NotNull
    public EntityIterable sort(@NotNull final String entityType,
                               @NotNull final String propertyName,
                               final boolean ascending) {
        assertOpen("sort");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().sort(entityType, propertyName, ascending));
    }

    @NotNull
    public EntityIterable sort(@NotNull final String entityType,
                               @NotNull final String propertyName,
                               @NotNull final EntityIterable rightOrder,
                               final boolean ascending) {
        assertOpen("sort");
        return new PersistentEntityIterableWrapper(store,
                getPersistentTransactionInternal().sort(entityType, propertyName, rightOrder.getSource(), ascending));
    }

    @NotNull
    public EntityIterable sortLinks(@NotNull final String entityType,
                                    @NotNull final EntityIterable sortedLinks,
                                    boolean isMultiple,
                                    @NotNull final String linkName,
                                    final @NotNull EntityIterable rightOrder) {
        assertOpen("sortLinks");
        return new PersistentEntityIterableWrapper(store,
                getPersistentTransactionInternal().sortLinks(entityType, sortedLinks, isMultiple, linkName, rightOrder.getSource()));
    }

    @NotNull
    public EntityIterable sortLinks(@NotNull final String entityType,
                                    @NotNull final EntityIterable sortedLinks,
                                    boolean isMultiple,
                                    @NotNull final String linkName,
                                    final @NotNull EntityIterable rightOrder,
                                    @NotNull final String oppositeEntityType,
                                    @NotNull final String oppositeLinkName) {
        assertOpen("sortLinks");
        return new PersistentEntityIterableWrapper(store,
                getPersistentTransactionInternal().sortLinks(entityType, sortedLinks, isMultiple, linkName, rightOrder.getSource(), oppositeEntityType, oppositeLinkName));
    }

    @NotNull
    public EntityIterable mergeSorted(@NotNull List<EntityIterable> sorted, @NotNull Comparator<Entity> comparator) {
        assertOpen("mergeSorted");
        return new PersistentEntityIterableWrapper(store, getPersistentTransactionInternal().mergeSorted(sorted, comparator));
    }

    @NotNull
    public Sequence getSequence(@NotNull final String sequenceName) {
        assertOpen("get sequence");
        return getPersistentTransactionInternal().getSequence(sequenceName);
    }

    protected void closePersistentSession() {
        if (log.isDebugEnabled()) {
            log.debug("Close persistent session for transient session " + this);
        }
        StoreTransaction persistentTxn = getPersistentTransactionInternal();
        if (persistentTxn != null) {
            persistentTxn.abort();
        }
        persistentTxn = txnWhichWasUpgraded;
        if (persistentTxn != null) {
            persistentTxn.abort();
            txnWhichWasUpgraded = null;
        }
    }

    public void quietIntermediateCommit() {
        final boolean qf = quietFlush;
        try {
            this.quietFlush = true;
            flush();
        } finally {
            this.quietFlush = qf;
        }
    }

    public void clearHistory(@NotNull final String entityType) {
        assertOpen("clear history");

        addChangeAndRun(new MyRunnable() {
            public boolean run() {
                getPersistentTransaction().clearHistory(entityType);
                return true;
            }
        });
    }

    @NotNull
    public TransientChangesTracker getTransientChangesTracker() throws IllegalStateException {
        assertOpen("get changes tracker");
        return changesTracker;
    }


    private void replayChanges() {
        initChangesTracker(false);

        for (MyRunnable c : changes) {
            c.run();
        }
    }

    /**
     * Removes orphans (entities without parents) or returns OrphanException to throw later.
     */
    @NotNull
    private Set<DataIntegrityViolationException> removeOrphans() {
        Set<DataIntegrityViolationException> orphans = new HashSetDecorator<DataIntegrityViolationException>();
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (modelMetaData == null) {
            return orphans;
        }

        for (TransientEntity e : new ArrayList<TransientEntity>(changesTracker.getChangedEntities())) {
            if (!e.isRemoved()) {
                EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());

                if (emd != null && emd.hasAggregationChildEnds() && !EntityMetaDataUtils.hasParent(emd, e, changesTracker)) {
                    if (emd.getRemoveOrphan()) {
                        // has no parent - remove
                        if (log.isDebugEnabled()) {
                            log.debug("Remove orphan: " + e);
                        }

                        // we don't want this change to be repeated on flush
                        deleteEntityInternal(e);
                    } else {
                        // has no parent, but orphans shouldn't be removed automatically - exception
                        orphans.add(new OrphanChildException(e, emd.getAggregationChildEnds()));
                    }
                }
            }
        }

        return orphans;
    }

    /**
     * Creates new transient entity
     *
     * @param entityType
     * @return
     */
    @NotNull
    public TransientEntity newEntity(@NotNull final String entityType) {
        assertOpen("create entity");
        upgradeReadonlyTransactionIfNecessary();
        return new TransientEntityImpl(entityType, this.getStore());
    }

    @NotNull
    @Override
    public TransientEntity newEntity(@NotNull final EntityCreator creator) {
        final Entity found = creator.find();
        if (found != null) {
            addEntityCreator((TransientEntityImpl) found, creator);
            return (TransientEntity) found;
        }
        upgradeReadonlyTransactionIfNecessary();
        return new TransientEntityImpl(creator, this.getStore());
    }

    /**
     * Creates local copy of given entity in current session.
     *
     * @param entity
     * @return
     */
    @NotNull
    public TransientEntity newLocalCopy(@NotNull final TransientEntity entity) {
        assertOpen("create local copy");
        if (entity.isReadonly() || entity.isWrapper()) {
            return entity;
        } else if (
            // optimization: inlined entity.isRemoved()
                changesTracker.isRemoved(entity)) {
            EntityRemovedException entityRemovedException = new EntityRemovedException(entity);
            log.warn("Entity [" + entity + "] was removed by you.");
            throw entityRemovedException;
        } else if (
            // optimization: inlined entity.isNew()
                changesTracker.isNew(entity)) {
            final EntityId entityId = entity.getId();
            if (managedEntities.get(entityId) == entity) {
                // was created in this session and session wasn't reverted
                return entity;
            }
            throw new IllegalStateException("Entity in state New was not created in this session. " + entity);
        } else if (
            // optimization: inlined entity.isSaved()
                changesTracker.isSaved(entity)) {
            final EntityId entityId = entity.getId();
            final TransientEntity localCopy = managedEntities.get(entityId);
            if (localCopy == entity) {
                // was created in this session and session wasn't reverted
                return entity;
            }
            // saved entity from another session or from reverted session - load it from database by id
            // local copy already created?
            if (localCopy != null) {
                if (
                    // optimization: inlined localCopy.isRemoved()
                        changesTracker.isRemoved(localCopy)) {
                    EntityRemovedException entityRemovedException = new EntityRemovedException(entity);
                    log.warn("Local copy of entity [" + entity + "] was removed by you.");
                    throw entityRemovedException;
                }
                return localCopy;
            }
            try {
                // load persistent entity from database by id
                return newEntity(getPersistentTransactionInternal().getEntity(entityId));
            } catch (EntityRemovedInDatabaseException e) {
                log.warn("Entity [" + entity + "] was removed in database, can't create local copy.");
                throw e;
            }
        } else {
            throw new IllegalStateException("Can't create local copy of entity (unexpected state) [" + entity + "]");
        }
    }

    /**
     * Checks if entity entity was removed in this transaction or in database
     *
     * @param entity
     * @return true if e was removed, false if it wasn't removed at all
     */
    public boolean isRemoved(@NotNull final Entity entity) {
        EntityId entityId = null;
        if (entity instanceof TransientEntity && state == State.Open) {
            final TransientEntity transientEntity = (TransientEntity) entity;
            if (transientEntity.isSaved()) {
                // saved entity from another session or from reverted session
                entityId = entity.getId();
                final TransientEntity localCopy = managedEntities.get(entityId);
                if (localCopy != transientEntity) {
                    // local copy already created?
                    if (localCopy != null && localCopy.isRemoved()) {
                        return true;
                    }
                }
            } else if (transientEntity.isRemoved()) {
                return true;
            } else if (transientEntity.isReadonly() || transientEntity.isNew()) {
                return false;
            }
        }
        // load persistent entity from database by id
        if (entityId == null) {
            entityId = entity.getId();
        }
        final PersistentStoreTransaction persistentTxn = getPersistentTransactionInternal();
        return getPersistentStore().getLastVersion(persistentTxn, entityId) < 0;
    }

    /**
     * Checks constraints before save changes
     */
    private void checkBeforeSaveChangesConstraints() {
        final Set<DataIntegrityViolationException> exceptions = removeOrphans();

        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            if (log.isWarnEnabled()) {
                log.warn("Quiet intermediate commit: skip before save changes constraints checking. " + this);
            }
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Check before save changes constraints. " + this);
        }

        // 0. check incoming links for deleted entities
        exceptions.addAll(ConstraintsUtil.checkIncomingLinks(changesTracker));

        // 1. check associations cardinality
        exceptions.addAll(ConstraintsUtil.checkAssociationsCardinality(changesTracker, modelMetaData));

        // 2. check required properties
        exceptions.addAll(ConstraintsUtil.checkRequiredProperties(changesTracker, modelMetaData));

        // 3. check other property constraints
        exceptions.addAll(ConstraintsUtil.checkOtherPropertyConstraints(changesTracker, modelMetaData));

        // 4. check index fields
        exceptions.addAll(ConstraintsUtil.checkIndexFields(changesTracker, modelMetaData));

        if (exceptions.size() != 0) {
            ConstraintsValidationException e = new ConstraintsValidationException(exceptions);
            store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
                public void visit(TransientStoreSessionListener listener) {
                    try {
                        listener.afterConstraintsFail(exceptions);
                    } catch (Exception e) {
                        if (log.isErrorEnabled()) {
                            log.error("Exception while inside listener [" + listener + "]", e);
                        }
                        // do not rethrow exception, because we are after constaints fail
                    }
                }
            });
            throw e;
        }
    }

    /**
     * Checks custom flush constraints before save changes
     */
    private void executeBeforeFlushTriggers(Set<TransientEntity> changedEntities) {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            if (log.isDebugEnabled()) {
                log.warn("Quiet intermediate commit: skip before flush triggers. " + this);
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Execute before flush triggers. " + this);
        }

        final Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();
        for (TransientEntity entity : changedEntities) {
            if (!entity.isRemoved()) {
                EntityMetaData md = modelMetaData.getEntityMetaData(entity.getType());

                // meta-data may be null for persistent enums
                if (md != null) {
                    try {
                        TransientStoreUtil.getPersistentClassInstance(entity).executeBeforeFlushTrigger(entity);
                    } catch (ConstraintsValidationException cve) {
                        for (DataIntegrityViolationException dive : cve.getCauses()) {
                            exceptions.add(dive);
                        }
                    }
                }
            }
        }

        if (exceptions.size() != 0) {
            ConstraintsValidationException e = new ConstraintsValidationException(exceptions);
            throw e;
        }
    }

    /**
     * Flushes changes
     */
    private void flushChanges() {
        if (flushing) {
            throw new IllegalStateException("Transaction is already being flushed!");
        }
        try {
            flushing = true;
            beforeFlush();
            checkBeforeSaveChangesConstraints();

            notifyBeforeFlushAfterConstraintsCheckListeners();

            final StoreTransaction txn = getPersistentTransactionInternal();
            if (txn.isIdempotent()) {
                return;
            }

            try {
                prepare();
                while (true) {
                    if (txn.flush()) {
                        return;
                    }
                    // replay changes
                    replayChanges();
                    //recheck constraints against new database root
                    checkBeforeSaveChangesConstraints();
                    prepare();
                }
            } catch (Throwable exception) {
                if (log.isInfoEnabled()) {
                    log.info("Catch exception in flush: " + exception.getMessage());
                }
                if (exception instanceof DataIntegrityViolationException) {
                    txn.revert();
                    replayChanges();
                }
                decodeException(exception);
                throw new IllegalStateException("should never be thrown");
            }
        } finally {
            flushing = false;
        }
    }

    private void prepare() {
        try {
            allowRunnables = false;
            saveHistory();
            flushIndexes();
        } finally {
            allowRunnables = true;
        }

        if (log.isTraceEnabled()) {
            log.trace("Flush persistent transaction in transient session " + this);
        }
    }

    private PersistentStoreTransaction getSnapshot() {
        return ((PersistentStoreTransaction) getPersistentTransaction()).getSnapshot();
    }

    private void flushIndexes() {
        if (TransientStoreUtil.isPostponeUniqueIndexes()) {
            return;
        }

        for (TransientEntity e : changesTracker.getChangedEntities()) {
            if (!e.isRemoved()) {
                // create/update
                Set<Index> dirtyIndeces = new HashSetDecorator<Index>();
                final Set<String> changedProperties = changesTracker.getChangedProperties(e);
                if (changedProperties != null) {
                    for (String propertyName : changedProperties) {
                        final Set<Index> indices = getMetadataIndexes(e, propertyName);
                        if (indices != null) {
                            dirtyIndeces.addAll(indices);
                        }
                    }
                }

                final Map<String, LinkChange> changedLinksDetailed = changesTracker.getChangedLinksDetailed(e);
                if (changedLinksDetailed != null) {
                    for (String propertyName : changedLinksDetailed.keySet()) {
                        final Set<Index> indices = getMetadataIndexes(e, propertyName);
                        if (indices != null) {
                            dirtyIndeces.addAll(indices);
                        }
                    }
                }

                for (Index index : dirtyIndeces) {
                    try {
                        if (!e.isNew()) {
                            getPersistentTransaction().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
                        }
                        getPersistentTransaction().insertUniqueKey(index, getIndexFieldsFinalValues(e, index), e);
                    } catch (ExodusException ex) {
                        throw new ConstraintsValidationException(new UniqueIndexViolationException(e, index));
                    }
                }
            }
        }
    }

    @Nullable
    Set<Pair<Index, List<Comparable>>> getIndexesValuesBeforeDelete(TransientEntity e) {
        if (changesTracker.isNew(e)) return null;

        Set<Pair<Index, List<Comparable>>> res = null;
        final EntityMetaData emd = getEntityMetaData(e);
        if (emd != null) {
            for (Index index : emd.getIndexes()) {
                if (res == null) res = new HashSet<Pair<Index, List<Comparable>>>();
                res.add(new Pair<Index, List<Comparable>>(index, getIndexFieldsOriginalValues(e, index)));
            }
        }

        return res;
    }

    void deleteIndexes(TransientEntity e, @Nullable Set<Pair<Index, List<Comparable>>> indexes) {
        if (indexes == null) return;

        for (Pair<Index, List<Comparable>> index : indexes) {
            try {
                getPersistentTransaction().deleteUniqueKey(index.getFirst(), index.getSecond());
            } catch (ExodusException ex) {
                throw new ConstraintsValidationException(new UniqueIndexIntegrityException(e, index.getFirst(), ex));
            }
        }
    }

    private List<Comparable> getIndexFieldsOriginalValues(TransientEntity e, Index index) {
        List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
        for (IndexField f : index.getFields()) {
            if (f.isProperty()) {
                res.add(getOriginalPropertyValue(e, f.getName()));
            } else {
                res.add(getOriginalLinkValue(e, f.getName()));
            }
        }
        return res;
    }

    @Nullable
    private Comparable getOriginalPropertyValue(TransientEntity e, String propertyName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getProperty(propertyName);
    }

    @Nullable
    private ByteIterable getOriginalRawPropertyValue(TransientEntity e, String propertyName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getRawProperty(propertyName);
    }

    @Nullable
    private String getOriginalBlobStringValue(TransientEntity e, String blobName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getBlobString(blobName);
    }

    @Nullable
    private InputStream getOriginalBlobValue(TransientEntity e, String blobName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getBlob(blobName);
    }

    @Nullable
    private Comparable getOriginalLinkValue(TransientEntity e, String linkName) {
        // get from saved changes, if not - from db
        Map<String, LinkChange> linksDetailed = changesTracker.getChangedLinksDetailed(e);
        if (linksDetailed != null) {
            LinkChange change = linksDetailed.get(linkName);
            if (change != null) {
                switch (change.getChangeType()) {
                    case ADD_AND_REMOVE:
                    case REMOVE:
                        if (change.getRemovedEntitiesSize() != 1) {
                            if (change.getDeletedEntitiesSize() == 1) {
                                return change.getDeletedEntities().iterator().next();
                            }
                            throw new IllegalStateException("Can't determine original link value: " + e.getType() + "." + linkName);
                        }
                        return change.getRemovedEntities().iterator().next();
                    default:
                        throw new IllegalStateException("Incorrect change type for link that is part of index: " + e.getType() + "." + linkName + ": " + change.getChangeType().getName());
                }
            }
        }

        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getLink(linkName);
    }

    private List<Comparable> getIndexFieldsFinalValues(TransientEntity e, Index index) {
        List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
        for (IndexField f : index.getFields()) {
            if (f.isProperty()) {
                res.add(e.getProperty(f.getName()));
            } else {
                res.add(e.getLink(f.getName()));
            }
        }
        return res;
    }

    @Nullable
    private Set<Index> getMetadataIndexes(TransientEntity e, String field) {
        EntityMetaData md = getEntityMetaData(e);
        return md == null ? null : md.getIndexes(field);
    }

    @Nullable
    private EntityMetaData getEntityMetaData(TransientEntity e) {
        ModelMetaData mdd = store.getModelMetaData();
        return mdd == null ? null : mdd.getEntityMetaData(e.getType());
    }

    private void beforeFlush() {
        // notify listeners, execute before flush, if were side effects, do the same for side effects

        Set<TransientEntityChange> changesDescription = changesTracker.getChangesDescription();
        if (!changesTracker.getChangedEntities().isEmpty()) {
            final Set<TransientEntity> processedEntities = new HashSetDecorator<TransientEntity>();
            Set<TransientEntity> changed = getSideEffects(processedEntities);
            while (true) {
                final int changesSize = changesTracker.getChangedEntities().size();
                notifyBeforeFlushListeners(changesDescription);
                executeBeforeFlushTriggers(changed);
                if (changesSize == changesTracker.getChangedEntities().size()) {
                    break;
                }
                processedEntities.addAll(changed);
                changed = getSideEffects(processedEntities);
                if (changed.isEmpty()) {
                    break;
                }
                changesDescription = new HashSet<TransientEntityChange>();
                for (TransientEntity sideEffectEntity : changed) {
                    changesDescription.add(changesTracker.getChangeDescription(sideEffectEntity));
                }
            }
        }
    }

    private Set<TransientEntity> getSideEffects(Set<TransientEntity> processedEntities) {
        HashSet<TransientEntity> sideEffect = new HashSet<TransientEntity>(changesTracker.getChangedEntities());
        sideEffect.removeAll(processedEntities);
        return sideEffect;
    }

    private static void decodeException(@NotNull Throwable e) {
        if (e instanceof Error) {
            throw (Error) e;
        }

        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }

        throw new RuntimeException(e);
    }

    private void saveHistory() {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (modelMetaData == null) {
            if (log.isWarnEnabled()) {
                log.warn("Model meta data is not defined. Skip history processing. " + this);
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Save history of changed entities. " + this);
        }

        final PersistentStoreTransaction snapshot = changesTracker.getSnapshot();
        final Set<TransientEntity> changedEntities = changesTracker.getChangedEntities();

        for (final TransientEntity e : changedEntities.toArray(new TransientEntity[changedEntities.size()])) {
            if (!e.isNew() && !e.isRemoved()) {
                final EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());
                if (emd != null && TransientStoreUtil.getPersistentClassInstance(e).evaluateSaveHistoryCondition(e)
                        && EntityMetaDataUtils.changesReflectHistory(emd, e, changesTracker)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Save history of: " + e);
                    }
                    e.getPersistentEntity().newVersion(snapshot);

                    // !!! should be called after e.newVersion();
                    TransientStoreUtil.getPersistentClassInstance(e).saveHistoryCallback(e);
                }
            }
        }
    }

    private void notifyFlushedListeners(TransientChangesTracker oldChangesTracker) {
        final Set<TransientEntityChange> changesDescription = oldChangesTracker.getChangesDescription();
        if (changesDescription.isEmpty()) {
            oldChangesTracker.dispose();
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Notify flushed listeners " + this);
        }

        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.flushed(changesDescription);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // do not rethrow exception
                }
            }
        });

        //explicitly notify EventsMultiplexer - it will dispose changes tracker in async job
        final IEventsMultiplexer ep = store.getEventsMultiplexer();
        if (ep != null) {
            try {
                ep.flushed(oldChangesTracker, changesDescription);
            } catch (Exception e) {
                if (log.isErrorEnabled()) {
                    log.error("Exception while inside events multiplexer", e);
                }
                oldChangesTracker.dispose();
            }
        } else {
            oldChangesTracker.dispose();
        }
    }

    private void notifyBeforeFlushListeners(final Set<TransientEntityChange> changes) {
        if (changes == null || changes.isEmpty()) return;

        if (log.isDebugEnabled()) {
            log.debug("Notify before flush listeners " + this);
        }

        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.beforeFlush(changes);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // rethrow exception, because we are before constraints check
                    decodeException(e);
                }
            }
        });
    }

    @Deprecated
    private void notifyBeforeFlushAfterConstraintsCheckListeners() {
        final Set<TransientEntityChange> changesDescr = changesTracker.getChangesDescription();

        if (changesDescr == null || changesDescr.isEmpty()) return;

        if (log.isDebugEnabled()) {
            log.debug("Notify before flush after constraints check listeners " + this);
        }

        // check side effects in listeners
        final int changesCount = changes.size();
        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.beforeFlushAfterConstraintsCheck(changesDescr);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // do not rethrow exceptipon, because we are after constaints check
                }
            }
        });

        if (changes.size() != changesCount) {
            throw new EntityStoreException("It's not allowed to change database inside listener.beforeFlushAfterConstraintsCheck() method.");
        }
    }

    @NotNull
    protected TransientEntity newEntityImpl(final PersistentEntity persistent) {
        if (persistent instanceof ReadOnlyPersistentEntity) {
            return new ReadonlyTransientEntityImpl((ReadOnlyPersistentEntity) persistent, store);
        }
        final EntityId entityId = persistent.getId();
        TransientEntity e = managedEntities.get(entityId);
        if (e == null) {
            e = new TransientEntityImpl((PersistentEntity) persistent, this.getStore());
            managedEntities.put(entityId, e);
        }
        return e;
    }

    void createEntity(@NotNull final TransientEntityImpl e, @NotNull String type) {
        final PersistentEntity persistentEntity = (PersistentEntity) getPersistentTransaction().newEntity(type);
        e.setPersistentEntity(persistentEntity);
        managedEntities.put(e.getId(), e);
        changesTracker.entityAdded(e);
        addChange(new MyRunnable() {
            public boolean run() {
                return saveEntityInternal(persistentEntity, e);
            }
        });
    }

    private boolean saveEntityInternal(@NotNull final PersistentEntity persistentEntity, @NotNull final TransientEntityImpl e) {
        getPersistentTransaction().saveEntity(persistentEntity);
        changesTracker.entityAdded(e);
        return true;
    }

    void createEntity(@NotNull final TransientEntityImpl e, @NotNull final EntityCreator creator) {
        final PersistentEntity persistentEntity = (PersistentEntity) getPersistentTransaction().newEntity(creator.getType());
        e.setPersistentEntity(persistentEntity);
        addChange(new MyRunnable() {
            @Override
            public boolean run() {
                final Entity found = creator.find();
                if (found == null) {
                    final boolean result = saveEntityInternal(persistentEntity, e);
                    try {
                        allowRunnables = false;
                        creator.created(e);
                    } finally {
                        allowRunnables = true;
                    }
                    return result;
                }
                e.setPersistentEntity(((TransientEntityImpl) found).getPersistentEntity());
                return false;
            }
        });
        managedEntities.put(e.getId(), e);
        changesTracker.entityAdded(e);
        try {
            allowRunnables = false;
            creator.created(e);
        } finally {
            allowRunnables = true;
        }
    }

    private void addEntityCreator(@NotNull final TransientEntityImpl e, @NotNull final EntityCreator creator) {
        addChange(new MyRunnable() {
            @Override
            public boolean run() {
                final Entity found = creator.find();
                if (found != null) {
                    if (!found.equals(e)) {
                        // update existing entity
                        e.setPersistentEntity(((TransientEntityImpl) found).getPersistentEntity());
                    }
                    return false;
                }
                upgradeReadonlyTransactionIfNecessary();
                // somebody deleted our (initially found) entity! we need to create some again
                e.setPersistentEntity((PersistentEntity) getPersistentTransaction().newEntity(creator.getType()));
                changesTracker.entityAdded(e);
                try {
                    allowRunnables = false;
                    creator.created(e);
                } finally {
                    allowRunnables = true;
                }
                return true;
            }
        });
    }

    boolean setProperty(@NotNull final TransientEntity e, @NotNull final String propertyName,
                        @NotNull final Comparable propertyNewValue) {
        return addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                return setPropertyInternal(e, propertyName, propertyNewValue);
            }
        });
    }

    private boolean setPropertyInternal(@NotNull final TransientEntity e, @NotNull final String propertyName,
                                        @NotNull final Comparable propertyNewValue) {
        if (e.getPersistentEntity().setProperty(propertyName, propertyNewValue)) {
            final Comparable oldValue = getOriginalPropertyValue(e, propertyName);
            if (propertyNewValue == oldValue || propertyNewValue.equals(oldValue)) {
                changesTracker.removePropertyChanged(e, propertyName);
            } else {
                changesTracker.propertyChanged(e, propertyName);
            }
            return true;
        }
        return false;
    }

    boolean deleteProperty(@NotNull final TransientEntity e, @NotNull final String propertyName) {
        return addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                return deletePropertyInternal(e, propertyName);
            }
        });
    }

    private boolean deletePropertyInternal(@NotNull final TransientEntity e, @NotNull final String propertyName) {
        if (e.getPersistentEntity().deleteProperty(propertyName)) {
            final Comparable oldValue = getOriginalPropertyValue(e, propertyName);
            if (oldValue == null) {
                changesTracker.removePropertyChanged(e, propertyName);
            } else {
                changesTracker.propertyChanged(e, propertyName);
            }
            return true;
        }
        return false;
    }

    void setBlob(@NotNull final TransientEntity e, @NotNull final String blobName, @NotNull final InputStream stream) {
        final ByteArraySizedInputStream copy;
        try {
            copy = ((PersistentEntityStore) store.getPersistentStore()).getBlobVault().cloneStream(stream, true);
        } catch (final IOException ioe) {
            throw new RuntimeException(ioe);
        }
        copy.mark(Integer.MAX_VALUE);
        addChangeAndRun(new MyRunnable() {
            public boolean run() {
                copy.reset();
                e.getPersistentEntity().setBlob(blobName, copy);
                changesTracker.propertyChanged(e, blobName);
                return true;
            }
        });
    }

    void setBlob(@NotNull final TransientEntity e, @NotNull final String blobName, @NotNull final File file) {
        addChangeAndRun(new MyRunnable() {
            public boolean run() {
                e.getPersistentEntity().setBlob(blobName, file);
                changesTracker.propertyChanged(e, blobName);
                return true;
            }
        });
    }

    boolean setBlobString(@NotNull final TransientEntity e, @NotNull final String blobName, @NotNull final String newValue) {
        return addChangeAndRun(new MyRunnable() {
            public boolean run() {
                if (e.getPersistentEntity().setBlobString(blobName, newValue)) {
                    final Comparable oldValue = getOriginalBlobStringValue(e, blobName);
                    if (newValue == oldValue || newValue.equals(oldValue)) {
                        changesTracker.removePropertyChanged(e, blobName);
                    } else {
                        changesTracker.propertyChanged(e, blobName);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    boolean deleteBlob(@NotNull final TransientEntity e, @NotNull final String blobName) {
        return addChangeAndRun(new MyRunnable() {
            public boolean run() {
                if (e.getPersistentEntity().deleteBlob(blobName)) {
                    final InputStream oldValue = getOriginalBlobValue(e, blobName);
                    if (oldValue == null) {
                        changesTracker.removePropertyChanged(e, blobName);
                    } else {
                        changesTracker.propertyChanged(e, blobName);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    boolean setLink(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        return addChangeAndRun(new MyRunnable() {
            public boolean run() {
                return setLinkInternal(source, linkName, target);
            }
        });
    }

    private boolean setLinkInternal(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        final TransientEntity oldTarget = (TransientEntity) source.getLink(linkName);
        if (source.getPersistentEntity().setLink(linkName, target.getPersistentEntity())) {
            changesTracker.linkChanged(source, linkName, target, oldTarget, true);
            return true;
        }

        return false;
    }

    boolean addLink(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        return addChangeAndRun(new MyRunnable() {
            public boolean run() {
                return addLinkInternal(source, linkName, target);
            }
        });
    }

    private boolean addLinkInternal(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        if (source.getPersistentEntity().addLink(linkName, target.getPersistentEntity())) {
            changesTracker.linkChanged(source, linkName, target, null, true);
            return true;
        }

        return false;
    }

    boolean deleteLink(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        return addChangeAndRun(new MyRunnable() {
            public boolean run() {
                return deleteLinkInternal(source, linkName, target);
            }
        });
    }

    private boolean deleteLinkInternal(TransientEntity source, String linkName, TransientEntity target) {
        if (source.getPersistentEntity().deleteLink(linkName, target.getPersistentEntity())) {
            changesTracker.linkChanged(source, linkName, target, null, false);
            return true;
        }

        return false;
    }

    void deleteLinks(@NotNull final TransientEntity source, @NotNull final String linkName) {
        addChangeAndRun(new MyRunnable() {
            public boolean run() {
                changesTracker.linksRemoved(source, linkName, source.getLinks(linkName));
                source.getPersistentEntity().deleteLinks(linkName);
                return true;
            }
        });
    }

    boolean deleteEntity(@NotNull final TransientEntity e) {
        return addChangeAndRun(new MyRunnable() {
            public boolean run() {
                return deleteEntityInternal(e);
            }
        });
    }

    private boolean deleteEntityInternal(@NotNull final TransientEntity e) {
        // remember index values first
        final Set<Pair<Index, List<Comparable>>> indexes = getIndexesValuesBeforeDelete(e);
        if (e.getPersistentEntity().delete()) {
            deleteIndexes(e, indexes);
            changesTracker.entityRemoved(e);
        }
        return true;
    }

    void setToOne(@NotNull final TransientEntity source, @NotNull final String linkName, @Nullable final TransientEntity target) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                if (target == null) {
                    TransientEntity oldTarget = (TransientEntity) source.getLink(linkName);
                    if (oldTarget != null) {
                        deleteLinkInternal(source, linkName, oldTarget);
                    }
                } else {
                    setLinkInternal(source, linkName, target);
                }
                return true;
            }
        });
    }

    void setManyToOne(@NotNull final TransientEntity many, @NotNull final String manyToOneLinkName,
                      @NotNull final String oneToManyLinkName, @Nullable final TransientEntity one) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                final TransientEntity oldOne = (TransientEntity) many.getLink(manyToOneLinkName);
                if (oldOne != null) {
                    deleteLinkInternal(oldOne, oneToManyLinkName, many);
                    if (one == null) {
                        deleteLinkInternal(many, manyToOneLinkName, oldOne);
                    }
                }
                if (one != null) {
                    addLinkInternal(one, oneToManyLinkName, many);
                    setLinkInternal(many, manyToOneLinkName, one);
                }
                return true;
            }
        });
    }

    void clearOneToMany(@NotNull final TransientEntity one, @NotNull final String manyToOneLinkName, @NotNull final String oneToManyLinkName) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                for (final Entity target : one.getLinks(oneToManyLinkName)) {
                    final TransientEntity many = (TransientEntity) target;
                    deleteLinkInternal(one, oneToManyLinkName, many);
                    deleteLinkInternal(many, manyToOneLinkName, one);
                }
                return true;
            }
        });
    }

    public void createManyToMany(@NotNull final TransientEntity e1, @NotNull final String e1Toe2LinkName,
                                 @NotNull final String e2Toe1LinkName, @NotNull final TransientEntity e2) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                addLinkInternal(e1, e1Toe2LinkName, e2);
                addLinkInternal(e2, e2Toe1LinkName, e1);
                return true;
            }
        });
    }

    public void clearManyToMany(@NotNull final TransientEntity e1, @NotNull final String e1Toe2LinkName, @NotNull final String e2Toe1LinkName) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                for (final Entity target : e1.getLinks(e1Toe2LinkName)) {
                    final TransientEntity e2 = (TransientEntity) target;
                    deleteLinkInternal(e1, e1Toe2LinkName, e2);
                    deleteLinkInternal(e2, e2Toe1LinkName, e1);
                }
                return true;
            }
        });
    }

    public void setOneToOne(@NotNull final TransientEntityImpl e1, @NotNull final String e1Toe2LinkName,
                            @NotNull final String e2Toe1LinkName, @Nullable final TransientEntity e2) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                final TransientEntity prevE2 = (TransientEntity) e1.getLink(e1Toe2LinkName);
                if (prevE2 != null) {
                    if (prevE2.equals(e2)) {
                        return true;
                    }
                    deleteLinkInternal(prevE2, e2Toe1LinkName, e1);
                    deleteLinkInternal(e1, e1Toe2LinkName, prevE2);
                }
                if (e2 != null) {
                    final TransientEntity prevE1 = (TransientEntity) e2.getLink(e2Toe1LinkName);
                    if (prevE1 != null) {
                        deleteLinkInternal(prevE1, e1Toe2LinkName, e2);
                    }
                    setLinkInternal(e1, e1Toe2LinkName, e2);
                    setLinkInternal(e2, e2Toe1LinkName, e1);
                }
                return true;
            }
        });
    }

    public void removeOneToMany(@NotNull final TransientEntityImpl one, @NotNull final String manyToOneLinkName,
                                @NotNull final String oneToManyLinkName, @NotNull final TransientEntity many) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                final TransientEntity oldOne = (TransientEntity) many.getLink(manyToOneLinkName);
                if (one.equals(oldOne)) {
                    deleteLinkInternal(many, manyToOneLinkName, oldOne);
                }
                deleteLinkInternal(one, oneToManyLinkName, many);
                return true;
            }
        });
    }

    public void removeFromParent(@NotNull final TransientEntity child, @NotNull final String parentToChildLinkName,
                                 @NotNull final String childToParentLinkName) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                final TransientEntity parent = (TransientEntity) child.getLink(childToParentLinkName);
                if (parent != null) { // may be changed or removed
                    removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child);
                }
                return true;
            }
        });
    }

    public void removeChild(@NotNull final TransientEntityImpl parent, @NotNull final String parentToChildLinkName,
                            @NotNull final String childToParentLinkName) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                final TransientEntity child = (TransientEntity) parent.getLink(parentToChildLinkName);
                if (child != null) { // may be changed or removed
                    removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, child);
                }
                return true;
            }
        });
    }

    private void removeChildFromParentInternal(@NotNull final TransientEntity parent, @NotNull final String parentToChildLinkName,
                                               @Nullable final String childToParentLinkName, @NotNull final TransientEntity child) {
        deleteLinkInternal(parent, parentToChildLinkName, child);
        deletePropertyInternal(child, PARENT_TO_CHILD_LINK_NAME);
        if (childToParentLinkName != null) {
            deleteLinkInternal(child, childToParentLinkName, parent);
            deletePropertyInternal(child, CHILD_TO_PARENT_LINK_NAME);
        }
    }

    public void setChild(@NotNull final TransientEntity parent, @NotNull final String parentToChildLinkName,
                         @NotNull final String childToParentLinkName, @NotNull final TransientEntity child) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                if (removeChildFromCurrentParentInternal(child, childToParentLinkName, parentToChildLinkName, parent)) {
                    final TransientEntity oldChild = (TransientEntity) parent.getLink(parentToChildLinkName);
                    if (oldChild != null) {
                        removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, oldChild);
                    }
                    setLinkInternal(parent, parentToChildLinkName, child);
                    setLinkInternal(child, childToParentLinkName, parent);
                    setPropertyInternal(child, PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName);
                    setPropertyInternal(child, CHILD_TO_PARENT_LINK_NAME, childToParentLinkName);
                }
                return true;
            }
        });
    }

    private boolean removeChildFromCurrentParentInternal(@NotNull final TransientEntity child, @NotNull final String childToParentLinkName,
                                                         @NotNull final String parentToChildLinkName, @NotNull final TransientEntity newParent) {
        final String oldChildToParentLinkName = (String) child.getProperty(CHILD_TO_PARENT_LINK_NAME);
        if (oldChildToParentLinkName != null) {
            if (childToParentLinkName.equals(oldChildToParentLinkName)) {
                final TransientEntity oldParent = (TransientEntity) child.getLink(childToParentLinkName);
                if (oldParent != null) {
                    if (oldParent.equals(newParent)) {
                        return false;
                    }
                    // child to parent link will be owerwritten, so don't delete it directly
                    deleteLinkInternal(oldParent, parentToChildLinkName, child);
                }
            } else {
                final TransientEntity oldParent = (TransientEntity) child.getLink(oldChildToParentLinkName);
                if (oldParent != null) {
                    final String oldParentToChildLinkName = (String) child.getProperty(PARENT_TO_CHILD_LINK_NAME);
                    deleteLinkInternal(oldParent, oldParentToChildLinkName == null ? parentToChildLinkName : oldParentToChildLinkName, child);
                    deleteLinkInternal(child, oldChildToParentLinkName, oldParent);
                }
            }
        }
        return true;
    }

    public void clearChildren(@NotNull final TransientEntity parent, @NotNull final String parentToChildLinkName) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                for (final Entity child : parent.getLinks(parentToChildLinkName)) {
                    final String childToParentLinkName = (String) child.getProperty(CHILD_TO_PARENT_LINK_NAME);
                    removeChildFromParentInternal(parent, parentToChildLinkName, childToParentLinkName, (TransientEntity) child);
                }
                return true;
            }
        });
    }

    public void addChild(@NotNull final TransientEntity parent, @NotNull final String parentToChildLinkName,
                         @NotNull final String childToParentLinkName, @NotNull final TransientEntity child) {
        addChangeAndRun(new MyRunnable() {
            @Override
            public boolean run() {
                if (removeChildFromCurrentParentInternal(child, childToParentLinkName, parentToChildLinkName, parent)) {
                    addLinkInternal(parent, parentToChildLinkName, child);
                    setLinkInternal(child, childToParentLinkName, parent);
                    setPropertyInternal(child, PARENT_TO_CHILD_LINK_NAME, parentToChildLinkName);
                    setPropertyInternal(child, CHILD_TO_PARENT_LINK_NAME, childToParentLinkName);
                }
                return true;
            }
        });
    }

    public Entity getParent(@NotNull final TransientEntity child) {
        final String childToParentLinkName = (String) child.getProperty(CHILD_TO_PARENT_LINK_NAME);

        if (childToParentLinkName == null) {
            return null;
        }

        return child.getLink(childToParentLinkName);
    }

    public boolean addChangeAndRun(MyRunnable change) {
        upgradeReadonlyTransactionIfNecessary();
        return addChange(change).run();
    }

    @Override
    public void insertUniqueKey(@NotNull final Index index,
                                @NotNull final List<Comparable> propValues,
                                @NotNull final Entity entity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteUniqueKey(@NotNull final Index index,
                                @NotNull final List<Comparable> propValues) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void saveEntity(@NotNull Entity entity) {
        throw new UnsupportedOperationException();
    }

    MyRunnable addChange(@NotNull final MyRunnable change) {
        if (allowRunnables) {
            changes.offer(change);
        }
        return change;
    }

    enum State {
        Open("open"),
        Committed("committed"),
        Aborted("aborted");

        private String name;

        State(String name) {
            this.name = name;
        }
    }

    public static interface MyRunnable {
        boolean run();
    }

    private PersistentEntityStoreImpl getPersistentStore() {
        return (PersistentEntityStoreImpl) store.getPersistentStore();
    }
}
