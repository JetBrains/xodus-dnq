package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.impl.iterate.EntityIterableBase;
import com.jetbrains.teamsys.database.impl.iterate.EntityIteratorBase;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.Map;
import java.util.Set;

/**
 * Date: 05.02.2007
 * Time: 16:34:36
 * <p/>
 * TODO: for blobs implement BlobsManagers, like LinkManager, because there're 3 blob types - String, File, InputStream
 *
 * @author Vadim.Gurov
 */
class TransientEntityImpl extends AbstractTransientEntity {

    private final Map<String, TransientLinksManager> linksManagers = new HashMapDecorator<String, TransientLinksManager>();
    private final Map<String, Comparable> propertiesCache = new HashMapDecorator<String, Comparable>();
    private final Map<String, File> fileBlobsCache = new HashMapDecorator<String, File>();

    TransientEntityImpl(@NotNull String type, @NotNull TransientStoreSession session) {
        setTransientStoreSession(session);
        setType(type);
        setState(State.New);
        setId(new TransientEntityIdImpl());

        session.getTransientChangesTracker().entityAdded(this);

        //trackEntityCreation(session);
    }

    TransientEntityImpl(@NotNull Entity persistentEntity, @NotNull TransientStoreSession session) {
        setTransientStoreSession(session);
        setPersistentEntityInternal(persistentEntity);
        setState(State.Saved);

        //trackEntityCreation(session);
    }

    private static final StandardEventHandler<String, Object, Comparable> getPropertyEventHandler = new StandardEventHandler2<String, Object, Comparable>() {
        Comparable processOpenSaved(AbstractTransientEntity entity, String propertyName, Object param2) {
            return _(entity).getPropertyInSavedStateInternal(propertyName);
        }

        Comparable processOpenNew(AbstractTransientEntity entity, String propertyName, Object param2) {
            return _(entity).getPropertyInNewStateInternal(propertyName);
        }

        Comparable processTemporary(AbstractTransientEntity entity, String propertyName, Object param2) {
            return processOpenNew(entity, propertyName, param2);
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
        Object processOpenSaved(AbstractTransientEntity entity, String propertyName, Comparable value) {
            entity.getTransientStoreSession().getTransientChangesTracker().propertyChanged(
                    entity,
                    propertyName,
                    entity.isSaved() ? _(entity).getPersistentEntityInternal().getProperty(propertyName) : null,
                    value);
            putValueInPropertiesCache(entity, propertyName, value);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String propertyName, Comparable value) {
            return processOpenSaved(entity, propertyName, value);
        }

        Object processTemporary(AbstractTransientEntity entity, String propertyName, Comparable value) {
            putValueInPropertiesCache(entity, propertyName, value);
            return null;
        }

    };

    public void setProperty(@NotNull final String propertyName, @NotNull final Comparable value) {
        setPropertyEventHandler.handle(this, propertyName, value);
    }


    private static final StandardEventHandler<String, Object, Object> deletePropertyEventHandler = new StandardEventHandler2<String, Object, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, String propertyName, Object value) {
            entity.getTransientStoreSession().getTransientChangesTracker().propertyDeleted(
                    entity,
                    propertyName,
                    entity.isSaved() ? _(entity).getPersistentEntityInternal().getProperty(propertyName) : entity.getProperty(propertyName));
            putValueInPropertiesCache(entity, propertyName, null);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String propertyName, Object value) {
            return processOpenSaved(entity, propertyName, value);
        }

        @Override
        Object processTemporary(AbstractTransientEntity entity, String propertyName, Object value) {
            putValueInPropertiesCache(entity, propertyName, null);
            return null;
        }
    };


    public void deleteProperty(@NotNull final String propertyName) {
        deletePropertyEventHandler.handle(this, propertyName, null);
    }

    private static final StandardEventHandler<String, Object, InputStream> getBlobEventHandler = new StandardEventHandler2<String, Object, InputStream>() {
        InputStream processOpenSaved(AbstractTransientEntity entity, String blobName, Object value) {
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

        InputStream processOpenNew(AbstractTransientEntity entity, String blobName, Object value) {
            File f = _(entity).fileBlobsCache.get(blobName);

            try {
                return f == null ? null : new FileInputStream(f);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        InputStream processTemporary(AbstractTransientEntity entity, String blobName, Object value) {
            return processOpenNew(entity, blobName, value);
        }

    };


    @Nullable
    public InputStream getBlob(@NotNull final String blobName) {
        return getBlobEventHandler.handle(this, blobName, null);
    }

    private static final StandardEventHandler<String, InputStream, Object> setBlobEventHandler = new StandardEventHandler2<String, InputStream, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, String blobName, InputStream blob) {
            File f = _(entity).createFile(blob);
            entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, f);
            _(entity).fileBlobsCache.put(blobName, f);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String blobName, InputStream blob) {
            return processOpenSaved(entity, blobName, blob);
        }

        Object processTemporary(AbstractTransientEntity entity, String blobName, InputStream blob) {
            File f = _(entity).createFile(blob);
            _(entity).fileBlobsCache.put(blobName, f);
            return null;
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

        Object processOpenSaved(AbstractTransientEntity entity, String blobName, File file) {
            File f = _(entity).moveOrCopy(file);
            entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, f);
            _(entity).fileBlobsCache.put(blobName, f);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String blobName, File file) {
            return processOpenSaved(entity, blobName, file);
        }

        @Override
        Object processTemporary(AbstractTransientEntity entity, String blobName, File file) {
            _(entity).fileBlobsCache.put(blobName, file);
            return null;
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

        Object processOpenSaved(AbstractTransientEntity entity, String blobName, Object param2) {
            _(entity).fileBlobsCache.put(blobName, null);
            entity.getTransientStoreSession().getTransientChangesTracker().blobDeleted(entity, blobName);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String blobName, Object param2) {
            return processOpenSaved(entity, blobName, param2);
        }

        Object processTemporary(AbstractTransientEntity entity, String blobName, Object param2) {
            _(entity).fileBlobsCache.put(blobName, null);
            return null;
        }

    };


    public void deleteBlob(@NotNull final String blobName) {
        deleteBlobEventHandler.handle(this, blobName, null);
    }


    private static final StandardEventHandler<String, Object, String> getBlobStringEventHandler = new StandardEventHandler2<String, Object, String>() {
        String processOpenSaved(AbstractTransientEntity entity, String blobName, Object param2) {
            if (!_(entity).propertiesCache.containsKey(blobName)) {
                final String value = entity.getPersistentEntityInternal().getBlobString(blobName);
                putValueInPropertiesCache(entity, blobName, value);
                return value;
            }
            return (String) _(entity).propertiesCache.get(blobName);
        }

        String processOpenNew(AbstractTransientEntity entity, String blobName, Object param2) {
            return (String) _(entity).propertiesCache.get(blobName);
        }

        String processTemporary(AbstractTransientEntity entity, String blobName, Object param2) {
            return processOpenNew(entity, blobName, param2);
        }

    };


    @Nullable
    public String getBlobString(@NotNull final String blobName) {
        return getBlobStringEventHandler.handle(this, blobName, null);
    }

    private static final StandardEventHandler<String, String, Object> setBlobStringEventHandler = new StandardEventHandler2<String, String, Object>() {
        Object processOpenSaved(AbstractTransientEntity entity, String blobName, String blobString) {
            entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, blobString);
            putValueInPropertiesCache(entity, blobName, blobString);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String blobName, String blobString) {
            return processOpenSaved(entity, blobName, blobString);
        }

        Object processTemporary(AbstractTransientEntity entity, String blobName, String blobString) {
            putValueInPropertiesCache(entity, blobName, blobString);
            return null;
        }

    };


    public void setBlobString(@NotNull final String blobName, @NotNull final String blobString) {
        setBlobStringEventHandler.handle(this, blobName, blobString);
    }

    private static final StandardEventHandler<String, Object, Object> deleteBlobStringEventHandler = new StandardEventHandler2<String, Object, Object>() {
        Object processOpenSaved(AbstractTransientEntity entity, String blobName, Object blobString) {
            putValueInPropertiesCache(entity, blobName, null);
            entity.getTransientStoreSession().getTransientChangesTracker().blobDeleted(entity, blobName);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String blobName, Object blobString) {
            return processOpenSaved(entity, blobName, blobString);
        }

        Object processTemporary(AbstractTransientEntity entity, String blobName, Object blobString) {
            putValueInPropertiesCache(entity, blobName, null);
            return null;
        }

    };


    public void deleteBlobString(@NotNull final String blobName) {
        deleteBlobStringEventHandler.handle(this, blobName, null);
    }

    private static final StandardEventHandler<String, Entity, Object> addLinkEventHandler = new StandardEventHandler2<String, Entity, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, String linkName, Entity target) {
            _(entity).getLinksManager(linkName).addLink((TransientEntity) target);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

        Object processTemporary(AbstractTransientEntity entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

    };

    public void addLink(@NotNull final String linkName, @NotNull final Entity target) {
        addLinkEventHandler.handle(this, linkName, target);
    }

    private static final StandardEventHandler<String, Entity, Object> setLinkEventHandler = new StandardEventHandler2<String, Entity, Object>() {
        Object processOpenSaved(AbstractTransientEntity entity, String linkName, Entity target) {
            _(entity).getLinksManager(linkName).setLink((TransientEntity) target);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

        Object processTemporary(AbstractTransientEntity entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

    };


    public void setLink(@NotNull final String linkName, @NotNull final Entity target) {
        setLinkEventHandler.handle(this, linkName, target);
    }

    private static final StandardEventHandler<String, Entity, Object> deleteLinkEventHandler = new StandardEventHandler2<String, Entity, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, String linkName, Entity target) {
            _(entity).getLinksManager(linkName).deleteLink((TransientEntity) target);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }

        Object processTemporary(AbstractTransientEntity entity, String linkName, Entity target) {
            return processOpenSaved(entity, linkName, target);
        }
    };


    public void deleteLink(@NotNull final String linkName, @NotNull final Entity target) {
        deleteLinkEventHandler.handle(this, linkName, target);
    }

    private static final StandardEventHandler<String, Object, Object> deleteLinksEventHandler = new StandardEventHandler2<String, Object, Object>() {
        Object processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
            _(entity).getLinksManager(linkName).deleteLinks();
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

        Object processTemporary(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    public void deleteLinks(@NotNull final String linkName) {
        deleteLinksEventHandler.handle(this, linkName, null);
    }

    private static final StandardEventHandler<String, Object, EntityIterable> getLinksEventHandler = new StandardEventHandler2<String, Object, EntityIterable>() {
        EntityIterable processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
            return _(entity).getLinksManager(linkName).getLinks();
        }

        EntityIterable processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

        EntityIterable processTemporary(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    @NotNull
    public EntityIterable getLinks(@NotNull final String linkName) {
        return getLinksEventHandler.handle(this, linkName, null);
    }

    private static final StandardEventHandler<String, Object, Entity> getLinkEventHandler = new StandardEventHandler2<String, Object, Entity>() {

        Entity processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
            return _(entity).getLinksManager(linkName).getLink();
        }

        Entity processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

        Entity processTemporary(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    @Nullable
    public Entity getLink(@NotNull final String linkName) {
        return getLinkEventHandler.handle(this, linkName, null);
    }

    private static final StandardEventHandler<String, Object, Long> getLinksSizeEventHandler = new StandardEventHandler2<String, Object, Long>() {
        Long processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
            return _(entity).getLinksManager(linkName).getLinksSize();
        }

        Long processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

        Long processTemporary(AbstractTransientEntity entity, String linkName, Object param2) {
            return processOpenSaved(entity, linkName, param2);
        }

    };

    public long getLinksSize(@NotNull final String linkName) {
        return getLinksSizeEventHandler.handle(this, linkName, null);
    }

    @NotNull
    public Map<String, EntityId> getIncomingLinks() {
        final Map<String, EntityId> result = new HashMapDecorator<String, EntityId>();
        final TransientStoreSession session = getTransientStoreSession();
        final StoreSession persistentSession = session.getPersistentSession();
        final ModelMetaData mmd = ((TransientEntityStore) session.getStore()).getModelMetaData();
        if (mmd != null) {
            final EntityMetaData emd = mmd.getEntityMetaData(getType());
            if (emd != null) {
                // EntityMetaData can be null during refactorings
                for (final Map.Entry<String, Set<String>> entry : emd.getIncomingAssociations(mmd).entrySet()) {
                    final String entityType = entry.getKey();
                    for (final String linkName : entry.getValue()) {
                        final EntityIteratorBase it = (EntityIteratorBase) persistentSession.findLinks(entityType, this, linkName).iterator();
                        while (it.hasNext()) {
                            //TODO: BUG HERE!!!
                            result.put(linkName, it.nextId());
                        }
                    }
                }
            }
        }
        return result;
    }

    private static final StandardEventHandler<Object, Object, Object> deleteEventHandler = new StandardEventHandler2<Object, Object, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            entity.getTransientStoreSession().getTransientChangesTracker().entityDeleted(entity);
            entity.setState(State.RemovedSaved);
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            entity.getTransientStoreSession().getTransientChangesTracker().entityDeleted(entity);
            entity.setState(State.RemovedNew);
            return null;
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            throw new IllegalStateException("Can't delete temporary entity. " + entity);
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
        }
    }

    private static final StandardEventHandler<Object, Object, Object> newVersionEventHandler = new StandardEventHandler2<Object, Object, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            entity.getPersistentEntityInternal().newVersion();
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            throw new UnsupportedOperationException("Not supported by transient entity in the current state. " + entity);
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return processOpenNew(entity, param1, param2);
        }
    };


    public void newVersion() {
        newVersionEventHandler.handle(this, null, null);
    }

    public void markAsTemporary() {
        if (!isNew()) {
            throw new IllegalStateException("An entity in the New state only can be marked as temporary.");
        }
        setState(State.Temporary);
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
                        m = new MultipleTransientLinksManagerImpl(linkName, this);
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

        Boolean processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return true;
        }

        Boolean processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            Map<String, PropertyChange> changesProperties = entity.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(entity);
            Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);

            return (changesLinks != null && !changesLinks.isEmpty()) || (changesProperties != null && !changesProperties.isEmpty());
        }

        @Override
        Boolean processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return true;
        }
    };


    public boolean hasChanges() {
        return hasChangesEventHandler.handle(this, null, null);
    }

    private static final StandardEventHandler<String, Object, Boolean> hasChangesForPropertyEventHandler = new StandardEventHandler2<String, Object, Boolean>() {

        Boolean processOpenNew(AbstractTransientEntity entity, String property, Object param2) {
            return processOpenSaved(entity, property, param2);
        }

        Boolean processTemporary(AbstractTransientEntity entity, String property, Object param2) {
            return false;
        }

        Boolean processOpenSaved(AbstractTransientEntity entity, String property, Object param2) {
            Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);
            Map<String, PropertyChange> changesProperties = entity.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(entity);

            return (changesLinks != null && changesLinks.containsKey(property)) ||
                    (changesProperties != null && changesProperties.containsKey(property));
        }

    };

    public boolean hasChanges(final String property) {
        return hasChangesForPropertyEventHandler.handle(this, property, null);
    }

    private static final StandardEventHandler<String[], Object, Boolean> hasChangesExceptingEventHandler = new StandardEventHandler2<String[], Object, Boolean>() {

        Boolean processOpenNew(AbstractTransientEntity entity, String[] properties, Object param2) {
            return processOpenSaved(entity, properties, param2);
        }

        Boolean processTemporary(AbstractTransientEntity entity, String[] properties, Object param2) {
            return false;
        }

        Boolean processOpenSaved(AbstractTransientEntity entity, String[] properties, Object param2) {
            Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);
            Map<String, PropertyChange> changesProperties = entity.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(entity);

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
        EntityIterable processOpenNew(AbstractTransientEntity entity, String name, Boolean removed) {
            return EntityIterableBase.EMPTY;
        }

        @NotNull
        EntityIterable processOpenSaved(AbstractTransientEntity entity, String name, Boolean removed) {
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

        @NotNull
        EntityIterable processTemporary(AbstractTransientEntity entity, String name, Boolean removed) {
            return EntityIterableBase.EMPTY;
        }

    };

    public EntityIterable getAddedLinks(final String name) {
        return addedRemovedLinksEventHandler.handle(this, name, false);
    }

    public EntityIterable getRemovedLinks(final String name) {
        return addedRemovedLinksEventHandler.handle(this, name, true);
    }

    private static abstract class StandardEventHandler2<P1, P2, T> extends StandardEventHandler<P1, P2, T> {
        TransientEntityImpl _(AbstractTransientEntity entity) {
            return (TransientEntityImpl) entity;
        }

    void putValueInPropertiesCache(AbstractTransientEntity entity, String propertyName, Comparable value) {
        _(entity).propertiesCache.put(propertyName, value);
    }
    }

}
