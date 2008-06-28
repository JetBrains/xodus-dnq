package com.jetbrains.teamsys.dnq.database;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.*;
import com.jetbrains.teamsys.core.execution.locks.Latch;

import java.util.*;
import java.io.File;
import java.io.IOException;

/**
 */
public class TransientSessionImpl extends AbstractTransientSession {

  protected static final Log log = LogFactory.getLog(TransientSessionImpl.class);
  private static final String TEMP_FILE_NAME_SEQUENCE = "__TEMP_FILE_NAME_SEQUENCE__";

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

  private Map<String, TransientEntity> localEntities;
  private State state;
  private boolean checkEntityVersionOnCommit = true;
  private Set<File> createdBlobFiles;
  private Latch lock = new Latch();
  private TransientChangesTracker changesTracker;

  // stores transient entities that was created for loaded persistent entities to avoid double loading
  private Map<EntityId, TransientEntity> createdTransientForPersistentEntities = new HashMap<EntityId, TransientEntity>();

  // stores new transient entities to support getEntity(EntityId) operation
  private Map<TransientEntityId, TransientEntity> createdNewTransientEntities = new HashMap<TransientEntityId, TransientEntity>();

  protected TransientSessionImpl(final TransientEntityStoreImpl store, final String name, final Object id, final boolean checkEntityVersionOnCommit) {
    super(store, name, id);

    this.checkEntityVersionOnCommit = checkEntityVersionOnCommit;
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

  boolean isCheckEntityVersionOnCommit() {
    return checkEntityVersionOnCommit;
  }

  void setCheckEntityVersionOnCommit(boolean checkEntityVersionOnCommit) {
    this.checkEntityVersionOnCommit = checkEntityVersionOnCommit;
  }

  @NotNull
  public EntityIterable createPersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable) {
    switch (state) {
      case Open:
        // do not wrap twice
        if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
          return wrappedIterable;
        } else {
          return new PersistentEntityIterableWrapper(wrappedIterable, this);
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
          if (localEntities == null) {
            localEntities = new HashMap<String, TransientEntity>();
          }
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
        TransientEntity res = null;
        synchronized (this) {
          final Map<String, TransientEntity> le = localEntities;
          res = le == null ? null : le.get(localName);
        }
        return res;

      default:
        throw new IllegalStateException("Can't get session local entity in state [" + state + "]");
    }
  }

  public void suspend() {
    if (log.isDebugEnabled()) {
      log.debug("Suspend transient session " + this);
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
        throw new IllegalArgumentException("Can't suspend in state " + state);
    }
  }

  public void resume() {
    try {
      lock.acquire();
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

  public void commit() {
    final Set<TransientEntityChange> changes = commitReturnChanges();
    notifyCommitedListeners(changes);
  }

  public void intermediateCommit() {
    final Set<TransientEntityChange> changes = intermediateCommitReturnChanges();
    notifyFlushedListeners(changes);
  }

  public void abort() {
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

  /*
   * Aborts session in any state
   */
  public void forceAbort() {
    if (log.isDebugEnabled()) {
      log.debug("Unconditional abort transient session " + this);
    }

    switch (state) {
      case Open:
        abort();
        break;
      case Suspended:
        deleteBlobsStore();
        store.unregisterStoreSession(this);
        state = State.Aborted;
        break;
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
        return toEntityIdImpl(representation);

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
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().getAll(entityType), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");

    }
  }

  @NotNull
  public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @Nullable final Comparable value) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().find(entityType, propertyName, value), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable minValue, @NotNull final Comparable maxValue) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().find(entityType, propertyName, minValue, maxValue), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  public EntityIterable findWithProp(@NotNull final String entityType, @NotNull final String propertyName) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findWithProp(entityType, propertyName), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable startsWith(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final String value) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().startsWith(entityType, propertyName, value), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable findLinks(@NotNull final String entityType, @NotNull final Entity entity, @NotNull final String linkName) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findLinks(entityType, entity, linkName), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable findLinks(@NotNull String entityType, @NotNull EntityIterable entities, @NotNull String linkName) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findLinks(entityType, entities, linkName), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable findWithLinks(@NotNull String entityType, @NotNull String linkName) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().findWithLinks(entityType, linkName), this);

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
        return new PersistentEntityIterableWrapper(
                getPersistentSessionInternal().sort(entityType, propertyName, ascending), this);

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
                getPersistentSessionInternal().sort(entityType, propertyName, rightOrder, ascending), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable distinct(@NotNull final EntityIterable source) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().distinct(source), this);

      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public EntityIterable selectDistinct(@NotNull EntityIterable source, @NotNull String linkName) {
    switch (state) {
      case Open:
        return new PersistentEntityIterableWrapper(getPersistentSessionInternal().selectDistinct(source, linkName), this);

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
        doClearHistory(entityType);
        break;
      default:
        throw new IllegalStateException("Can't execute in state [" + state + "]");
    }
  }

  @NotNull
  public File createBlobFile(boolean createNewFile) {
    return doCreateBlobFile(createNewFile);
  }

  public void quietIntermediateCommit() {
    final ModelMetaData saved = store.getModelMetaData();
    final boolean checkVersions = isCheckEntityVersionOnCommit();
    try {
      store.setModelMetaData(null);
      setCheckEntityVersionOnCommit(false);
      intermediateCommit();
    } finally {
      setCheckEntityVersionOnCommit(checkVersions);
      store.setModelMetaData(saved);
    }
  }

  protected void closePersistentSession() {
    if (log.isDebugEnabled()) {
      log.debug("Close persistent session for transient session " + this);
    }
    getPersistentSessionInternal().close();
  }

  protected void doResume() {
    log.debug("Open persistent session for transient session " + this);
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

  private Set<TransientEntityChange> commitReturnChanges() throws IllegalStateException {
    if (log.isDebugEnabled()) {
      log.debug("Commit transient session " + this);
    }

    switch (state) {
      case Open:
        // flush may produce runtime exceptions. if so - session stays open
        Set<TransientEntityChange> changes = flush();

        try {
          closePersistentSession();
        } finally {
          deleteBlobsStore();
          store.unregisterStoreSession(this);
          state = State.Committed;
          lock.release();
        }

        return changes;

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
    Set<DataIntegrityViolationException> orphans = new HashSet<DataIntegrityViolationException>(4);
    final ModelMetaData modelMetaData = store.getModelMetaData();

    if (modelMetaData == null) {
      return orphans;
    }

    for (TransientEntity e : new HashSet<TransientEntity>(changesTracker.getChangedEntities())) {
      if (!e.isRemoved()) {
        EntityMetaData emd = modelMetaData.getEntityMetaData(e.getRealType());

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

  private void disposeCursors() {
    if (log.isDebugEnabled()) {
      log.debug("Dispose open cursors. " + this);
    }
    try {
      ((BerkeleyDbSession) getPersistentSessionInternal()).disposeOpenedIterables();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  /**
   * Creates local copy of given entity.
   *
   * @param entity
   * @return
   */
  @NotNull
  public TransientEntity newLocalCopy(@NotNull final TransientEntity entity) {
    switch (state) {
      case Open:
        if (entity.isTemporary()) {
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
          if (((TransientEntityImpl) entity).getTransientStoreSession() == this &&
                  this.createdTransientForPersistentEntities.get(entity.getId()) == entity) {
            // was created in this session and session wasn't reverted
            return entity;
          } else {
            // saved entity from another session or from reverted session - load it from database by id
            EntityId id = entity.getId();
            // local copy already created?
            TransientEntity localCopy = createdTransientForPersistentEntities.get(id);
            if (localCopy != null) {
              return localCopy;
            }

            // load persistent entity from database by id
            Entity databaseCopy = getPersistentSessionInternal().getEntity(id);
            if (databaseCopy == null) {
              // entity was removed - can't create local copy
              throw new EntityRemovedInDatabaseException(entity);
            }

            return newEntity(databaseCopy);
          }
        } else if (entity.isRemoved()) {
          throw new EntityRemovedException(entity);
        }

      default:
        throw new IllegalStateException("Can't create local copy in state [" + state + "]");
    }
  }

  /**
   * Checks constraints before save changes
   */
  private void checkBeforeSaveChangesConstraints(@NotNull Set<DataIntegrityViolationException> exceptions) {
    final ModelMetaData modelMetaData = store.getModelMetaData();

    if (modelMetaData == null) {
      if (log.isDebugEnabled()) {
        log.warn("Model meta data is not defined. Skip before save changes constraints checking. " + this);
      }
      return;
    }

    if (log.isDebugEnabled()) {
      log.debug("Check before save changes constraints. " + this);
    }

    // 1. check associations cardinality
    exceptions.addAll(ConstraintsUtil.checkAssociationsCardinality(changesTracker, modelMetaData));

    // 2. check properties constraints
    exceptions.addAll(ConstraintsUtil.checkRequiredProperties(changesTracker, modelMetaData));
    exceptions.addAll(ConstraintsUtil.checkUniqueProperties(this, changesTracker, modelMetaData));

    if (exceptions.size() != 0) {
      ConstraintsValidationException e = new ConstraintsValidationException(exceptions);
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
  private Set<TransientEntityChange> flush() {
    if (!changesTracker.areThereChanges()) {
      log.debug("Nothing to flush.");
      return null;
    }

    checkDatabaseState();
    checkBeforeSaveChangesConstraints(removeOrphans());

    changesTracker.markState();
    notifyBeforeFlushListeners();
    if (changesTracker.wasChangesAfterMarkState()) {
      checkBeforeSaveChangesConstraints(removeOrphans());
    }

    disposeCursors();
    StoreTransaction transaction = null;
    try {
      if (log.isDebugEnabled()) {
        log.debug("Open persistent transaction in transient session " + this);
      }

      transaction = getPersistentSessionInternal().beginTransaction();

      // lock entities to be updated by current transaction
      lockForUpdate(transaction);

      // check versions before commit changes
      checkVersions();

      // save history if meta data defined
      saveHistory();

      // commit changes
      for (Runnable c : changesTracker.getChanges()) {
        c.run();
      }

      if (log.isDebugEnabled()) {
        log.debug("Commit persistent transaction in transient session " + this);
      }
      disposeCursors();

      transaction.commit();

      updateCaches();

    } catch (Throwable e) {
      // tracker make some changes in transient entities - rollback them
      try {
        rollbackTransientTrackerChanges();
        fixEntityIdsInDataIntegrityViolationException(e);
      } finally {
        TransientStoreUtil.abort(e, transaction);
      }
    }

    Set<TransientEntityChange> changesDescription = changesTracker.getChangesDescription();

    changesTracker.clear();

    return changesDescription;
  }

  private void checkDatabaseState() {
    if (store.getPersistentStore().isReadonly()) {
      throw new DatabaseStateIsReadonlyException("Database backup is in progress. Please wait and then repeat your action.");
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
    // all new transient entities was saved or removed - clear cache
    // FIX: do not clear cache of new entities to support old IDs for Webr
    // createdNewTransientEntities.clear();
    // reload version of changed persistent entities, remove removed and clear caches

    for (TransientEntity e : changesTracker.getChangedEntities()) {
      if (e.isNew()) {
        throw new IllegalStateException("Bug! New transient entity after commit!");

      } else if (e.isRemoved()) {

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
    final List<Entity> affected = new ArrayList<Entity>(changedPersistentEntities.size());
    for (final TransientEntity localCopy : changedPersistentEntities) {
      //if (/*!localCopy.isNew() && */!localCopy.isRemoved()) {
      Entity e = ((TransientEntityImpl) localCopy).getPersistentEntityInternal();
      affected.add(e);
      //}
    }
    txn.lockForUpdate(affected);
  }

  private void checkVersions() {
    if (!checkEntityVersionOnCommit) {
      if (log.isWarnEnabled()) {
        log.warn("Skip versions checking. " + this);
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
        if (log.isDebugEnabled()) {
          log.debug("Entity was removed from database:" + localCopy);
        }

        throw new EntityRemovedInDatabaseException(localCopy);
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
            log.warn("Incompatible concurrent changes for " + localCopy + ". Changed properties [" + TransientStoreUtil.toString(changesTracker.getChangedProperties(localCopy)) +
                    "] changed links [" + TransientStoreUtil.toString(changesTracker.getChangedLinks(localCopy)) + "]");
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
    EntityMetaData emd = md == null ? null : md.getEntityMetaData(entity.getRealType());
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

    TransientEntityChange tec = changesTracker.getChangeDescription(entity);

    Map<String, LinkChange> changedLinks = tec.getChangedLinksDetaled();
    if (changedLinks != null) {
      for (LinkChange lc : changedLinks.values()) {
        AssociationEndMetaData amd = emd.getAssociationEndMetaData(lc.getLinkName());
        if (!(amd.getCardinality().isMultiple() && lc.getChangeType() == LinkChangeType.ADD)) {
          return false;
        }
      }
    }

    // 2. changed property is marked as versionMismatchResolution: ignore
    Set<String> changedProps = tec.getChangedProperties();
    if (changedProps != null) {
      for (String p : changedProps) {
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

    for (final TransientEntity e : changedPersistentEntities) {
      if (!e.isNew() && !e.isRemoved()) {
        final EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());
        if (emd != null && emd.getHasHistory() && emd.changesReflectHistory(e, changesTracker)) {
          if (log.isDebugEnabled()) {
            log.debug("Save history of: " + e);
          }
          e.newVersion();
        }
      }
    }
  }

  private Set<File> getCreatedBlobFiles() {
    if (createdBlobFiles == null) {
      createdBlobFiles = new HashSet<File>();
    }
    return createdBlobFiles;
  }

  private void deleteBlobsStore() {
    if (createdBlobFiles == null) {
      return;
    }

    for (File f : createdBlobFiles) {
      if (f.exists() && !f.delete()) {
        log.warn("Can't delete temp blob file [" + f.getAbsolutePath() + "]");
        f.deleteOnExit();
        //TODO: start background threads that periodically tries to delete file. FileCleaner can't be used.
      }
    }

    createdBlobFiles.clear();
  }

  private void notifyCommitedListeners(final Set<TransientEntityChange> changes) {
    if (changes == null || changes.isEmpty()) {
      return;
    }

    if (log.isDebugEnabled()) {
      log.debug("Notify commited listeners " + this);
    }

    store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
      public void visit(TransientStoreSessionListener listener) {
        listener.commited(changes);
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
        listener.flushed(changes);
      }
    });
  }

  private void notifyBeforeFlushListeners() {
    final Set<TransientEntityChange> changes = changesTracker.getChangesDescription();

    if (changes == null || changes.isEmpty()) {
      return;
    }

    if (log.isDebugEnabled()) {
      log.debug("Notify before flush listeners " + this);
    }

    store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
      public void visit(TransientStoreSessionListener listener) {
        listener.beforeFlush(changes);
      }
    });
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

  @NotNull
  protected EntityId toEntityIdImpl(@NotNull final String representation) {
    // treat given id as id of transient entity first
    try {
      return getPersistentSessionInternal().toEntityId(representation);
    } catch (Exception e) {
      return TransientEntityIdImpl.fromString(representation);
    }
  }

  protected void doClearHistory(@NotNull final String entityType) {
    changesTracker.historyCleared(entityType);
  }

  @NotNull
  protected File doCreateBlobFile(boolean createNewFile) {
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

    getCreatedBlobFiles().add(f);

    return f;
  }

}
