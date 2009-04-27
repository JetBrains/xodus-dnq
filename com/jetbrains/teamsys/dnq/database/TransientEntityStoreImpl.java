package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.LinkedHashSet;
import com.jetbrains.teamsys.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * @author Vadim.Gurov
 */
public class TransientEntityStoreImpl implements TransientEntityStore, InitializingBean {

  private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

  private EntityStore persistentStore;
  private ModelMetaData modelMetaData;
  private Map<Object, TransientStoreSession> sessions = new HashMap<Object, TransientStoreSession>();
  private ThreadLocal<TransientStoreSession> currentSession = new ThreadLocal<TransientStoreSession>();
  private Set<TransientStoreSessionListener> listeners = new LinkedHashSet<TransientStoreSessionListener>();

  private boolean trackEntityCreation = true;
  private boolean abortSessionsOnClose = false;
  private boolean resumeOnBeginIfExists = false;
  private boolean attachToCurrentOnBeginIfExists = false;
  private String blobsStorePath;
  private File blobsStore;

  public TransientEntityStoreImpl() {
    if (log.isTraceEnabled()) {
      log.trace("TransientEntityStoreImpl constructor called.");
    }
  }

  public EntityStore getPersistentStore() {
    return persistentStore;
  }

  public void setBlobsStorePath(@NotNull String blobsStorePath) {
    this.blobsStorePath = blobsStorePath;
  }

  File getBlobsStore() {
    return blobsStore;
  }

  /**
   * Service locator {@link jetbrains.springframework.configuration.runtime.ServiceLocator} is responsible to set persistent entity store
   *
   * @param persistentStore
   */
  public void setPersistentStore(EntityStore persistentStore) {
    this.persistentStore = persistentStore;
  }

  /**
   * If true, on store close all opened sessions will be aborted.
   *
   * @param abortSessionsOnClose
   */
  public void setAbortSessionsOnClose(boolean abortSessionsOnClose) {
    this.abortSessionsOnClose = abortSessionsOnClose;
  }

  /**
   * If true, in {@link #beginSession(String,Object)} will use existing current session if exists.
   *
   * @param attachToCurrentOnBeginIfExists
   */
  public void setAttachToCurrentOnBeginIfExists(boolean attachToCurrentOnBeginIfExists) {
    this.attachToCurrentOnBeginIfExists = attachToCurrentOnBeginIfExists;
  }

  /**
   * Resume session if {@link #beginSession(String,Object)} called, but session with given id already exists.
   *
   * @param resumeOnBeginIfExists
   */
  public void setResumeOnBeginIfExists(boolean resumeOnBeginIfExists) {
    this.resumeOnBeginIfExists = resumeOnBeginIfExists;
  }

  @NotNull
  public String getName() {
    return "transient store";
  }

  @NotNull
  public String getLocation() {
    throw new UnsupportedOperationException("Not supported by transient store.");
  }

  /**
   * The same as {@link #getThreadSession()}
   *
   * @return
   */
  @NotNull
  public StoreSession beginSession() {
    throw new UnsupportedOperationException("Not supported by transient store. Use beginSession(name, id) instead.");
  }

  public TransientStoreSession beginSession(@Nullable String name, Object id) {
    return beginSession(name, id, TransientStoreSessionMode.readwrite);
  }

  protected TransientStoreSession beginSession(@Nullable String name, Object id, @NotNull TransientStoreSessionMode mode) {
    if (name == null) {
      name = "anonymous";
    }

    if (log.isDebugEnabled()) {
      log.debug("Begin new session [" + name + "] with id [" + id + "]");
    }

    if (currentSession.get() != null) {
      if (attachToCurrentOnBeginIfExists) {
        log.debug("Return session already associated with the current thread " + currentSession.get());
        return currentSession.get();
      } else {
        throw new IllegalStateException("Open session already presents for current thread.");
      }
    }

    if (id == null) {
        return registerStoreSession(new TransientSessionImpl(this, name));
    } else {
        if (getStoreSession(id) != null) {
          if (resumeOnBeginIfExists) {
            return resumeSession(id);
          } else {
            throw new IllegalArgumentException("Transient session with id [" + id + "] already exists.");
          }
        }
    }
    return registerStoreSession(new TransientSessionImpl(this, name, id));
  }

  public boolean isSessionExists(@NotNull Object id) {
    return getStoreSession(id) != null;
  }

  public boolean isTrackEntityCreation() {
    return trackEntityCreation;
  }

  public TransientStoreSession resumeSession(@NotNull Object id) {
    if (log.isDebugEnabled()) {
      log.debug("Resume session with id [" + id + "]");
    }

    if (currentSession.get() != null) {
      throw new IllegalStateException("Open transient session already associated with current thread.");
    }

    TransientStoreSession s = getStoreSession(id);

    if (s == null) {
      throw new IllegalArgumentException("Transient session with id [" + id + "] is not found.");
    }

    s.resume();
    currentSession.set(s);

    return s;
  }

  public void resumeSession(TransientStoreSession session) {
    if (session != null) {
      if (log.isDebugEnabled()) {
        log.debug("Resume session [" + session.getName() + "] with id [" + session.getId() + "]");
      }

      TransientStoreSession current = currentSession.get();
      if (current != null) {
        if (current != session) {
         throw new IllegalStateException("Another open transient session already associated with current thread.");
        }
      }

      session.resume();
      currentSession.set(session);
    }
  }

  public void setModelMetaData(final ModelMetaData modelMetaData) {
    this.modelMetaData = modelMetaData;
  }

  @Nullable
  public ModelMetaData getModelMetaData() {
    return modelMetaData;
  }

  /**
   * It's guaranteed that current thread session is Open, if exists
   *
   * @return
   */
  @Nullable
  public StoreSession getThreadSession() {
    return currentSession.get();
  }

  public void close() {
    log.debug("Close transient store.");

    // check there's no opened sessions

    synchronized (sessions) {
      if (abortSessionsOnClose) {
        log.debug("Abort opened transient sessions.");

        for (TransientStoreSession s : new ArrayList<TransientStoreSession>(sessions.values())) {
          try {
            s.forceAbort();
          } catch (Throwable e) {
            log.error("Error while aborting session " + s, e);
          }
        }
      } else {
        if (sessions.size() != 0) {
          throw new IllegalStateException("Can't close transient store, because there're opened transient sessions.");
        }
      }
    }
  }

  public void setReadonly(boolean readonly) {
    persistentStore.setReadonly(readonly);
  }

  public boolean isReadonly() {
    return persistentStore.isReadonly();
  }

  public void save(@NotNull final String file) {
    persistentStore.save(file);
  }

  public void load(@NotNull final String file) {
    persistentStore.load(file);
  }

  public boolean entityTypeExists(@NotNull final String entityTypeName) {
    try {
      return ((BerkeleyDbEntityStore) persistentStore).getEntityTypeId(entityTypeName, false) >= 0;
    } catch (Exception e) {
      // ignore
    }
    return false;
  }

  public void renameEntityTypeRefactoring(@NotNull final String oldEntityTypeName, @NotNull final String newEntityTypeName) {
    final TransientStoreSession s = (TransientStoreSession) getThreadSession();

    if (s == null) {
      throw new IllegalStateException("No current thread session.");
    }

    final TransientChangesTrackerImpl changesTracker1 = (TransientChangesTrackerImpl) s.getTransientChangesTracker();
    final TransientChangesTrackerImpl changesTracker = changesTracker1;
    changesTracker.offerChange(new Runnable() {
      public void run() {
        ((BerkeleyDbEntityStore) s.getPersistentSession().getStore()).renameEntityType(oldEntityTypeName, newEntityTypeName);
      }
    });
  }

  public void deleteEntityRefactoring(@NotNull Entity entity) {
    final TransientStoreSession s = (TransientStoreSession) getThreadSession();

    if (s == null) {
      throw new IllegalStateException("No current thread session.");
    }

    final TransientChangesTrackerImpl changesTracker1 = (TransientChangesTrackerImpl) s.getTransientChangesTracker();
    final TransientChangesTrackerImpl changesTracker = changesTracker1;
    final Entity persistentEntity =
            (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;

    changesTracker.offerChange(new Runnable() {
      public void run() {
        persistentEntity.delete();
      }
    });
  }

  public void deleteLinksRefactoring(@NotNull final Entity entity, @NotNull final String linkName) {
    final TransientStoreSession s = (TransientStoreSession) getThreadSession();

    if (s == null) {
      throw new IllegalStateException("No current thread session.");
    }

    final TransientChangesTrackerImpl changesTracker1 = (TransientChangesTrackerImpl) s.getTransientChangesTracker();
    final TransientChangesTrackerImpl changesTracker = changesTracker1;

    final Entity persistentEntity =
            (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
    changesTracker.offerChange(new Runnable() {
      public void run() {
        persistentEntity.deleteLinks(linkName);
      }
    });
  }

  private TransientStoreSession getStoreSession(Object id) {
    synchronized (sessions) {
      return sessions.get(id);
    }
  }

  private TransientStoreSession registerStoreSession(TransientStoreSession s) {
    synchronized (sessions) {
      if (sessions.containsKey(s.getId())) {
        throw new IllegalArgumentException("Session with id [" + s.getId() + "] already registered.");
      }

      sessions.put(s.getId(), s);
    }

    currentSession.set(s);

    return s;
  }

  void unregisterStoreSession(TransientStoreSession s) {
    synchronized (sessions) {
      if (sessions.remove(s.getId()) == null) {
        throw new IllegalArgumentException("Transient session with id [" + s.getId() + "] wasn't previously registered.");
      }
    }

    currentSession.remove();
  }

  void suspendThreadSession() {
    assert currentSession.get() != null;

    currentSession.remove();
  }

  public void addListener(TransientStoreSessionListener listener) {
    listeners.add(listener);
  }

  public void removeListener(TransientStoreSessionListener listener) {
    listeners.remove(listener);
  }

  void forAllListeners(ListenerVisitor v) {
    for (TransientStoreSessionListener l : listeners) {
      v.visit(l);
    }
  }

  public void afterPropertiesSet() throws Exception {
    if (blobsStorePath == null) {
      blobsStorePath = System.getProperty("java.io.tmpdir");
    }

    blobsStore = new File(blobsStorePath);

    if (!blobsStore.exists() && !blobsStore.mkdirs()) {
      throw new IllegalArgumentException("Can't create not existing directory [" + blobsStorePath + "]");
    }

    if (!blobsStore.isDirectory()) {
      throw new IllegalArgumentException("Path [" + blobsStorePath + "] should be directory.");
    }

    if (!blobsStore.canWrite() || !blobsStore.canRead()) {
      throw new IllegalArgumentException("Application must have write and read access to [" + blobsStorePath + "]");
    }

    if (log.isDebugEnabled()) {
      log.debug("Transient store will use the following path for storing blobs [" + blobsStore.getCanonicalPath() + "]");
    }
  }

  interface ListenerVisitor {
    void visit(TransientStoreSessionListener listener);
  }

}
