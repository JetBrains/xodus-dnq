package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.execution.locks.Latch;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.*;
import com.jetbrains.teamsys.database.persistence.exceptions.LockConflictException;
import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
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
    protected static final Log logForDumps = LogFactory.getLog("DNQDUMPS");
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

    // stores created readonly entities
    private Map<EntityId, ReadonlyTransientEntityImpl> createdReadonlyTransientEntities = new HashMapDecorator<EntityId, ReadonlyTransientEntityImpl>();

    protected TransientSessionImpl(final TransientEntityStoreImpl store, final String name) {
        this(store, name, UNIQUE_ID.incrementAndGet());
    }

    protected TransientSessionImpl(final TransientEntityStoreImpl store, final String name, final Object id) {
        super(store, name, id);

        this.changesTracker = new TransientChangesTrackerImpl(this);

        try {
            lock.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        doResume();
    }

    public String toString() {
        return "[" + name + "] id=[" + id + "] state=[" + state + "]";
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
                }
                finally {
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
                final Entity first = getPersistentSessionInternal().getFirst(it.getSource());
                return (first == null) ? null : newEntityImpl(first);

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
        String fileName = id + "-" + getPersistentSessionInternal().getSequence(TEMP_FILE_NAME_SEQUENCE).increment() + ".dat";
        File f = new File(store.getBlobsStore(), fileName);
        if (createNewFile) {
            try {
                if (!f.createNewFile()) {
                    throw new IllegalArgumentException("Can't create blob file [" + store.getBlobsStore().getAbsolutePath() + "/" + fileName + "]");
                }
            } catch (IOException e) {
                throw new RuntimeException("Can't create blob file [" + store.getBlobsStore().getAbsolutePath() + "/" + fileName + "]", e);
            }
        }

        createdBlobFiles.add(f);
        return f;
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

                if (emd != null && emd.hasAggregationChildEnds() && !emd.hasParent(e, changesTracker)) {
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

    public TransientEntity newReadonlyLocalCopy(TransientEntityChange change) {
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
                        EntityId entityId = orig.getPersistentEntityInternal().getId();
                        ReadonlyTransientEntityImpl entity = createdReadonlyTransientEntities.get(entityId);
                        if (entity == null) {
                            entity = new ReadonlyTransientEntityImpl(change, this);
                            createdReadonlyTransientEntities.put(entityId, entity);
                        }
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
    public TransientEntity newLocalCopy(@NotNull final TransientEntity entity) {
        switch (state) {
            case Open:
                if (entity.isTemporary() || entity.isReadonly()) {
                    return entity;
                } else if (entity.isNew()) {
                    if (((TransientEntityImpl) entity).getTransientStoreSession() == this) {
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
                    if (entity.getTransientStoreSession() == this && createdTransientForPersistentEntities.get(entity.getId()) == entity) {
                        // was created in this session and session wasn't reverted
                        return entity;
                    } else {
                        // saved entity from another session or from reverted session - load it from database by id
                        EntityId id = entity.getId();
                        // local copy already created?
                        TransientEntity localCopy = createdTransientForPersistentEntities.get(id);
                        if (localCopy != null) {
                            return localCopy.isRemoved() ? null : localCopy;
                        }

                        // load persistent entity from database by id
                        Entity databaseCopy = getPersistentSessionInternal().getEntity(id);
                        if (databaseCopy == null) {
                            // entity was removed - can't create local copy
                            log.warn("Entity [" + entity + "] was removed in database, can't create local copy, return null.");
                            return null;
                            // throw new EntityRemovedInDatabaseException(entity);
                        }

                        return newEntity(databaseCopy);
                    }
                } else if (entity.isRemoved()) {
                    log.warn("Entity [" + entity + "] was by you, return null.");
                    return null;
                    //throw new EntityRemovedException(entity);
                }

            default:
                throw new IllegalStateException("Can't create local copy in state [" + state + "]");
        }
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
                while (parent != null && parent.isNewOrTemporary()) {
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
     * @param exceptions resulting errors set
     */
    private void checkBeforeSaveChangesConstraints(@NotNull Set<DataIntegrityViolationException> exceptions) {
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

        // 3. check index fields
        exceptions.addAll(ConstraintsUtil.checkIndexFields(changesTracker, modelMetaData));

        if (exceptions.size() != 0) {
            ConstraintsValidationException e = new ConstraintsValidationException(exceptions);
            e.fixEntityId();
            throw e;
        }
    }

    /**
     * Checks custom flush constraints before save changes
     */
    private void executeBeforeFlushTriggers() {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            if (log.isDebugEnabled()) {
                log.warn("Quiet intermediate commit: skip custom flush constraints checking. " + this);
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Check custom flush constraints. " + this);
        }

        final Set<DataIntegrityViolationException> triggerErrors =
            ConstraintsUtil.executeBeforeFlushTriggers(changesTracker, modelMetaData);

        if (triggerErrors.size() != 0) {
            ConstraintsValidationException e = new ConstraintsValidationException(triggerErrors);
            e.fixEntityId();
            throw e;
        }
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

        notifyBeforeFlushListeners();
        executeBeforeFlushTriggers();
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
            
            StoreTransaction transaction = null;
            try {
                if (log.isTraceEnabled()) {
                    log.trace("Open persistent transaction in transient session " + this, new Throwable());
                }

                transaction = getPersistentSessionInternal().beginTransaction();

                // lock entities to be updated by current transaction
                // TODO: move to the first line of the method - before constraints check
                lockForUpdate(transaction);

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

                transaction.commit();

                updateCaches();

            } catch (Throwable e) {
                // tracker make some changes in transient entities - rollback them
                try {
                    logThreadsDump(e);
                    rollbackTransientTrackerChanges();
                    fixEntityIdsInDataIntegrityViolationException(e);
                } finally {
                    abort(e, transaction);
                }
            }
        }

        changesTracker.clear();

        return changesDescription;
    }

    private void logThreadsDump(Throwable e) {
        if (logForDumps.isErrorEnabled()) {
            if (e.getCause() instanceof LockConflictException) {
                final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
                for (Thread t : stackTraces.keySet()) {
                    logForDumps.error(t);
                    final StackTraceElement[] traceElements = stackTraces.get(t);
                    StringBuilder builder = new StringBuilder();
                    for (StackTraceElement traceElement : traceElements) {
                        builder.append("    ");
                        builder.append(traceElement);
                        builder.append('\n');
                    }
                    logForDumps.error(builder);
                }
            }
        }
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

    private void rollbackTransientTrackerChanges() {
        if (log.isDebugEnabled()) {
            log.debug("Rollback transient entities changes made by changes tracker." + this);
        }
        for (Runnable r : changesTracker.getRollbackChanges()) {
            try {
                r.run();
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
        //TODO: remove this method if only one tran can be flushed concurrently
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

        for (TransientEntity localCopy : changedPersistentEntities) {
            if (log.isDebugEnabled()) {
                log.debug("Check version of: " + localCopy);
            }

            // use internal getter, because local copy entity may be removed
            //Entity lastDatabaseCopy = ((TransientEntityImpl)localCopy).getLastVersionInternal();
            Entity lastDatabaseCopy = ((TransientEntityImpl) localCopy).getPersistentEntityInternal().getUpToDateVersion();
            if (lastDatabaseCopy == null) {
                if (localCopy.isRemoved()) {
                    Exception ex;
                    try {
                        throw new EntityRemovedInDatabaseException(localCopy);
                    } catch (EntityRemovedInDatabaseException er) {
                        ex = er;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Entity " + localCopy + " was removed from database, but is in removed state on transient level, hence flush is not terminated.", ex);
                    }
                    // dont't check version mismatch  
                    continue;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Entity was removed from database:" + localCopy);
                    }
                    throw new EntityRemovedInDatabaseException(localCopy);
                }
            }

            int localCopyVersion = ((TransientEntityImpl) localCopy).getVersionInternal();
            int lastDatabaseCopyVersion = lastDatabaseCopy.getVersion();

            if (log.isDebugEnabled()) {
                log.debug("Entity [" + localCopy + "] localVersion=" + localCopyVersion + " databaseVersion=" + lastDatabaseCopyVersion);
            }

            if (localCopyVersion != lastDatabaseCopyVersion) {
                // TODO: try to merge changes,
                // i.e. check if new changes may be added to existing without conflicts and,
                // if can, load
                //TODO: delegate to MergeHandler. This handler should be generated or handcoded for concrete problem domain.
                if (!areChangesCompatible(localCopy)) {
                    if (log.isDebugEnabled()) {
                        log.warn("Incompatible concurrent changes for " + localCopy + ". Changed properties [" + TransientStoreUtil.toString(changesTracker.getChangedPropertiesDetailed(localCopy)) +
                            "] changed links [" + TransientStoreUtil.toString(changesTracker.getChangedLinksDetailed(localCopy)) + "]");
                    }
                    throw new VersionMismatchException(localCopy, localCopyVersion, lastDatabaseCopyVersion);
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
                if (emd != null && emd.getInstance(e).evaluateSaveHistoryCondition(e) && emd.changesReflectHistory(e, changesTracker)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Save history of: " + e);
                    }
                    e.newVersion();

                    // !!! should be called after e.newVersion();
                    emd.getInstance(e).saveHistoryCallback(e);
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
                }
            }
        });
    }

    private void notifyBeforeFlushListeners() {
        final Set<TransientEntityChange> changes = changesTracker.getChangesDescription();

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
        changesTracker.markState();
        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.beforeFlushAfterConstraintsCheck(changes);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                }
            }
        });

        if (changesTracker.wereChangesAfterMarkState()) {
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

    protected TransientEntity newEntityImpl(Entity persistent) {
        final EntityId entityId = persistent.getId();
        TransientEntity e = createdTransientForPersistentEntities.get(entityId);
        if (e == null) {
            e = new TransientEntityImpl(persistent, this);
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
                Entity _e = getPersistentSessionInternal().getEntity(id);

                return _e == null ? null : newEntity(_e);
            } else {
                return e;
            }
        }
    }

    private static void abort(@NotNull Throwable e, @Nullable StoreTransaction t) {
        if (log.isDebugEnabled()) {
            log.error("Abort persistent transaction.", e);
        }

        if (t != null) {
            t.abort();
        }

        if (e instanceof Error) {
            throw (Error) e;
        }

        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }

        throw new RuntimeException(e);
    }

}
