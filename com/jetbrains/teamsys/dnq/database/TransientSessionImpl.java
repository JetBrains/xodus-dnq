package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.core.execution.locks.Latch;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.*;
import jetbrains.exodus.database.persistence.exceptions.PhysicalLayerException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 */
public class TransientSessionImpl extends AbstractTransientSession {

    protected static final Log log = LogFactory.getLog(TransientSessionImpl.class);
    private static final String TEMP_FILE_NAME_SEQUENCE = "__TEMP_FILE_NAME_SEQUENCE__";
    private static final AtomicLong UNIQUE_ID = new AtomicLong(0);

    enum State {
        Open("open"),
        Suspended("suspended"),
        Committed("committed"),
        Aborted("aborted");

        private String name;

        State(String name) {
            this.name = name;
        }
    }

    private Map<String, TransientEntity> localEntities = new HashMapDecorator<String, TransientEntity>();
    protected State state;
    private boolean quietFlush = false;
    private Set<File> createdBlobFiles = new HashSetDecorator<File>();
    protected Latch lock = Latch.create();
    private TransientChangesTracker changesTracker;

    // stores transient entities that were created for loaded persistent entities to avoid double loading
    private Map<EntityId, TransientEntity> createdTransientForPersistentEntities = new HashMap<EntityId, TransientEntity>(100, 1.5f);

    // stores new transient entities to support getEntity(EntityId) operation
    private Map<TransientEntityId, TransientEntity> createdNewTransientEntities = new HashMapDecorator<TransientEntityId, TransientEntity>();

/*
    // stores created readonly entities
    private Map<EntityId, ReadonlyTransientEntityImpl> createdReadonlyTransientEntities = new HashMapDecorator<EntityId, ReadonlyTransientEntityImpl>();
*/

    protected TransientSessionImpl(final TransientEntityStoreImpl store) {
        this(store, UNIQUE_ID.incrementAndGet());
    }

    protected TransientSessionImpl(final TransientEntityStoreImpl store, final long id) {
        super(store, id);

        this.changesTracker = new TransientChangesTrackerImpl(this);

        try {
            lock.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        doResume();
    }

    public String toString() {
        return "id=[" + id + "] state=[" + state + "]";
    }

    public boolean isOpened() {
        return state == State.Open;
    }

    public boolean isCommitted() {
        return state == State.Committed;
    }

    public boolean isSuspended() {
        return state == State.Suspended;
    }

    public boolean isAborted() {
        return state == State.Aborted;
    }

    State getState() {
        return state;
    }

    @NotNull
    public EntityIterable createPersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable) {
        switch (state) {
            case Open:
                // do not wrap twice
                if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
                    return wrappedIterable;
                } else {
                    return new PersistentEntityIterableWrapper(wrappedIterable);
                }

            default:
                throw new IllegalStateException("Can't create wrapper in state [" + state + "]");
        }
    }

    @Nullable
    public Entity addSessionLocalEntity(@NotNull String localName, @Nullable Entity e) throws IllegalStateException {
        switch (state) {
            case Open:
                synchronized (this) {
                    localEntities.put(localName, (TransientEntity) e);
                }
                return e;

            default:
                throw new IllegalStateException("Can't add session local entity in state [" + state + "]");
        }
    }

    @Nullable
    public TransientEntity getSessionLocalEntity(@NotNull String localName) throws IllegalStateException, IllegalArgumentException {
        switch (state) {
            case Open:
                synchronized (this) {
                    return localEntities.get(localName);
                }
            default:
                throw new IllegalStateException("Can't get session local entity in state [" + state + "]");
        }
    }

    public void suspend() {
        if (log.isDebugEnabled()) {
            log.debug("Suspend transient session " + this);
        }

        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't suspend session from another thread.");
        }

        switch (state) {
            case Open:
                try {
                    doSuspend();
                } finally {
                    store.suspendThreadSession();
                }
                break;

            default:
                throw new IllegalStateException("Can't suspend in state " + state);
        }
    }

    public void resume() {
        resume(0);
    }

    public void resume(int timeout) {
        try {
            if (timeout == 0) {
                lock.acquire();
            } else {
                if (!lock.acquire(timeout)) {
                    throw new IllegalStateException("Can't acquire transient session lock. Owner: " + lock.getOwnerName());
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        switch (state) {
            case Suspended:
                doResume();
                break;

            default:
                lock.release();
                throw new IllegalStateException("Can't resume transient session in state [" + state + "]");
        }
    }

    protected final void dispose() {
        localEntities.clear();
        changesTracker.dispose();
        changesTracker = null;
        createdBlobFiles.clear();
        createdNewTransientEntities = null;
        createdTransientForPersistentEntities = null;
    }

    public void intermediateCommit() {
        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't commit session from another thread.");
        }

        final Set<TransientEntityChange> changes = intermediateCommitReturnChanges();
        notifyFlushedListeners(changes);
    }

    public void abort() {
        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't abort session that is not current thread session. Current thread session is [" + store.getThreadSession() + "]");
        }

        if (log.isDebugEnabled()) {
            log.debug("Abort transient session " + this);
        }

        switch (state) {
            case Open:
                try {
                    closePersistentSession();
                } finally {
                    deleteBlobsStore();
                    store.unregisterStoreSession(this);
                    state = State.Aborted;
                    lock.release();
                }
                break;

            default:
                throw new IllegalArgumentException("Can't abort in state " + state);
        }
    }

    public void abort(@NotNull Throwable e) {
        abort();

        if (e instanceof Error) {
            throw (Error) e;
        }

        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }

        throw new RuntimeException(e);
    }

    /*
    * Aborts session in any state
    */

    public void forceAbort() {
        if (log.isDebugEnabled()) {
            log.debug("Unconditional abort transient session " + this);
        }

        // wait for lock
        try {
            if (!lock.acquire(1000)) {
                if (log.isWarnEnabled()) {
                    log.debug("Can't acquire lock for transient session " + this);
                }
                return;
            }
        } catch (InterruptedException e) {
        }

        // after lock acquire, session may be in Suspend, Commited or Aborted states only!
        try {
            switch (state) {
                case Suspended:
                    deleteBlobsStore();
                    store.unregisterStoreSession(this);
                    state = State.Aborted;
                    break;
                case Committed:
                case Aborted:
                    break;
                default:
                    throw new IllegalStateException("Transient session can't be in this state after lock.acquire() [" + state + "]");
            }
        } finally {
            lock.release();
        }
    }

    public void intermediateAbort() {
        if (log.isDebugEnabled()) {
            log.debug("Revert transient session " + this);
        }

        switch (state) {
            case Open:
                doIntermediateAbort();
                break;

            default:
                throw new IllegalArgumentException("Can't revert in state " + state);
        }
    }

    @NotNull
    public StoreSession getPersistentSession() {
        switch (state) {
            case Open:
                return getPersistentSessionInternal();

            default:
                throw new IllegalStateException("Can't access persistent session in state [" + state + "]");
        }
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

        switch (state) {
            case Open:
                return newEntityImpl(persistent);

            default:
                throw new IllegalStateException("Can't create entity in state [" + state + "]");
        }
    }

    /**
     * Creates transient wrapper for existing persistent entity with specified version
     *
     * @param persistent
     * @return
     */
    @NotNull
    public TransientEntity newEntity(@NotNull Entity persistent, final int version) {
        if (persistent instanceof TransientEntity) {
            throw new IllegalArgumentException("Can't create transient entity wrapper for another transient entity.");
        }

        switch (state) {
            case Open:
                return newEntityImpl(persistent, version);

            default:
                throw new IllegalStateException("Can't create entity in state [" + state + "]");
        }
    }

    @Nullable
    public Entity getEntity(@NotNull final EntityId id) {
        switch (state) {
            case Open:
                return getEntityImpl(id);

            default:
                throw new IllegalStateException("Can't get entity in state [" + state + "]");
        }
    }

    @NotNull
    public EntityId toEntityId(@NotNull final String representation) {
        switch (state) {
            case Open:
                // treat given id as id of transient entity first
                try {
                    return getPersistentSessionInternal().toEntityId(representation);
                } catch (Exception e) {
                    return TransientEntityIdImpl.fromString(representation);
                }
            default:
                throw new IllegalStateException("Can't convert to entity id in state [" + state + "]");
        }
    }

    @NotNull
    public List<String> getEntityTypes() {
        switch (state) {
            case Open:
                return getPersistentSessionInternal().getEntityTypes();

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable getAll(@NotNull final String entityType) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().getAll(entityType));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");

        }
    }

    @NotNull
    public EntityIterable getSingletonIterable(@NotNull final Entity entity) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().getSingletonIterable(((AbstractTransientEntity) entity).getPersistentEntityInternal()));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");

        }
    }

    @NotNull
    public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable value) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().find(entityType, propertyName, value));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable minValue, @NotNull final Comparable maxValue) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().find(entityType, propertyName, minValue, maxValue));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    public EntityIterable findWithProp(@NotNull final String entityType, @NotNull final String propertyName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findWithProp(entityType, propertyName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable startsWith(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final String value) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().startsWith(entityType, propertyName, value));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable findWithBlob(@NotNull final String entityType, @NotNull final String propertyName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findWithBlob(entityType, propertyName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable findLinks(@NotNull final String entityType, @NotNull final Entity entity, @NotNull final String linkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findLinks(entityType, entity, linkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable findLinks(@NotNull String entityType, @NotNull EntityIterable entities, @NotNull String linkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findLinks(entityType, entities.getSource(), linkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable findWithLinks(@NotNull String entityType, @NotNull String linkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findWithLinks(entityType, linkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable findWithLinks(@NotNull String entityType,
                                        @NotNull String linkName,
                                        @NotNull String oppositeEntityType,
                                        @NotNull String oppositeLinkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(
                        getPersistentSessionInternal().findWithLinks(entityType, linkName, oppositeEntityType, oppositeLinkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable sort(@NotNull final String entityType,
                               @NotNull final String propertyName,
                               final boolean ascending) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().sort(entityType, propertyName, ascending));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable sort(@NotNull final String entityType,
                               @NotNull final String propertyName,
                               @NotNull final EntityIterable rightOrder,
                               final boolean ascending) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(
                        getPersistentSessionInternal().sort(entityType, propertyName, rightOrder.getSource(), ascending));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable sortLinks(@NotNull final String entityType,
                                    @NotNull final EntityIterable sortedLinks,
                                    boolean isMultiple,
                                    @NotNull final String linkName,
                                    final @NotNull EntityIterable rightOrder) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(
                        getPersistentSessionInternal().sortLinks(entityType, sortedLinks, isMultiple, linkName, rightOrder.getSource()));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable sortLinks(@NotNull final String entityType,
                                    @NotNull final EntityIterable sortedLinks,
                                    boolean isMultiple,
                                    @NotNull final String linkName,
                                    final @NotNull EntityIterable rightOrder,
                                    @NotNull final String oppositeEntityType,
                                    @NotNull final String oppositeLinkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(
                        getPersistentSessionInternal().sortLinks(entityType, sortedLinks, isMultiple, linkName, rightOrder.getSource(), oppositeEntityType, oppositeLinkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable mergeSorted(@NotNull List<EntityIterable> sorted, @NotNull Comparator<Entity> comparator) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().mergeSorted(sorted, comparator));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable distinct(@NotNull final EntityIterable source) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().distinct(source.getSource()));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable selectDistinct(@NotNull EntityIterable source, @NotNull String linkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().selectDistinct(source.getSource(), linkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable selectManyDistinct(@NotNull EntityIterable source, @NotNull String linkName) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().selectManyDistinct(source.getSource(), linkName));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @Nullable
    public Entity getFirst(@NotNull final EntityIterable it) {
        switch (state) {
            case Open:
                final Entity last = getPersistentSessionInternal().getFirst(it.getSource());
                return (last == null) ? null : newEntityImpl(last);

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @Nullable
    public Entity getLast(@NotNull final EntityIterable it) {
        switch (state) {
            case Open:
                final Entity last = getPersistentSessionInternal().getLast(it.getSource());
                return (last == null) ? null : newEntityImpl(last);

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable reverse(@NotNull final EntityIterable source) {
        switch (state) {
            case Open:
                return new PersistentEntityIterableWrapper(getPersistentSessionInternal().reverse(source.getSource()));

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    @NotNull
    public Sequence getSequence(@NotNull final String sequenceName) {
        switch (state) {
            case Open:
                return getPersistentSessionInternal().getSequence(sequenceName);

            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    public void clearHistory(@NotNull final String entityType) {
        switch (state) {
            case Open:
                changesTracker.historyCleared(entityType);
                break;
            default:
                throw new IllegalStateException("Can't execute in state [" + state + "]");
        }
    }

    public void updateUniqueKeyIndices(@NotNull Set<Index> indices) {
        throw new UnsupportedOperationException();
    }


    public void insertUniqueKey(@NotNull final Index index,
                                @NotNull final List<Comparable> propValues,
                                @NotNull final Entity entity) {
        throw new UnsupportedOperationException();
    }

    public void deleteUniqueKey(@NotNull final Index index,
                                @NotNull final List<Comparable> propValues) {
        throw new UnsupportedOperationException();
    }

    @NotNull
    public File createBlobFile(boolean createNewFile) {
        final File blobsStore = store.getBlobsStore();
        String fileName = generateBlobFileName();
        File f = new File(blobsStore, fileName);
        if (createNewFile) {
            try {
                while (!f.createNewFile()) {
                    fileName = generateBlobFileName();
                    f = new File(blobsStore, fileName);
                }
            } catch (IOException e) {
                throw new PhysicalLayerException("Can't create blob file [" + blobsStore.getAbsolutePath() + "/" + fileName + "]", e);
            }
        }

        createdBlobFiles.add(f);
        return f;
    }

    private String generateBlobFileName() {
        final StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(id);
        nameBuilder.append('-');
        nameBuilder.append(getPersistentSessionInternal().getSequence(TEMP_FILE_NAME_SEQUENCE).increment());
        nameBuilder.append(".dat");
        return nameBuilder.toString();
    }

    public void quietIntermediateCommit() {
        final boolean qf = quietFlush;
        try {
            this.quietFlush = true;
            intermediateCommit();
        } finally {
            this.quietFlush = qf;
        }
    }

    protected void closePersistentSession() {
        if (log.isDebugEnabled()) {
            log.debug("Close persistent session for transient session " + this);
        }
        StoreSession persistentSession = getPersistentSessionInternal();
        if (persistentSession != null) {
            persistentSession.close();
        }
    }

    protected void doResume() {
        if (log.isDebugEnabled()) {
            log.debug("Open persistent session for transient session " + this);
        }
        store.getPersistentStore().beginSession();
        state = State.Open;
    }


    @NotNull
    public TransientChangesTracker getTransientChangesTracker() throws IllegalStateException {
        switch (state) {
            case Open:
                return changesTracker;

            default:
                throw new IllegalStateException("Can't access changes tracker in state [" + state + "]");
        }
    }

    public void commit() {
        // this method is overridden in TransientSessionDeferred, but that's ok (changes are isomorphic)

        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't commit session from another thread.");
        }

        if (log.isDebugEnabled()) {
            log.debug("Commit transient session " + this);
        }

        switch (state) {
            case Open:
                // flush may produce runtime exceptions. if so - session stays open
                Set<TransientEntityChange> changes = flush();

                try {
                    notifyCommitedListeners(changes);
                } finally {
                    try {
                        closePersistentSession();
                    } finally {
                        deleteBlobsStore();
                        store.unregisterStoreSession(this);
                        state = State.Committed;
                        dispose();
                        lock.release();
                    }
                }

                break;

            default:
                throw new IllegalArgumentException("Can't commit in state " + state);
        }
    }

    private Set<TransientEntityChange> intermediateCommitReturnChanges() throws IllegalStateException {
        if (log.isDebugEnabled()) {
            log.debug("Intermidiate commit transient session " + this);
        }

        switch (state) {
            case Open:
                // flush may produce runtime exceptions. if so - session stays open
                Set<TransientEntityChange> changes = flush();
                return changes;

            default:
                throw new IllegalArgumentException("Can't commit in state " + state);
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
            if (!e.isRemovedOrTemporary()) {
                EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());

                if (emd != null && emd.hasAggregationChildEnds() && !EntityMetaDataUtils.hasParent(emd, e, changesTracker)) {
                    if (emd.getRemoveOrphan()) {
                        // has no parent - remove
                        if (log.isDebugEnabled()) {
                            log.debug("Remove orphan: " + e);
                        }

                        EntityOperations.remove(e);
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
    public Entity newEntity(@NotNull final String entityType) {
        switch (state) {
            case Open:
                TransientEntity e = new TransientEntityImpl(entityType, this);
                createdNewTransientEntities.put((TransientEntityId) e.getId(), e);
                return e;

            default:
                throw new IllegalStateException("Can't create entity in state [" + state + "]");
        }
    }

    public TransientEntity newReadonlyLocalCopy(final TransientEntityChange change) {
        switch (state) {
            case Open:
                AbstractTransientEntity orig = (AbstractTransientEntity) change.getTransientEntity();
                switch (orig.getState()) {
                    case New:
                        throw new IllegalStateException("Can't create readonly local copy of entity in new state.");

                    case Temporary:
                        return orig;

                    case Saved:
                    case SavedNew:
                    case RemovedNew:
                    case RemovedSaved:
                    case RemovedSavedNew:
                        final ReadonlyTransientEntityImpl entity = new ReadonlyTransientEntityImpl(change, this);
                        return entity;

                    default:
                        throw new IllegalStateException("Can't create readonly local copy in state [" + orig.getState() + "]");
                }
            default:
                throw new IllegalStateException("Can't create readonly local copy in state [" + state + "]");
        }
    }

    /**
     * Creates local copy of given entity in current session.
     *
     * @param entity
     * @return
     */
    @NotNull
    public TransientEntity newLocalCopy(@NotNull final TransientEntity entity) {
        switch (state) {
            case Open:
                if (entity.isTemporary() || entity.isReadonly()) {
                    return entity;
                } else if (entity.isNew()) {
                    if (entity.getSessionId() == id) {
                        // this is transient entity that was created in this session
                        // check session wasn't reverted
                        if (this.createdNewTransientEntities.get(entity.getId()) == entity) {
                            return entity;
                        } else {
                            throw new IllegalStateException("Can't create local copy of transient entity in New state from reverted session." + entity);
                            //return null;
                        }
                    } else {
                        throw new IllegalStateException("Can't create local copy of transient entity in New state from another session. " + entity);
                    }
                } else if (entity.isSaved()) {
                    final EntityId entityId = entity.getId();
                    if (entity.getSessionId() == id && createdTransientForPersistentEntities.get(entityId) == entity) {
                        // was created in this session and session wasn't reverted
                        return entity;
                    } else {
                        // saved entity from another session or from reverted session - load it from database by id
                        // local copy already created?
                        TransientEntity localCopy = createdTransientForPersistentEntities.get(entityId);
                        if (localCopy != null) {
                            if (localCopy.isRemoved()) {
                                EntityRemovedException entityRemovedException = new EntityRemovedException(entity);
                                log.warn("Local copy of entity [" + entity + "] was removed by you.");
                                throw entityRemovedException;
                            }
                            return localCopy;
                        }

                        try {
                            // load persistent entity from database by id
                            return newEntity(getPersistentSessionInternal().getEntity(entityId));
                        } catch (EntityRemovedInDatabaseException e) {
                            log.warn("Entity [" + entity + "] was removed in database, can't create local copy.");
                            throw e;
                        }
                    }
                } else if (entity.isRemoved()) {
                    EntityRemovedException entityRemovedException = new EntityRemovedException(entity);
                    log.warn("Entity [" + entity + "] was removed by you.");
                    throw entityRemovedException;
                }

            default:
                throw new IllegalStateException("Can't create local copy in state [" + state + "]");
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
            if (transientEntity.isRemoved()) {
                return true;
            } else if (transientEntity.isSaved()) {
                // saved entity from another session or from reverted session
                entityId = entity.getId();
                if (createdTransientForPersistentEntities.get(entityId) != transientEntity) {
                    // local copy already created?
                    TransientEntity localCopy = createdTransientForPersistentEntities.get(entityId);
                    if (localCopy != null && localCopy.isRemoved()) {
                        return true;
                    }
                }
            } else if (transientEntity.isTemporary() || transientEntity.isReadonly() || transientEntity.isNew()) {
                return false;
            }
        }
        // load persistent entity from database by id
        if (entityId == null) {
            entityId = entity.getId();
        }
        return ((PersistentEntityStore) getPersistentSessionInternal().getStore()).getLastVersion(entityId) < 0;
    }

    public void registerEntityIterator(@NotNull EntityIterator iterator) {
        //TODO: revisit StoreSession interface and remove these stub method
    }

    public void deregisterEntityIterator(@NotNull EntityIterator iterator) {
        //TODO: revisit StoreSession interface and remove these stub method
    }

    /**
     * Entities created as new ones (using the "new" operator, not "new transient") which are
     * the childs of temporary parents (created using the "new transient" operator, or by induction)
     */
    private void transformNewChildsOfTempoparyParents() {
        for (final TransientEntity e : changesTracker.getChangedEntities()) {
            if (e.isNew()) {
                TransientEntity parent = (TransientEntity) AggregationAssociationSemantics.getParent(e);
                HashSet<TransientEntity> lookedParents = new HashSet<TransientEntity>();
                while (parent != null && parent.isNewOrTemporary()) {
                    if (!(lookedParents.add(parent))) {
                        log.warn("Found parent cycle: " + parent.toString());
                        break;
                    }
                    if (parent.isTemporary()) {
                        e.markAsTemporary();
                        break;
                    }
                    parent = (TransientEntity) AggregationAssociationSemantics.getParent(parent);
                }
            }
        }
    }

    /**
     * Checks constraints before save changes
     *
     * @param exceptions resulting errors set
     */
    private void checkBeforeSaveChangesConstraints(@NotNull final Set<DataIntegrityViolationException> exceptions) {
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
        exceptions.addAll(ConstraintsUtil.checkIncomingLinks(changesTracker, modelMetaData));

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
            e.fixEntityId();
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
     *
     * @return side effects
     */
    @Nullable
    private Set<TransientEntity> executeBeforeFlushTriggers(Set<TransientEntity> changedEntities, Set<TransientEntity> processedEntities) {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            if (log.isDebugEnabled()) {
                log.warn("Quiet intermediate commit: skip before flush triggers. " + this);
            }
            return null;
        }

        if (log.isDebugEnabled()) {
            log.debug("Execute before flush triggers. " + this);
        }

        final Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();
        final int changesEntitiesCount = ((TransientChangesTrackerImpl) changesTracker).getChangedEntities().size();
        for (TransientEntity entity : changedEntities) {
            if (!entity.isRemoved()) {
                EntityMetaData md = modelMetaData.getEntityMetaData(entity.getType());

                // meta-data may be null for persistent enums
                if (md != null) {
                    try {
                        TransientStoreUtil.getPersistentClassInstance(entity, md).executeBeforeFlushTrigger(entity);
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
            e.fixEntityId();
            throw e;
        }

        if (changesEntitiesCount != ((TransientChangesTrackerImpl) changesTracker).getChangedEntities().size()) {
            processedEntities.addAll(changedEntities);

            HashSet<TransientEntity> sideEffect = new HashSet<TransientEntity>(changesTracker.getChangedEntities());
            sideEffect.removeAll(processedEntities);

            return sideEffect;
        }

        return null;
    }

    /**
     * Flushes changes
     *
     * @return changed description excluding deleted entities
     */
    @Nullable
    protected final Set<TransientEntityChange> flush() {
        if (!changesTracker.areThereChanges()) {
            log.trace("Nothing to flush.");
            return null;
        }

        beforeFlush();

        checkDatabaseState();
        transformNewChildsOfTempoparyParents();
        // TODO: this method checks incomming links, but doesn't lock entities to remove after check, so new links may appear after this check but before low level remove
        checkBeforeSaveChangesConstraints(removeOrphans());

        // check if nothing to persist
        boolean onlyTemporary = true;
        for (TransientEntity te : changesTracker.getChangedEntities()) {
            if (!te.isTemporary()) {
                onlyTemporary = false;
                break;
            }
        }

        // remember changes before commit changes, because all New entities become SavedNew after it
        final Set<TransientEntityChange> changesDescription = changesTracker.getChangesDescription();
        if (!onlyTemporary) {
            notifyBeforeFlushAfterConstraintsCheckListeners();

            int retry = 0;
            Throwable lastEx = null;

            while (retry++ < flushRetryOnVersionMismatch) {
                StoreTransaction persistentTransaction = null;
                try {
                    try {
                        persistentTransaction = getPersistentSessionInternal().beginTransaction();
                    } catch (jetbrains.exodus.exceptions.VersionMismatchException e) {
                        Thread.yield();
                        continue;
                    }

                    // lock entities to be updated by current transaction
                    // TODO: move to the first line of the method - before constraints check
                    lockForUpdate(persistentTransaction);

                    // check versions before commit changes
                    checkVersions();

                    // save history if meta data defined
                    saveHistory();

                    // commit changes
                    for (Runnable c : changesTracker.getChanges()) {
                        c.run();
                    }

                    if (log.isTraceEnabled()) {
                        log.trace("Commit persistent transaction in transient session " + this);
                    }

                    final StoreTransaction txn = persistentTransaction;
                    persistentTransaction = null;
                    txn.commit();

                    updateCaches();

                    lastEx = null;
                    break;
                } catch (Throwable e) {
                    lastEx = e;
                    log.error("Catch exception in flush: " + e.getMessage());

                    try {
                        if (persistentTransaction != null) persistentTransaction.abort();
                    } catch (Throwable e1) {
                        lastEx = e1;
                        break;
                    }

                    if (e instanceof jetbrains.exodus.exceptions.VersionMismatchException) {
                        // check versions before commit changes
                        checkVersions();
                        Thread.yield();
                        rollbackTransientTrackerChanges(false);
                    } else {
                        break;
                    }
                }
            }

            if (lastEx != null) {
                // rollback
                // tracker make some changes in transient entities - rollback them
                try {
                    rollbackTransientTrackerChanges(true);
                    fixEntityIdsInDataIntegrityViolationException(lastEx);
                } finally {
                    decodeException(lastEx);
                }
            }

        }

        changesTracker.clear();
        return changesDescription;
    }

    private void beforeFlush() {
        // notify listeners, execute before flush, if were side effects, do the same for side effects

        Set<TransientEntityChange> changesDescription = changesTracker.getChangesDescription();
        Set<TransientEntity> sideEffects = new HashSet<TransientEntity>(changesTracker.getChangedEntities());
        Set<TransientEntity> processedEntities = new HashSetDecorator<TransientEntity>();

        while (sideEffects != null && !sideEffects.isEmpty()) {
            notifyBeforeFlushListeners(changesDescription);
            sideEffects = executeBeforeFlushTriggers(sideEffects, processedEntities);

            if (sideEffects != null && !sideEffects.isEmpty()) {
                changesDescription = new HashSet<TransientEntityChange>();
                for (TransientEntity sideEffectEntity : sideEffects) {
                    changesDescription.add(changesTracker.getChangeDescription(sideEffectEntity));
                }
            }
        }
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

    private void checkDatabaseState() {
        if (store.getPersistentStore().isReadonly()) {
            throw new DatabaseStateIsReadonlyException("maintenance is in progress. Please repeat your action later.");
        }
    }

    private void fixEntityIdsInDataIntegrityViolationException(Throwable e) {
        // fix entity ids - we can do it after rollback tracker changes
        if (e instanceof DataIntegrityViolationException) {
            ((DataIntegrityViolationException) e).fixEntityId();
        }
    }

    private void rollbackTransientTrackerChanges(boolean isFinalRollback) {
        if (log.isDebugEnabled()) {
            log.debug("Rollback transient entities changes made by changes tracker." + this);
        }
        for (TransientChangesTracker.Rollback r : changesTracker.getRollbackChanges(isFinalRollback)) {
            try {
                r.rollback(isFinalRollback);
            } catch (Exception e1) {
                log.trace("Error while rollback changes made by changes tracker", e1);
            }
        }
    }

    private void updateCaches() {
        // all new transient entities were saved or removed - clear cache
        // FIX: do not clear cache of new entities to support old IDs for Webr
        // createdNewTransientEntities.clear();
        // reload version of changed persistent entities, remove removed and clear caches

        for (TransientEntity e : changesTracker.getChangedEntities()) {
            if (e.isNew()) {
                throw new IllegalStateException("Bug! New transient entity after commit!");

            } else if (e.isRemovedOrTemporary()) {

                //createdTransientForPersistentEntities.remove(e.getId());
                //createdNewTransientEntities.remove(e.getId());

            } else if (e.isSaved()) {

                if (log.isTraceEnabled()) {
                    log.trace("Update version of cached persistent entity and clear blob cache " + e);
                }

                ((TransientEntityImpl) e).updateVersion();
                ((TransientEntityImpl) e).clearFileBlobsCache();
                ((TransientEntityImpl) e).updateLinkManagers();

                if (e.wasNew()) {
                    // add to persistent
                    createdTransientForPersistentEntities.put(e.getId(), e);

                    // leave it in createdNewTransientEntities for getting  it by old TransientEntityIdImpl
                }
            }
        }

        // do not clear this cache to have ability to request entities using old TransientEntityIdImpl
        //createdNewTransientEntities.clear();
    }

    /**
     * locks exclusively all entities affected by the transaction in order to prevent
     * race condition during checking version mismatches.
     */
    private void lockForUpdate(@NotNull final StoreTransaction txn) {
        final Set<TransientEntity> changedPersistentEntities = changesTracker.getChangedPersistentEntities();
        final int changedEntitiesCount = changedPersistentEntities.size();
        if (changedEntitiesCount > 0) {
            final List<Entity> affected = new ArrayList<Entity>(changedEntitiesCount);
            for (final TransientEntity localCopy : changedPersistentEntities) {
                //if (/*!localCopy.isNew() && */!localCopy.isRemoved()) {
                Entity e = ((TransientEntityImpl) localCopy).getPersistentEntityInternal();
                affected.add(e);
                //}
            }
            txn.lockForUpdate(affected);
        }
    }

    private void checkVersions() {
        if (quietFlush) {
            if (log.isWarnEnabled()) {
                log.warn("Quiet intermediate commit: skip versions checking. " + this);
            }

            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Check versions of changed entities. " + this);
        }
        // check versions of changed persistent entities
        Set<TransientEntity> changedPersistentEntities = changesTracker.getChangedPersistentEntities();

        final PersistentEntityStore persistentStore = (PersistentEntityStore) getStore().getPersistentStore();

        for (TransientEntity entity : changedPersistentEntities) {
            if (log.isDebugEnabled()) {
                log.debug("Check version of: " + entity);
            }

            final TransientEntityImpl localCopy = (TransientEntityImpl) entity;
            final PersistentEntityId id = localCopy.getPersistentEntityInternal().getId();
            int lastDatabaseCopyVersion = persistentStore.getLastVersion(id);
            if (lastDatabaseCopyVersion < 0) {
                if (entity.isRemoved()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Entity " + entity + " was removed from database, but is in removed state on transient level, hence flush is not terminated.");
                    }
                    // dont't check version mismatch
                    continue;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Entity was removed from database:" + entity);
                    }
                    throw new EntityRemovedInDatabaseException(id, persistentStore);
                }
            }

            int localCopyVersion = localCopy.getVersionInternal();

            if (log.isDebugEnabled()) {
                log.debug("Entity [" + entity + "] localVersion=" + localCopyVersion + " databaseVersion=" + lastDatabaseCopyVersion);
            }

            if (localCopyVersion != lastDatabaseCopyVersion) {
                // TODO: try to merge changes,
                // i.e. check if new changes may be added to existing without conflicts and,
                // if can, load
                //TODO: delegate to MergeHandler. This handler should be generated or handcoded for concrete problem domain.
                if (!areChangesCompatible(entity)) {
                    if (log.isDebugEnabled()) {
                        log.warn("Incompatible concurrent changes for " + entity + ". Changed properties [" + TransientStoreUtil.toString(changesTracker.getChangedPropertiesDetailed(entity)) +
                                "] changed links [" + TransientStoreUtil.toString(changesTracker.getChangedLinksDetailed(entity)) + "]");
                    }
                    throw new VersionMismatchException(entity, localCopyVersion, lastDatabaseCopyVersion);
                }
            }
        }
    }

    /**
     * Analize changes for given entity and return true if changes may be saved into database without conflicts
     *
     * @param entity
     * @return
     */
    private boolean areChangesCompatible(TransientEntity entity) {
        ModelMetaData md = store.getModelMetaData();
        EntityMetaData emd = md == null ? null : md.getEntityMetaData(entity.getType());
        if (emd == null) {
            if (log.isWarnEnabled()) {
                log.warn("Metadata for " + entity + " is not found. Can't merge changes. " + this);
            }
            return false;
        }

        // ignore version mismatch
        if (emd.isVersionMismatchIgnoredForWholeClass()) {
            return true;
        }

        // compatible changes:
        // 1. add association (for multiple associations)
        // 2. changed links is marked as versionMismatchResolution: ignore

        TransientEntityChange tec = changesTracker.getChangeDescription(entity);

        Map<String, LinkChange> changedLinks = tec.getChangedLinksDetaled();
        if (changedLinks != null) {
            for (LinkChange lc : changedLinks.values()) {
                final String linkName = lc.getLinkName();
                if (!((emd.isVersionMismatchIgnored(linkName)) || (lc.getChangeType() == LinkChangeType.ADD && emd.getAssociationEndMetaData(linkName).getCardinality().isMultiple()))) {
                    return false;
                }
            }
        }

        // 3. changed property is marked as versionMismatchResolution: ignore
        Map<String, PropertyChange> changedProps = tec.getChangedPropertiesDetaled();
        if (changedProps != null) {
            for (String p : changedProps.keySet()) {
                if (!emd.isVersionMismatchIgnored(p)) {
                    return false;
                }
            }
        }

        return true;
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

        final Set<TransientEntity> changedPersistentEntities = changesTracker.getChangedPersistentEntities();

        for (final TransientEntity e : changedPersistentEntities.toArray(new TransientEntity[changedPersistentEntities.size()])) {
            if (!e.isNew() && !e.isRemovedOrTemporary()) {
                final EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());
                if (emd != null && TransientStoreUtil.getPersistentClassInstance(e, emd).evaluateSaveHistoryCondition(e)
                        && EntityMetaDataUtils.changesReflectHistory(emd, e, changesTracker)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Save history of: " + e);
                    }
                    e.newVersion();

                    // !!! should be called after e.newVersion();
                    TransientStoreUtil.getPersistentClassInstance(e, emd).saveHistoryCallback(e);
                }
            }
        }
    }

    protected void deleteBlobsStore() {
        for (File f : createdBlobFiles) {
            if (f.exists() && !f.delete()) {
                log.warn("Can't delete temp blob file [" + f.getAbsolutePath() + "]");
                f.deleteOnExit();
                //TODO: start background threads that periodically tries to delete file. FileCleaner can't be used.
            }
        }

        createdBlobFiles.clear();
    }

    protected final void notifyCommitedListeners(final Set<TransientEntityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Notify commited listeners " + this);
        }

        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.commited(changes);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                }
            }
        });
    }

    private void notifyFlushedListeners(final Set<TransientEntityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Notify flushed listeners " + this);
        }

        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.flushed(changes);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // do not rethrow exception
                }
            }
        });
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

    private void notifyBeforeFlushAfterConstraintsCheckListeners() {
        final Set<TransientEntityChange> changes = changesTracker.getChangesDescription();

        if (changes == null || changes.isEmpty()) return;

        if (log.isDebugEnabled()) {
            log.debug("Notify before flush after constraints check listeners " + this);
        }

        // check side effects in listeners
        final int changesCount = ((TransientChangesTrackerImpl) changesTracker).getChangesCount();
        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.beforeFlushAfterConstraintsCheck(changes);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // do not rethrow exceptipon, because we are after constaints check
                }
            }
        });

        if (((TransientChangesTrackerImpl) changesTracker).getChangesCount() != changesCount) {
            throw new EntityStoreException("It's not allowed to change database inside listener.beforeFlushAfterConstraintsCheck() method.");
        }
    }

    protected void doSuspend() {
        try {
            closePersistentSession();
        } finally {
            state = State.Suspended;
            lock.release();
        }
    }

    protected void doIntermediateAbort() {
        deleteBlobsStore();
        createdTransientForPersistentEntities.clear();
        createdNewTransientEntities.clear();
        changesTracker = new TransientChangesTrackerImpl(this);
    }

    protected TransientEntity newEntityImpl(final Entity persistent) {
        final EntityId entityId = persistent.getId();
        TransientEntity e = createdTransientForPersistentEntities.get(entityId);
        if (e == null) {
            e = new TransientEntityImpl(persistent, this);
            createdTransientForPersistentEntities.put(entityId, e);
        }
        return e;
    }

    protected TransientEntity newEntityImpl(final Entity persistent, final int version) {
        final EntityId entityId = persistent.getId();
        TransientEntity e = createdTransientForPersistentEntities.get(entityId);
        if (e == null) {
            e = new TransientEntityImpl(persistent, this, version);
            createdTransientForPersistentEntities.put(entityId, e);
        }
        return e;
    }

    @Nullable
    protected Entity getEntityImpl(@NotNull final EntityId id) {
        if (id instanceof TransientEntityId) {
            return createdNewTransientEntities.get(id);
        } else {
            TransientEntity e = createdTransientForPersistentEntities.get(id);
            if (e == null) {
                PersistentEntityStoreImpl persistentEntityStore = (PersistentEntityStoreImpl) store.getPersistentStore();
                if (persistentEntityStore.getLastVersion(id) < 0) {
                    return null;
                }
                return newEntity(persistentEntityStore.getEntity(id));
            } else {
                return e;
            }
        }
    }

}
