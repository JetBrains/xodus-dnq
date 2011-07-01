package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.core.dataStructures.hash.LinkedHashSet;
import com.jetbrains.teamsys.core.execution.locks.Latch;
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
    private final Map<Object, TransientStoreSession> sessions = new HashMap<Object, TransientStoreSession>();
    private final ThreadLocal<TransientStoreSession> currentSession = new ThreadLocal<TransientStoreSession>();
    private final Set<TransientStoreSessionListener> listeners = new LinkedHashSet<TransientStoreSessionListener>();

    private boolean trackEntityCreation = true;
    private boolean abortSessionsOnClose = false;
    private boolean resumeOnBeginIfExists = false;
    private boolean attachToCurrentOnBeginIfExists = false;
    private String blobsStorePath;
    private File blobsStore;
    private int flushRetryOnLockConflict = 100;
    private final Latch enumContainersLock = Latch.create();
    private final Set<EnumContainer> initedContainers = new HashSet<EnumContainer>(10);
    private final Map<String, Entity> enumCache = new HashMap<String, Entity>();

    public TransientEntityStoreImpl() {
        if (log.isTraceEnabled()) {
            log.trace("TransientEntityStoreImpl constructor called.");
        }
    }

    public EntityStore getPersistentStore() {
        return persistentStore;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void setBlobsStorePath(@NotNull String blobsStorePath) {
        this.blobsStorePath = blobsStorePath;
    }

    File getBlobsStore() {
        return blobsStore;
    }

    public int getFlushRetryOnLockConflict() {
        return flushRetryOnLockConflict;
    }

    public void setFlushRetryOnLockConflict(int flushRetryOnLockConflict) {
        this.flushRetryOnLockConflict = flushRetryOnLockConflict;
    }

    /**
     * Service locator {@link jetbrains.springframework.configuration.runtime.ServiceLocator} is responsible to set persistent entity store
     *
     * @param persistentStore persistent entity store.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void setPersistentStore(EntityStore persistentStore) {
        this.persistentStore = persistentStore;
    }

    /**
     * If true, on store close all opened sessions will be aborted.
     *
     * @param abortSessionsOnClose true to abort.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void setAbortSessionsOnClose(boolean abortSessionsOnClose) {
        this.abortSessionsOnClose = abortSessionsOnClose;
    }

    /**
     * If true, in {@link #beginSession(String, Object)} will use existing current session if exists.
     *
     * @param attachToCurrentOnBeginIfExists true to use existing current session.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void setAttachToCurrentOnBeginIfExists(boolean attachToCurrentOnBeginIfExists) {
        this.attachToCurrentOnBeginIfExists = attachToCurrentOnBeginIfExists;
    }

    /**
     * Resume session if {@link #beginSession(String, Object)} called, but session with given id already exists.
     *
     * @param resumeOnBeginIfExists should resume if session exists
     */
    @SuppressWarnings({"UnusedDeclaration"})
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
     * @return fake
     */
    @NotNull
    public StoreSession beginSession() {
        throw new UnsupportedOperationException("Not supported by transient store. Use beginSession(name, id) instead.");
    }

    public TransientStoreSession beginSession(@Nullable String name, Object id) {
        return beginSession(name, id, TransientStoreSessionMode.inplace);
    }

    public TransientStoreSession beginDeferredSession(@Nullable String name, Object id) {
        return beginSession(name, id, TransientStoreSessionMode.deferred);
    }

    protected TransientStoreSession beginSession(@Nullable String name, Object id, @NotNull TransientStoreSessionMode mode) {
        if (name == null) {
            name = "anonymous";
        }

        if (log.isDebugEnabled()) {
            StringBuilder logMessage = new StringBuilder(64);
            logMessage.append("Begin new session [");
            logMessage.append(name);
            logMessage.append("] with id [");
            logMessage.append(id);
            logMessage.append("], mode = ");
            logMessage.append(mode);
            log.debug(logMessage.toString());
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
            final TransientStoreSession transientSession;
            if (mode == TransientStoreSessionMode.inplace) {
                transientSession = new TransientSessionImpl(this, name);
            } else if (mode == TransientStoreSessionMode.deferred) {
                transientSession = new TransientSessionDeferred(this, name);
            } else {
                throw new IllegalStateException("Unsupported session mode: " + mode);
            }
            return registerStoreSession(transientSession);
        } else {
            if (getStoreSession(id) != null) {
                if (resumeOnBeginIfExists) {
                    return resumeSession(id);
                } else {
                    throw new IllegalArgumentException("Transient session with id [" + id + "] already exists.");
                }
            }
        }

        final TransientStoreSession transientSession;
        if (mode == TransientStoreSessionMode.inplace) {
            transientSession = new TransientSessionImpl(this, name, id);
        } else if (mode == TransientStoreSessionMode.deferred) {
            transientSession = new TransientSessionDeferred(this, name, id);
        } else {
            throw new IllegalStateException("Unsupported session mode: " + mode);
        }
        return registerStoreSession(transientSession);
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

    public void resumeSession(TransientStoreSession session, int timeout) {
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

            session.resume(timeout);
            currentSession.set(session);
        }
    }

    public void resumeSession(TransientStoreSession session) {
        resumeSession(session, 0);
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
     * @return current thread session
     */
    @Nullable
    public TransientStoreSession getThreadSession() {
        return currentSession.get();
    }

    public void close() {
        log.debug("Close transient store.");

        // check there's no opened sessions
        final ArrayList<TransientStoreSession> _sessions;
        synchronized (sessions) {
            _sessions = new ArrayList<TransientStoreSession>(sessions.values());
        }
        if (abortSessionsOnClose) {
            log.debug("Abort opened transient sessions.");

            for (TransientStoreSession s : _sessions) {
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

    public void setReadonly(boolean readonly) {
        persistentStore.setReadonly(readonly);
    }

    public boolean isReadonly() {
        return persistentStore.isReadonly();
    }

    public boolean entityTypeExists(@NotNull final String entityTypeName) {
        try {
            return ((PersistentEntityStore) persistentStore).getEntityTypeId(entityTypeName, false) >= 0;
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    public void renameEntityTypeRefactoring(@NotNull final String oldEntityTypeName, @NotNull final String newEntityTypeName) {
        final TransientStoreSession s = getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final TransientChangesTrackerImpl changesTracker = (TransientChangesTrackerImpl) s.getTransientChangesTracker();
        changesTracker.offerChange(new Runnable() {
            public void run() {
                ((PersistentEntityStore) s.getPersistentSession().getStore()).renameEntityType(oldEntityTypeName, newEntityTypeName);
            }
        });
    }

    public void deleteEntityTypeRefactoring(@NotNull final String entityTypeName) {
        final TransientStoreSession s = getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final TransientChangesTrackerImpl changesTracker = (TransientChangesTrackerImpl) s.getTransientChangesTracker();
        changesTracker.offerChange(new Runnable() {
            public void run() {
                ((PersistentEntityStoreImpl) s.getPersistentSession().getStore()).deleteEntityType(entityTypeName);
            }
        });
    }

    public void deleteEntityRefactoring(@NotNull Entity entity) {
        final TransientStoreSession s = getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final TransientChangesTrackerImpl changesTracker = (TransientChangesTrackerImpl) s.getTransientChangesTracker();
        final Entity persistentEntity =
            (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;

        if (entity instanceof TransientEntity) {
            changesTracker.entityDeleted((TransientEntity) entity);
        } else {
            changesTracker.offerChange(new Runnable() {
                public void run() {
                    persistentEntity.delete();
                }
            });
        }
    }

    public void deleteLinksRefactoring(@NotNull final Entity entity, @NotNull final String linkName) {
        final TransientStoreSession s = getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final TransientChangesTrackerImpl changesTracker = (TransientChangesTrackerImpl) s.getTransientChangesTracker();

        final Entity persistentEntity =
            (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        changesTracker.offerChange(new Runnable() {
            public void run() {
                persistentEntity.deleteLinks(linkName);
            }
        });
    }

    public void deleteLinkRefactoring(@NotNull final Entity entity, @NotNull final String linkName, @NotNull final Entity link) {
        final TransientStoreSession s = getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final TransientChangesTrackerImpl changesTracker = (TransientChangesTrackerImpl) s.getTransientChangesTracker();

        final Entity persistentEntity =
            (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        final Entity persistentLink =
            (link instanceof TransientEntity) ? ((TransientEntity) link).getPersistentEntity() : link;
        changesTracker.offerChange(new Runnable() {
            public void run() {
                persistentEntity.deleteLink(linkName, persistentLink);
            }
        });
    }

    public TransientStoreSession getStoreSession(Object id) {
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

    public int sessionsCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    public void dumpSessions(StringBuilder sb) {
        synchronized (sessions) {
            for (TransientStoreSession s : sessions.values()) {
                sb.append("\n").append(s.toString());
            }
        }
    }

    public boolean isEnumContainerInited(EnumContainer container) {
        return initedContainers.contains(container);
    }

    public void enumContainerInited(EnumContainer container) {
        initedContainers.add(container);
    }

    public void enumContainerLock() throws InterruptedException {
        enumContainersLock.acquire();
    }

    public void enumContainerUnLock() {
        enumContainersLock.release();
    }

    public Entity getCachedEnumValue(@NotNull final String className, @NotNull final String propName) {
        final String key = getEnumKey(className, propName);
        synchronized (enumCache) {
            return enumCache.get(key);
        }
    }

    public void setCachedEnumValue(@NotNull final String className,
                                   @NotNull final String propName, @NotNull final Entity entity) {
        final String key = getEnumKey(className, propName);
        synchronized (enumCache) {
            enumCache.put(key, entity);
        }
    }

    public static String getEnumKey(@NotNull final String className, @NotNull final String propName) {
        final StringBuilder builder = new StringBuilder(24);
        builder.append(propName);
        builder.append('@');
        builder.append(className);
        return builder.toString();
    }

    interface ListenerVisitor {
        void visit(TransientStoreSessionListener listener);
    }

}
