package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.EntityRemovedException;
import jetbrains.exodus.database.impl.iterate.EntityIterableBase;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

/**
 * Date: 05.02.2007
 * Time: 16:34:36
 * <p/>
 * TODO: for blobs implement BlobsManagers, like LinkManager, because there're 3 blob types - String, File, InputStream
 *
 * @author Vadim.Gurov
 */
class TransientEntityImpl implements TransientEntity {

    protected static final Log log = LogFactory.getLog(TransientEntity.class);

    enum State {
        New,
        Saved,
        SavedNew,
        RemovedNew,
        RemovedSaved,
        RemovedSavedNew,

    }
    protected final Map<String, TransientLinksManager> linksManagers = new HashMapDecorator<String, TransientLinksManager>();
    protected final Map<String, Comparable> propertiesCache = new HashMapDecorator<String, Comparable>();
    protected final Map<String, File> fileBlobsCache = new HashMapDecorator<String, File>();

    protected PersistentEntity persistentEntity;
    protected int version;
    protected String type;
    protected State state;
    protected TransientEntityStore store;
    protected long sessionId;
    protected int id;

    TransientEntityImpl(@NotNull String type, @NotNull TransientStoreSession session) {
        setTransientStoreSession(session);
        setType(type);
        setState(State.New);
        setId(new TransientEntityIdImpl());

        session.getTransientChangesTracker().entityAdded(this);
    }

    TransientEntityImpl(@NotNull Entity persistentEntity, @NotNull TransientStoreSession session) {
        setTransientStoreSession(session);
        setPersistentEntityInternal(persistentEntity);
        setState(State.Saved);
    }

    TransientEntityImpl(@NotNull Entity persistentEntity, @NotNull TransientStoreSession session, int version) {
        setTransientStoreSession(session);
        setPersistentEntityInternal(persistentEntity, version);
        setState(State.Saved);
    }

    private static final StandardEventHandler<Object, Object, Entity> getPersistentEntityEventHandler = new StandardEventHandler<Object, Object, Entity>() {
        Entity processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.persistentEntity;
        }

        Entity processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }
    };

    /**
     * It's allowed to get persistent entity in state Open-Removed.
     *
     * @return underlying persistent entity
     */
    @NotNull
    public Entity getPersistentEntity() {
        return getPersistentEntityEventHandler.handle(this, null, null);
    }

    public void deleteInternal() {
        persistentEntity.delete();
    }

    PersistentEntity getPersistentEntityInternal() {
        return persistentEntity;
    }

    protected void setPersistentEntityInternal(Entity persistentEntity) {
        if (persistentEntity == null) {
            this.persistentEntity = null;
            return;
        }
        setPersistentEntityInternal(persistentEntity, persistentEntity.getVersion(), persistentEntity.getType());
    }

    protected void setPersistentEntityInternal(Entity persistentEntity, int version) {
        if (persistentEntity == null) {
            this.persistentEntity = null;
            return;
        }
        setPersistentEntityInternal(persistentEntity, version, persistentEntity.getType());
    }

    protected void setPersistentEntityInternal(Entity persistentEntity, int version, String type) {
        this.persistentEntity = (PersistentEntity) persistentEntity;
        this.version = version;
        this.type = type;
    }

    @NotNull
    public TransientEntityStore getStore() {
        return store;
    }

    public long getSessionId() {
        return sessionId;
    }

    @NotNull
    public TransientStoreSession getTransientStoreSession() {
        return store.getStoreSession(sessionId);
    }

    protected void setTransientStoreSession(TransientStoreSession session) {
        store = session.getStore();
        sessionId = session.getId();
    }

    public TransientStoreSession getThreadStoreSession() {
        return store.getThreadSession();
    }

    public boolean isNew() {
        return state == State.New;
    }

    public boolean isSaved() {
        return state == State.Saved || state == State.SavedNew;
    }

    public boolean isRemoved() {
        return state == State.RemovedNew || state == State.RemovedSaved || state == State.RemovedSavedNew;
    }

    public boolean isReadonly() {
        return false;
    }

    State getState() {
        return state;
    }

    protected void setState(State state) {
        this.state = state;
    }

    public boolean wasNew() {
        switch (state) {
            case RemovedNew:
            case SavedNew:
            case RemovedSavedNew:
                return true;

            case RemovedSaved:
            case Saved:
                return false;

            default:
                throw new IllegalStateException("Entity is not in removed or saved state.");
        }
    }

    public boolean wasSaved() {
        switch (state) {
            case RemovedSaved:
            case RemovedSavedNew:
                return true;
        }
        return false;
    }

    @NotNull
    public String getType() {
        return type;
    }

    protected void setType(String type) {
        this.type = type;
    }

    private static final StandardEventHandler<Object, Object, EntityId> getIdEventHandler = new StandardEventHandler<Object, Object, EntityId>() {

        EntityId processOpenFromAnotherSessionSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return processOpenSaved(entity, param1, param2);
        }

        @Override
        EntityId processOpenFromAnotherSessionRemoved(TransientEntityImpl entity, Object param1, Object param2) {
            return processOpenRemoved(entity, param1, param2);
        }

        @Override
        protected EntityId processClosedRemoved(TransientEntityImpl entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return super.processClosedRemoved(entity, param1, param2);
                case RemovedSaved:
                case RemovedSavedNew:
                    return entity.getPersistentEntityInternal().getId();
            }

            throw new IllegalStateException();
        }

        EntityId processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getId();
        }

        EntityId processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            return new TransientEntityIdImpl(entity.id);
        }

        EntityId processOpenRemoved(TransientEntityImpl entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return new TransientEntityIdImpl(entity.id);
                case RemovedSaved:
                case RemovedSavedNew:
                    return entity.getPersistentEntityInternal().getId();
            }

            throw new IllegalStateException();
        }

        EntityId processSuspendedRemoved(TransientEntityImpl entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return super.processSuspendedRemoved(entity, param1, param2);
                case RemovedSaved:
                case RemovedSavedNew:
                    return entity.getPersistentEntityInternal().getId();
            }

            throw new IllegalStateException();
        }

        EntityId processClosedSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getId();
        }

        EntityId processSuspendedSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getId();
        }

    };


    /**
     * Allows getting id for Committed-Saved, Aborted-Saved and Open-Removed
     *
     * @return entity id
     */
    @NotNull
    public EntityId getId() {
        return getIdEventHandler.handle(this, null, null);
    }

    private final static StandardEventHandler<Object, Object, String> toIdStringEventHandler = new StandardEventHandler<Object, Object, String>() {

        String processOpenFromAnotherSessionSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return processOpenSaved(entity, param1, param2);
        }

        String processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().toIdString();
        }

        String processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            return Integer.toString(entity.id);
        }

        String processOpenRemoved(TransientEntityImpl entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return Integer.toString(entity.id);
                case RemovedSaved:
                case RemovedSavedNew:
                    return entity.getPersistentEntityInternal().toIdString();
            }

            throw new IllegalStateException();
        }

        String processSuspendedRemoved(TransientEntityImpl entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    super.processSuspendedRemoved(entity, param1, param2);
                case RemovedSaved:
                case RemovedSavedNew:
                    return entity.getPersistentEntityInternal().toIdString();
            }

            throw new IllegalStateException();
        }

        String processClosedSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().toIdString();
        }

        String processSuspendedSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().toIdString();
        }

    };


    @NotNull
    public String toIdString() {
        return toIdStringEventHandler.handle(this, null, null);
    }

    protected void setId(TransientEntityIdImpl id) {
        this.id = id.hashCode();
    }

    private final static StandardEventHandler<Entity, Object, Object> setPersistentEntityEventHandler = new StandardEventHandler<Entity, Object, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, Entity param1, Object param2) {
            throw new IllegalStateException("Transient entity already associated with persistent entity. " + entity);
        }

        Object processOpenNew(TransientEntityImpl entity, Entity param1, Object param2) {
            entity.setPersistentEntityInternal(param1);
            entity.state = State.SavedNew;
            return null;
        }

    };

    void setPersistentEntity(@NotNull final Entity persistentEntity) {
        if (persistentEntity instanceof TransientEntity) {
            throw new IllegalArgumentException("Can't create transient entity as wrapper for another transient entity. " + TransientEntityImpl.this);
        }

        setPersistentEntityEventHandler.handle(this, persistentEntity, null);
    }

    private final static StandardEventHandler<Object, Object, Object> clearPersistentEntityEventHandler = new StandardEventHandler<Object, Object, Object>() {
        Object processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            entity.setPersistentEntityInternal(null);
            entity.state = State.New;
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

    };


    void clearPersistentEntity() {
        clearPersistentEntityEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, Object> updateVersionEventHandler = new StandardEventHandler<Object, Object, Object>() {

        Object processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

        Object processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            entity.version = entity.getPersistentEntityInternal().getVersion();
            return null;
        }
    };


    void updateVersion() {
        updateVersionEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, List<String>> getPropertyNamesEventHandler = new StandardEventHandler<Object, Object, List<String>>() {
        List<String> processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getPropertyNames();
        }

        List<String> processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

    };

    @NotNull
    public List<String> getPropertyNames() {
        return getPropertyNamesEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, List<String>> getBlobNamesEventHandler = new StandardEventHandler<Object, Object, List<String>>() {
        List<String> processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getBlobNames();
        }

        List<String> processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

    };


    @NotNull
    public List<String> getBlobNames() {
        return getBlobNamesEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, List<String>> getLinkNamesEventHandler = new StandardEventHandler<Object, Object, List<String>>() {
        List<String> processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getLinkNames();
        }

        List<String> processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

    };

    @NotNull
    public List<String> getLinkNames() {
        return getLinkNamesEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, Integer> getVersionEventHandler = new StandardEventHandler<Object, Object, Integer>() {
        Integer processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            return entity.version;
        }

        Integer processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

    };


    public int getVersion() {
        return getVersionEventHandler.handle(this, null, null);
    }

    int getVersionInternal() {
        return version;
    }

    public boolean isUpToDate() {
        return getPersistentEntity().isUpToDate();
    }

    private static final StandardEventHandler<Object, Object, List<Entity>> getHistoryEventHandler = new StandardEventHandler<Object, Object, List<Entity>>() {
        List<Entity> processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            final List<Entity> history = entity.getPersistentEntityInternal().getHistory();
            final List<Entity> result = new ArrayList<Entity>(history.size());
            final TransientStoreSession session = entity.getTransientStoreSession();
            for (final Entity _entity : history) {
                result.add(session.newEntity(_entity));
            }
            return result;
        }

        List<Entity> processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            // new transient entity has no history
            return Collections.emptyList();
        }

    };


    @NotNull
    public List<Entity> getHistory() {
        return getHistoryEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, Entity> getNextVersionEventHandler = new StandardEventHandler<Object, Object, Entity>() {
        Entity processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            final Entity e = entity.getPersistentEntityInternal().getNextVersion();
            return e == null ? null : entity.getThreadStoreSession().newEntity(e);
        }

        Entity processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            return null;
        }

    };


    @Nullable
    public Entity getNextVersion() {
        return getNextVersionEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Object, Object, Entity> getPreviousVersionEventHandler = new StandardEventHandler<Object, Object, Entity>() {
        Entity processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            final Entity e = entity.getPersistentEntityInternal().getPreviousVersion();
            return e == null ? null : entity.getThreadStoreSession().newEntity(e);
        }

        Entity processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            return null;
        }

    };

    @Nullable
    public Entity getPreviousVersion() {
        return getPreviousVersionEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<Entity, Object, Integer> compareToEventHandler = new StandardEventHandler<Entity, Object, Integer>() {
        Integer processOpenSaved(TransientEntityImpl entity, Entity e, Object param2) {
            return entity.getPersistentEntityInternal().compareTo(e);
        }

        Integer processOpenNew(TransientEntityImpl entity, Entity param, Object param2) {
            entity.throwNoPersistentEntity();
            return null;
        }

    };


    public int compareTo(final Entity e) {
        return compareToEventHandler.handle(this, e, null);
    }

    /**
     * Called by BasePersistentClassImpl by default
     *
     * @return debug presentation
     */
    public String getDebugPresentation() {
        final Entity pe = getPersistentEntityInternal();

        final StringBuilder sb = new StringBuilder();
        if (pe != null) {
            sb.append(pe);
        } else {
            sb.append(type);
        }

        sb.append(" (");
        sb.append(state);

        sb.append(")");

        return sb.toString();
    }

    public String toString() {
        //rollback to original implementation due to stackoverflows
        //TODO: implement smart toString for persistent enums
        return getDebugPresentation();
        /*
            // delegate to Persistent Class implementation
            BasePersistentClassImpl pc = (BasePersistentClassImpl) DnqUtils.getPersistentClassInstance(this, this.getType());
            return pc == null ? getDebugPresentation() : pc.toString(this);
        */
    }

    private static final StandardEventHandler<TransientEntity, Object, Boolean> equalsEventHandler = new StandardEventHandler<TransientEntity, Object, Boolean>() {

        @Override
        Boolean processClosedNew(TransientEntityImpl entity, TransientEntity param1, Object param2) {
            // entity from closed session in new state can't be equals with anything
            return false;
        }

        Boolean processOpenFromAnotherSessionSaved(TransientEntityImpl entity, TransientEntity that, Object param2) {
            return processOpenSaved(entity, that, param2);
        }

        Boolean processOpenSaved(TransientEntityImpl entity, TransientEntity that, Object param2) {
            return checkEquals(entity, that);
        }

        Boolean processClosedSaved(TransientEntityImpl entity, TransientEntity that, Object param2) {
            return processOpenSaved(entity, that, param2);
        }

        Boolean processOpenNew(TransientEntityImpl entity, TransientEntity that, Object param2) {
            return entity == that;
        }

        @Override
        protected Boolean processClosedRemoved(TransientEntityImpl entity, TransientEntity that, Object param2) {
            return processOpenRemoved(entity, that, param2);
        }

        Boolean processOpenRemoved(TransientEntityImpl entity, TransientEntity that, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return entity == that;
                case RemovedSaved:
                case RemovedSavedNew:
                    return checkEquals(entity, that);
            }

            return false;
        }

        Boolean processSuspendedSaved(TransientEntityImpl entity, TransientEntity that, Object param2) {
            return that.isSaved() && (entity.getId().equals(that.getId()) && entity.getStore().equals(that.getStore()));
        }

    };

    /*
     * Internal check whether entities are eqaul or not.
     * Check id's and stores.
     * @return true if entities are equal
     */
    private static boolean checkEquals(@NotNull TransientEntity entity, @NotNull TransientEntity that) {
        //Check stores & EntityIds
        return (that.isSaved() || that.wasSaved()) &&
                (entity.getId().equals(that.getId()) && entity.getStore().equals(that.getStore()));
    }


    @SuppressWarnings({"SimplifiableIfStatement"})
    public boolean equals(Object obj) {
        if (!(obj instanceof TransientEntity)) {
            return false;
        }

        return obj == this || equalsEventHandler.handle(this, (TransientEntity) obj, null);
    }

    public int hashCode() {
        if (sessionId == getStore().getThreadSession().getId()) {
            switch (state) {
                // to satisfy hashCode contract, return old hashCode for saved entities that was new, later, in this session
                case SavedNew:
                case New:
                case RemovedNew:
                case RemovedSavedNew:
                    return System.identityHashCode(this);

                case RemovedSaved:
                case Saved:
                    return getPersistentEntityInternal().hashCode();
            }
        } else {
            // access from another session
            switch (state) {
                case New:
                case RemovedNew:
                    throw new IllegalStateException("Can't access new transient entity from another session");

                case SavedNew:
                case RemovedSaved:
                case RemovedSavedNew:
                case Saved:
                    return getPersistentEntityInternal().hashCode();
            }
        }

        throw new IllegalStateException("Illegal state [" + state + "]");
    }

    public void clearCaches() {
        //TODO: revisit Entity interface and remove these stub method
    }

    private Object throwNoPersistentEntity() throws IllegalStateException {
        throw new IllegalStateException("Transient Objectentity has no associated persistent entity. " + this);
    }

    private static final StandardEventHandler<String, Object, Comparable> getPropertyEventHandler = new StandardEventHandler2<String, Object, Comparable>() {
        Comparable processOpenSaved(TransientEntityImpl entity, String propertyName, Object param2) {
            return _(entity).getPropertyInSavedStateInternal(propertyName);
        }

        Comparable processOpenNew(TransientEntityImpl entity, String propertyName, Object param2) {
            return _(entity).getPropertyInNewStateInternal(propertyName);
        }

    };


    @Nullable
    public Comparable getProperty(@NotNull final String propertyName) {
        return getPropertyEventHandler.handle(this, propertyName, null);
    }

    @Nullable
    Comparable getPropertyInSavedStateInternal(@NotNull final String propertyName) {
        if (!(this.propertiesCache.containsKey(propertyName))) {
            final Comparable v = this.getPersistentEntityInternal().getProperty(propertyName);
            this.propertiesCache.put(propertyName, v);
            return v;
        } else {
            return this.propertiesCache.get(propertyName);
        }
    }

    @Nullable
    Comparable getPropertyInNewStateInternal(@NotNull final String propertyName) {
        return this.propertiesCache.get(propertyName);
    }

    private static final StandardEventHandler<String, Comparable, Object> setPropertyEventHandler = new StandardEventHandler2<String, Comparable, Object>() {
        Object processOpenSaved(TransientEntityImpl entity, String propertyName, Comparable value) {
            entity.getTransientStoreSession().getTransientChangesTracker().propertyChanged(
                    entity,
                    propertyName,
                    entity.isSaved() ? _(entity).getPersistentEntityInternal().getProperty(propertyName) : null,
                    value);
            putValueInPropertiesCache(entity, propertyName, value);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String propertyName, Comparable value) {
            return processOpenSaved(entity, propertyName, value);
        }

    };

    public void setProperty(@NotNull final String propertyName, @NotNull final Comparable value) {
        setPropertyEventHandler.handle(this, propertyName, value);
    }


    private static final StandardEventHandler<String, Object, Object> deletePropertyEventHandler = new StandardEventHandler2<String, Object, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, String propertyName, Object value) {
            entity.getTransientStoreSession().getTransientChangesTracker().propertyDeleted(
                    entity,
                    propertyName,
                    entity.isSaved() ? _(entity).getPersistentEntityInternal().getProperty(propertyName) : entity.getProperty(propertyName));
            putValueInPropertiesCache(entity, propertyName, null);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String propertyName, Object value) {
            return processOpenSaved(entity, propertyName, value);
        }

    };


    public void deleteProperty(@NotNull final String propertyName) {
        deletePropertyEventHandler.handle(this, propertyName, null);
    }

    private static final StandardEventHandler<String, Object, InputStream> getBlobEventHandler = new StandardEventHandler2<String, Object, InputStream>() {
        InputStream processOpenSaved(TransientEntityImpl entity, String blobName, Object value) {
            if (!_(entity).fileBlobsCache.containsKey(blobName)) {
                //TODO: bad solution - it breaks transaction isolation.
                //TODO: Better solution is to get blob from persistent store only ones and save it somehow in transient session.
                return entity.getPersistentEntityInternal().getBlob(blobName);
            }

            File f = _(entity).fileBlobsCache.get(blobName);

            try {
                return f == null ? null : new FileInputStream(f);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        InputStream processOpenNew(TransientEntityImpl entity, String blobName, Object value) {
            File f = _(entity).fileBlobsCache.get(blobName);

            try {
                return f == null ? null : new FileInputStream(f);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

    };


    @Nullable
    public InputStream getBlob(@NotNull final String blobName) {
        return getBlobEventHandler.handle(this, blobName, null);
    }

    private static final StandardEventHandler<String, InputStream, Object> setBlobEventHandler = new StandardEventHandler2<String, InputStream, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, String blobName, InputStream blob) {
            File f = _(entity).createFile(blob);
            entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, f);
            _(entity).fileBlobsCache.put(blobName, f);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String blobName, InputStream blob) {
            return processOpenSaved(entity, blobName, blob);
        }

    };


    public void setBlob(@NotNull final String blobName, @NotNull final InputStream blob) {
        setBlobEventHandler.handle(this, blobName, blob);
    }

    private File createFile(InputStream blob) {
        File outputFile = getTransientStoreSession().createBlobFile(true);

        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(outputFile));
            IOUtils.copy(blob, out);
        } catch (IOException e) {
            throw new RuntimeException("Can't save blob to file [" + outputFile.getAbsolutePath() + "]", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }

        return outputFile;
    }

    private static final StandardEventHandler<String, File, Object> setBlobFileEventHandler = new StandardEventHandler2<String, File, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, String blobName, File file) {
            File f = _(entity).moveOrCopy(file);
            entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, f);
            _(entity).fileBlobsCache.put(blobName, f);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String blobName, File file) {
            return processOpenSaved(entity, blobName, file);
        }

    };


    public void setBlob(@NotNull final String blobName, @NotNull final File file) {
        setBlobFileEventHandler.handle(this, blobName, file);
    }

    private File moveOrCopy(File inputFile) {
        File outputFile = getTransientStoreSession().createBlobFile(false);

        if (!inputFile.renameTo(outputFile)) {
            if (log.isDebugEnabled()) {
                log.warn("Can't move file [" + inputFile.getAbsolutePath() + "] to file [" + outputFile.getAbsolutePath() + "]. Try copy.");
            }

            BufferedInputStream in = null;
            BufferedOutputStream out = null;
            try {
                in = new BufferedInputStream(new FileInputStream(inputFile));
                out = new BufferedOutputStream(new FileOutputStream(outputFile));
                IOUtils.copy(in, out);
            } catch (IOException e) {
                throw new RuntimeException("Can't copy file [" + inputFile.getAbsolutePath() + "] to file [" + outputFile.getAbsolutePath() + "]");
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        return outputFile;
    }

    private static final StandardEventHandler<String, Object, Object> deleteBlobEventHandler = new StandardEventHandler2<String, Object, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, String blobName, Object param2) {
            _(entity).fileBlobsCache.put(blobName, null);
            entity.getTransientStoreSession().getTransientChangesTracker().blobDeleted(entity, blobName);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String blobName, Object param2) {
            return processOpenSaved(entity, blobName, param2);
        }

    };


    public void deleteBlob(@NotNull final String blobName) {
        deleteBlobEventHandler.handle(this, blobName, null);
    }


    private static final StandardEventHandler<String, Object, String> getBlobStringEventHandler = new StandardEventHandler2<String, Object, String>() {
        String processOpenSaved(TransientEntityImpl entity, String blobName, Object param2) {
            if (!_(entity).propertiesCache.containsKey(blobName)) {
                final String value = entity.getPersistentEntityInternal().getBlobString(blobName);
                putValueInPropertiesCache(entity, blobName, value);
                return value;
            }
            return (String) _(entity).propertiesCache.get(blobName);
        }

        String processOpenNew(TransientEntityImpl entity, String blobName, Object param2) {
            return (String) _(entity).propertiesCache.get(blobName);
        }

    };


    @Nullable
    public String getBlobString(@NotNull final String blobName) {
        return getBlobStringEventHandler.handle(this, blobName, null);
    }

    private static final StandardEventHandler<String, String, Object> setBlobStringEventHandler = new StandardEventHandler2<String, String, Object>() {
        Object processOpenSaved(TransientEntityImpl entity, String blobName, String blobString) {
            entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, blobString);
            putValueInPropertiesCache(entity, blobName, blobString);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String blobName, String blobString) {
            return processOpenSaved(entity, blobName, blobString);
        }

    };


    public void setBlobString(@NotNull final String blobName, @NotNull final String blobString) {
        setBlobStringEventHandler.handle(this, blobName, blobString);
    }

    private static final StandardEventHandler<String, Object, Object> deleteBlobStringEventHandler = new StandardEventHandler2<String, Object, Object>() {
        Object processOpenSaved(TransientEntityImpl entity, String blobName, Object blobString) {
            putValueInPropertiesCache(entity, blobName, null);
            entity.getTransientStoreSession().getTransientChangesTracker().blobDeleted(entity, blobName);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String blobName, Object blobString) {
            return processOpenSaved(entity, blobName, blobString);
        }

    };


    public void deleteBlobString(@NotNull final String blobName) {
        deleteBlobStringEventHandler.handle(this, blobName, null);
    }

    private static final StandardEventHandler<String, Entity, Object> addLinkEventHandler = new StandardEventHandler2<String, Entity, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, String linkName, Entity target) {
            _(entity).getLinksManager(linkName).addLink((TransientEntity) target);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

    };

    public void addLink(@NotNull final String linkName, @NotNull final Entity target) {
        addLinkEventHandler.handle(this, linkName, target);
    }

    private static final StandardEventHandler<String, Entity, Object> setLinkEventHandler = new StandardEventHandler2<String, Entity, Object>() {
        Object processOpenSaved(TransientEntityImpl entity, String linkName, Entity target) {
            _(entity).getLinksManager(linkName).setLink((TransientEntity) target);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

    };


    public void setLink(@NotNull final String linkName, @NotNull final Entity target) {
        setLinkEventHandler.handle(this, linkName, target);
    }

    private static final StandardEventHandler<String, Entity, Object> deleteLinkEventHandler = new StandardEventHandler2<String, Entity, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, String linkName, Entity target) {
            _(entity).getLinksManager(linkName).deleteLink((TransientEntity) target);
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

    };


    public void deleteLink(@NotNull final String linkName, @NotNull final Entity target) {
        deleteLinkEventHandler.handle(this, linkName, target);
    }

    private static final StandardEventHandler<String, Object, Object> deleteLinksEventHandler = new StandardEventHandler2<String, Object, Object>() {
        Object processOpenSaved(TransientEntityImpl entity, String linkName, Object param2) {
            _(entity).getLinksManager(linkName).deleteLinks();
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    public void deleteLinks(@NotNull final String linkName) {
        deleteLinksEventHandler.handle(this, linkName, null);
    }

    private static final StandardEventHandler<String, Object, Iterable<Entity>> getLinksEventHandler = new StandardEventHandler2<String, Object, Iterable<Entity>>() {
        Iterable<Entity> processOpenSaved(TransientEntityImpl entity, String linkName, Object param2) {
            return _(entity).getLinksManager(linkName).getLinks();
        }

        Iterable<Entity> processOpenNew(TransientEntityImpl entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    @NotNull
    public Iterable<Entity> getLinks(@NotNull final String linkName) {
        return getLinksEventHandler.handle(this, linkName, null);
    }

    private static final StandardEventHandler<String, Object, Entity> getLinkEventHandler = new StandardEventHandler2<String, Object, Entity>() {

        Entity processOpenSaved(TransientEntityImpl entity, String linkName, Object param2) {
            return _(entity).getLinksManager(linkName).getLink();
        }

        Entity processOpenNew(TransientEntityImpl entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    @Nullable
    public Entity getLink(@NotNull final String linkName) {
        return getLinkEventHandler.handle(this, linkName, null);
    }

    private static final StandardEventHandler<Collection<String>, Object, EntityIterable> getLinksFromSetEventHandler = new StandardEventHandler2<Collection<String>, Object, EntityIterable>() {
        EntityIterable processOpenSaved(TransientEntityImpl entity, Collection<String> linkNames, Object param2) {
            return new PersistentEntityIterableWrapper(_(entity).getPersistentEntityInternal().getLinks(linkNames)) {
                @Override
                public EntityIterator iterator() {
                    return new PersistentEntityIteratorWithPropIdWrapper((EntityIteratorWithPropId) wrappedIterable.iterator(),
                            (TransientStoreSession) ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession());
                }
            };
        }

        EntityIterable processOpenNew(TransientEntityImpl entity, Collection<String> linkNames, Object param2) {
            return processOpenSaved(entity, linkNames, param2);
        }

    };

    @Nullable
    public EntityIterable getLinks(@NotNull final Collection<String> linkNames) {
        return getLinksFromSetEventHandler.handle(this, linkNames, null);
    }

    private static final StandardEventHandler<String, Object, Long> getLinksSizeEventHandler = new StandardEventHandler2<String, Object, Long>() {
        Long processOpenSaved(TransientEntityImpl entity, String linkName, Object param2) {
            return _(entity).getLinksManager(linkName).getLinksSize();
        }

        Long processOpenNew(TransientEntityImpl entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    public long getLinksSize(@NotNull final String linkName) {
        return getLinksSizeEventHandler.handle(this, linkName, null);
    }

    @NotNull
    public List<Pair<String, EntityIterable>> getIncomingLinks() {
        final List<Pair<String, EntityIterable>> result = new ArrayList<Pair<String, EntityIterable>>();
        final TransientStoreSession session = getTransientStoreSession();
        //Why persistent store is here instead of transient?
        final StoreTransaction persistentTxn = session.getPersistentTransaction();
        final ModelMetaData mmd = ((TransientEntityStore) session.getStore()).getModelMetaData();
        if (mmd != null) {
            final EntityMetaData emd = mmd.getEntityMetaData(getType());
            if (emd != null) {
                // EntityMetaData can be null during refactorings
                for (final Map.Entry<String, Set<String>> entry : emd.getIncomingAssociations(mmd).entrySet()) {
                    final String entityType = entry.getKey();
                    //Link name clash possible!!!!
                    for (final String linkName : entry.getValue()) {
                        // Can value be list?
                        result.add(new Pair<String, EntityIterable>(linkName, session.findLinks(entityType, this, linkName)));
                    }
                }
            }
        }
        return result;
    }

    private static final StandardEventHandler<Object, Object, Object> deleteEventHandler = new StandardEventHandler2<Object, Object, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            entity.getTransientStoreSession().getTransientChangesTracker().entityDeleted(entity);
            switch (entity.getState()) {
                case Saved:
                    entity.setState(State.RemovedSaved);
                    break;
                case SavedNew:
                    entity.setState(State.RemovedSavedNew);
                    break;
            }
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            entity.getTransientStoreSession().getTransientChangesTracker().entityDeleted(entity);
            entity.setState(State.RemovedNew);
            return null;
        }

    };

    public void delete() {
        deleteEventHandler.handle(this, null, null);
    }

    //TODO: check if method can me deleted
    @SuppressWarnings({"UnusedDeclaration"})
    private EntityMetaData getEntityMetadata() {
        final ModelMetaData md = ((TransientEntityStore) this.getStore()).getModelMetaData();
        return md == null ? null : md.getEntityMetaData(this.getType());
    }

    /**
     * Called by session on session abort
     */
    void rollbackDelete() {
        switch (getState()) {
            case RemovedNew:
                setState(State.New);
                break;

            case RemovedSaved:
                setState(State.Saved);
                break;

            case RemovedSavedNew:
                setState(State.SavedNew);
                break;
        }
    }

    private static final StandardEventHandler<Object, Object, Object> newVersionEventHandler = new StandardEventHandler2<Object, Object, Object>() {

        Object processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            entity.getPersistentEntityInternal().newVersion();
            return null;
        }

        Object processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            throw new UnsupportedOperationException("Not supported by transient entity in the current state. " + entity);
        }

    };


    public void newVersion() {
        newVersionEventHandler.handle(this, null, null);
    }

    @Deprecated
    public void markAsTemporary() {
        //TODO: remove
    }

    private TransientLinksManager getLinksManager(@NotNull String linkName) {
        TransientLinksManager m = linksManagers.get(linkName);

        if (m == null) {
            final ModelMetaData md = ((TransientEntityStore) getStore()).getModelMetaData();
            final AssociationEndMetaData aemd;
            final EntityMetaData emd;
            if (md == null || (emd = md.getEntityMetaData(getType())) == null || (aemd = emd.getAssociationEndMetaData(linkName)) == null) {
                if (log.isTraceEnabled()) {
                    log.trace("Model-meta data is not defined. Use unified link manager for link [" + linkName + "]");
                }
                m = new UnifiedTransientLinksManagerImpl(linkName, this);
            } else {
                switch (aemd.getCardinality()) {
                    case _0_1:
                    case _1:
                        m = new SingleTransientLinksManagerImpl(linkName, this);
                        break;

                    case _0_n:
                    case _1_n:
                        m = new MultipleTransientLinksManagerImpl(linkName, this, aemd.getOppositeEntityMetaData().getType());
                        break;
                }
            }

            linksManagers.put(linkName, m);
        }

        return m;
    }

    /**
     * Is called by session on flush, because all files stored in temp location will be moved to persistent store location.
     */
    void clearFileBlobsCache() {
        fileBlobsCache.clear();
    }

    /**
     * Notifies links managers about successful flush. Called by transient session
     */
    void updateLinkManagers() {
        for (TransientLinksManager lm : linksManagers.values()) {
            lm.flushed();
        }
    }

    private static final StandardEventHandler<Object, Object, Boolean> hasChangesEventHandler = new StandardEventHandler2<Object, Object, Boolean>() {

        Boolean processOpenNew(TransientEntityImpl entity, Object param1, Object param2) {
            return true;
        }

        Boolean processOpenSaved(TransientEntityImpl entity, Object param1, Object param2) {
            final TransientStoreSession session = entity.getTransientStoreSession();
            Map<String, PropertyChange> changesProperties = session.getTransientChangesTracker().getChangedPropertiesDetailed(entity);
            Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(entity);

            return (changesLinks != null && !changesLinks.isEmpty()) || (changesProperties != null && !changesProperties.isEmpty());
        }

    };


    public boolean hasChanges() {
        return hasChangesEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<String, Object, Boolean> hasChangesForPropertyEventHandler = new StandardEventHandler2<String, Object, Boolean>() {

        Boolean processOpenNew(TransientEntityImpl entity, String property, Object param2) {
            return processOpenSaved(entity, property, param2);
        }

        Boolean processOpenSaved(TransientEntityImpl entity, String property, Object param2) {
            final TransientStoreSession session = entity.getTransientStoreSession();
            Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(entity);
            Map<String, PropertyChange> changesProperties = session.getTransientChangesTracker().getChangedPropertiesDetailed(entity);

            return (changesLinks != null && changesLinks.containsKey(property)) ||
                    (changesProperties != null && changesProperties.containsKey(property));
        }

    };

    public boolean hasChanges(final String property) {
        return hasChangesForPropertyEventHandler.handle(this, property, null);
    }

    private static final StandardEventHandler<String[], Object, Boolean> hasChangesExceptingEventHandler = new StandardEventHandler2<String[], Object, Boolean>() {

        Boolean processOpenNew(TransientEntityImpl entity, String[] properties, Object param2) {
            return processOpenSaved(entity, properties, param2);
        }

        Boolean processOpenSaved(TransientEntityImpl entity, String[] properties, Object param2) {
            final TransientStoreSession session = entity.getTransientStoreSession();
            Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(entity);
            Map<String, PropertyChange> changesProperties = session.getTransientChangesTracker().getChangedPropertiesDetailed(entity);

            int found = 0;
            int changed;
            if (changesLinks == null && changesProperties == null) {
                return false;
            } else {
                for (String property : properties) {
                    // all properties have to be changed
                    if (entity.hasChanges(property)) found++;
                }
                if (changesLinks == null) {
                    changed = changesProperties.size();
                } else if (changesProperties == null) {
                    changed = changesLinks.size();
                } else {
                    changed = changesLinks.size() + changesProperties.size();
                }
                return changed > found;
            }
        }

    };

    public boolean hasChangesExcepting(String[] properties) {
        return hasChangesExceptingEventHandler.handle(this, properties, null);
    }

    private static final StandardEventHandler<String, Boolean, EntityIterable> addedRemovedLinksEventHandler = new StandardEventHandler2<String, Boolean, EntityIterable>() {

        @NotNull
        EntityIterable processOpenNew(TransientEntityImpl entity, String name, Boolean removed) {
            return EntityIterableBase.EMPTY;
        }

        @NotNull
        EntityIterable processOpenSaved(TransientEntityImpl entity, String name, Boolean removed) {
            Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);

            if (changesLinks != null) {
                final LinkChange linkChange = changesLinks.get(name);
                if (linkChange != null) {
                    Set<TransientEntity> result = removed ? linkChange.getRemovedEntities() : linkChange.getAddedEntities();
                    if (result != null) {
                        if (removed) {
                            return new TransientEntityIterable(result) {
                                @Override
                                public long size() {
                                    return linkChange.getRemovedEntitiesSize();
                                }

                                @Override
                                public long count() {
                                    return linkChange.getRemovedEntitiesSize();
                                }
                            };
                        } else {
                            return new TransientEntityIterable(result) {
                                @Override
                                public long size() {
                                    return linkChange.getAddedEntitiesSize();
                                }

                                @Override
                                public long count() {
                                    return linkChange.getAddedEntitiesSize();
                                }
                            };
                        }
                    }
                }
            }
            return EntityIterableBase.EMPTY;
        }

    };

    public EntityIterable getAddedLinks(final String name) {
        return addedRemovedLinksEventHandler.handle(this, name, false);
    }

    public EntityIterable getRemovedLinks(final String name) {
        return addedRemovedLinksEventHandler.handle(this, name, true);
    }

    private static final StandardEventHandler<Set<String>, Boolean, EntityIterable> addedRemovedFromSetLinksEventHandler = new StandardEventHandler2<Set<String>, Boolean, EntityIterable>() {

        @NotNull
        EntityIterable processOpenNew(TransientEntityImpl entity, Set<String> linkNames, Boolean removed) {
            return UniversalEmptyEntityIterable.INSTANCE;
        }

        @NotNull
        EntityIterable processOpenSaved(TransientEntityImpl entity, final Set<String> linkNames, final Boolean removed) {
            return AddedOrRemovedLinksFromSetTransientEntityIterable.get(
                    entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity),
                    linkNames, removed
            );
        }

    };

    public EntityIterable getAddedLinks(final Set<String> linkNames) {
        return addedRemovedFromSetLinksEventHandler.handle(this, linkNames, false);
    }

    public EntityIterable getRemovedLinks(final Set<String> linkNames) {
        return addedRemovedFromSetLinksEventHandler.handle(this, linkNames, true);
    }

    protected static abstract class StandardEventHandler<P1, P2, T> {

        protected StandardEventHandler() {
        }

        T handle(@NotNull TransientEntityImpl entity, @Nullable P1 param1, @Nullable P2 param2) {
            do {
                final TransientStoreSession session = entity.store.getStoreSession(entity.sessionId);
                if (session == null || session.isAborted() || session.isCommitted()) {
                    switch (entity.state) {
                        case New:
                            return processClosedNew(entity, param1, param2);

                        case Saved:
                        case SavedNew:
                            return processClosedSaved(entity, param1, param2);

                        case RemovedNew:
                        case RemovedSaved:
                        case RemovedSavedNew:
                            return processClosedRemoved(entity, param1, param2);
                    }
                } else if (session.isOpened()) {
                    // check that entity is accessed in the same thread as session
                    final TransientStoreSession storeSession = entity.getStore().getThreadSession();
                    if (session.getId() != storeSession.getId()) {
                        switch (entity.state) {
                            case New:
                                return processOpenFromAnotherSessionNew(entity, param1, param2);

                            case Saved:
                            case SavedNew:
                                return processOpenFromAnotherSessionSaved(entity, param1, param2);

                            case RemovedNew:
                            case RemovedSaved:
                            case RemovedSavedNew:
                                return processOpenFromAnotherSessionRemoved(entity, param1, param2);
                        }
                    }
                    switch (entity.state) {
                        case New:
                            return processOpenNew(entity, param1, param2);

                        case Saved:
                        case SavedNew:
                            return processOpenSaved(entity, param1, param2);

                        case RemovedNew:
                        case RemovedSaved:
                        case RemovedSavedNew:
                            return processOpenRemoved(entity, param1, param2);
                    }
                }
            } while (true);
            //throw new IllegalStateException("Unknown session state. " + entity);
        }

        T processClosedNew(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new IllegalStateException("Illegal combination of session and transient entity states (Committed or Aborted, New). Possible bug. " + entity);
        }

        protected T processClosedRemoved(TransientEntityImpl entity, P1 paraP1, P2 param2) {
            throw new EntityRemovedException(entity);
        }

        @SuppressWarnings({"UnusedDeclaration"})
        T processOpenFromAnotherSessionNew(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + entity);
        }

        T processOpenFromAnotherSessionSaved(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + entity);
        }

        T processOpenFromAnotherSessionRemoved(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + entity);
        }

        T processSuspendedSaved(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new IllegalStateException("Can't access transient saved entity while it's session is suspended. Only getId is permitted. " + entity);
        }

        abstract T processOpenSaved(TransientEntityImpl entity, P1 param1, P2 param2);

        abstract T processOpenNew(TransientEntityImpl entity, P1 param1, P2 param2);

        T processOpenRemoved(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new EntityRemovedException(entity);
        }

        T processSuspendedRemoved(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new EntityRemovedException(entity);
        }

        T processClosedSaved(TransientEntityImpl entity, P1 param1, P2 param2) {
            throw new IllegalStateException("Can't access committed saved entity. Only getId is permitted. " + entity);
        }

    }

    private static abstract class StandardEventHandler2<P1, P2, T> extends StandardEventHandler<P1, P2, T> {
        TransientEntityImpl _(TransientEntityImpl entity) {
            return (TransientEntityImpl) entity;
        }

        void putValueInPropertiesCache(TransientEntityImpl entity, String propertyName, Comparable value) {
            _(entity).propertiesCache.put(propertyName, value);
        }
    }

}
