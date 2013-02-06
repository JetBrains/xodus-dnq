package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.EntityIterableBase;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
import jetbrains.exodus.database.persistence.Transaction;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

/**
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

    protected String type;
    protected State state;
    protected TransientEntityStore store;
    @NotNull
    protected PersistentEntity persistentEntity;

    TransientEntityImpl(@NotNull String type, @NotNull TransientStoreSession session) {
        this.store = session.getStore();
        this.type = type;
        setState(State.New);
        session.getTransientChangesTracker().entityAdded(this);
    }

    TransientEntityImpl(@NotNull PersistentEntity persistentEntity, @NotNull TransientStoreSession session) {
        this.store = session.getStore();
        setState(State.Saved);
        setPersistentEntity(persistentEntity);
    }

    @NotNull
    public PersistentEntity getPersistentEntity() {
        return persistentEntity;
    }

    protected void setPersistentEntity(@NotNull PersistentEntity persistentEntity) {
        this.persistentEntity = persistentEntity;
        this.type = persistentEntity.getType();
    }

    @NotNull
    public TransientEntityStore getStore() {
        return store;
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

    @NotNull
    public EntityId getId() {
        return persistentEntity.getId();
    }

    @NotNull
    public String toIdString() {
        return persistentEntity.toIdString();
    }
    @NotNull
    public List<String> getPropertyNames() {
        return persistentEntity.getPropertyNames();
    }

    @NotNull
    public List<String> getBlobNames() {
        return persistentEntity.getBlobNames();
    }

    @NotNull
    public List<String> getLinkNames() {
        return persistentEntity.getLinkNames();
    }

    public int getVersion() {
        return persistentEntity.getVersion();
    }

    public boolean isUpToDate() {
        return persistentEntity.isUpToDate();
    }

    @NotNull
    public List<Entity> getHistory() {
        if (isNew()) return Collections.EMPTY_LIST;

        final List<Entity> history = persistentEntity.getHistory();
        final List<Entity> result = new ArrayList<Entity>(history.size());
        final TransientStoreSession session = getThreadStoreSession();
        for (final Entity _entity : history) {
            result.add(session.newEntity(_entity));
        }
        return result;
    }

    @Nullable
    public Entity getNextVersion() {
        if (isNew()) return null;

        final Entity e = persistentEntity.getNextVersion();
        return e == null ? null : getThreadStoreSession().newEntity(e);
    }

    @Nullable
    public Entity getPreviousVersion() {
        if (isNew()) return null;

        final Entity e = persistentEntity.getPreviousVersion();
        return e == null ? null : getThreadStoreSession().newEntity(e);
    }

    public int compareTo(final Entity e) {
        return persistentEntity.compareTo(e);
    }

    /**
     * Called by BasePersistentClassImpl by default
     *
     * @return debug presentation
     */
    public String getDebugPresentation() {
        final StringBuilder sb = new StringBuilder();
        return sb.append(persistentEntity).append(" (").append(state).append(")").toString();
    }

    public String toString() {
        return getDebugPresentation();
    }

    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof TransientEntity)) return false;
        return persistentEntity.equals(((TransientEntity)obj).getPersistentEntity());
    }

    public int hashCode() {
        return persistentEntity.hashCode();
    }

    @Nullable
    public Comparable getProperty(@NotNull final String propertyName) {
        return persistentEntity.getProperty(propertyName);
    }

    public boolean setProperty(@NotNull final String propertyName, @NotNull final Comparable value) {
        getThreadStoreSession().getTransientChangesTracker().propertyChanged(
                this, propertyName, this.getPersistentEntity().getProperty(propertyName), value);
        return true;
    }

    public boolean deleteProperty(@NotNull final String propertyName) {
        getThreadStoreSession().getTransientChangesTracker().propertyDeleted(
                this, propertyName, this.getPersistentEntity().getProperty(propertyName));
        return true;
    }

    @Nullable
    public InputStream getBlob(@NotNull final String blobName) {
        return persistentEntity.getBlob(blobName);
    }

    public void setBlob(@NotNull final String blobName, @NotNull final InputStream blob) {
        getThreadStoreSession().getTransientChangesTracker().blobChanged(this, blobName, blob);
    }

    public void setBlob(@NotNull final String blobName, @NotNull final File file) {
        getThreadStoreSession().getTransientChangesTracker().blobChanged(this, blobName, file);
    }

    public boolean setBlobString(@NotNull final String blobName, @NotNull final String blobString) {
        getThreadStoreSession().getTransientChangesTracker().blobChanged(this, blobName, blobString);
        return true;
    }

    public boolean deleteBlob(@NotNull final String blobName) {
        getThreadStoreSession().getTransientChangesTracker().blobDeleted(this, blobName);
        return true;
    }

    @Nullable
    public String getBlobString(@NotNull final String blobName) {
        return persistentEntity.getBlobString(blobName);
    }

    public void deleteBlobString(@NotNull final String blobName) {
        getThreadStoreSession().getTransientChangesTracker().blobDeleted(this, blobName);
    }

    public boolean addLink(@NotNull final String linkName, @NotNull final Entity target) {
        getThreadStoreSession().getTransientChangesTracker().linkAdded(this, linkName, (TransientEntity) target);
        return true;
    }

    public boolean setLink(@NotNull final String linkName, @NotNull final Entity target) {
        getThreadStoreSession().getTransientChangesTracker().linkSet(this, linkName, (TransientEntity) target);
        return true;
    }

    public boolean deleteLink(@NotNull final String linkName, @NotNull final Entity target) {
        getThreadStoreSession().getTransientChangesTracker().linkDeleted(this, linkName, (TransientEntity) target);
        return true;
    }

    public void deleteLinks(@NotNull final String linkName) {
        getThreadStoreSession().getTransientChangesTracker().linksDeleted(this, linkName);
    }

    @NotNull
    public Iterable<Entity> getLinks(@NotNull final String linkName) {
        return new PersistentEntityIterableWrapper(persistentEntity.getLinks(linkName));
    }

    @Nullable
    public Entity getLink(@NotNull final String linkName) {
        final Entity link = persistentEntity.getLink(linkName);
        return link == null ? null : getThreadStoreSession().newEntity(link);
    }

    @NotNull
    public EntityIterable getLinks(@NotNull final Collection<String> linkNames) {
        return new PersistentEntityIterableWrapper(persistentEntity.getLinks(linkNames)) {
            @Override
            public EntityIterator iterator() {
                return new PersistentEntityIteratorWithPropIdWrapper((EntityIteratorWithPropId) wrappedIterable.iterator(),
                        (TransientStoreSession) ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession());
            }
        };
    }

    public long getLinksSize(@NotNull final String linkName) {
        //TODO: slow method
        return persistentEntity.getLinks(linkName).size();
    }

    @NotNull
    public List<Pair<String, EntityIterable>> getIncomingLinks() {
        final List<Pair<String, EntityIterable>> result = new ArrayList<Pair<String, EntityIterable>>();
        final TransientStoreSession session = getThreadStoreSession();
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

    public boolean delete() {
        getThreadStoreSession().getTransientChangesTracker().entityDeleted(this);
        switch (state) {
            case New:
                state = State.RemovedNew;
            case Saved:
                state = State.RemovedSaved;
            case SavedNew:
                state = State.RemovedSavedNew;
        }
        return true;
    }

    public void newVersion() {
        persistentEntity.newVersion();
    }

    public boolean hasChanges() {
        if (isNew()) return true;

        final TransientStoreSession session = getThreadStoreSession();
        Map<String, PropertyChange> changesProperties = session.getTransientChangesTracker().getChangedPropertiesDetailed(this);
        Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(this);

        return (changesLinks != null && !changesLinks.isEmpty()) || (changesProperties != null && !changesProperties.isEmpty());
    }

    public boolean hasChanges(final String property) {
        final TransientStoreSession session = getThreadStoreSession();
        Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(this);
        Map<String, PropertyChange> changesProperties = session.getTransientChangesTracker().getChangedPropertiesDetailed(this);

        return (changesLinks != null && changesLinks.containsKey(property)) || (changesProperties != null && changesProperties.containsKey(property));
    }

    public boolean hasChangesExcepting(String[] properties) {
        final TransientStoreSession session = getThreadStoreSession();
        Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(this);
        Map<String, PropertyChange> changesProperties = session.getTransientChangesTracker().getChangedPropertiesDetailed(this);

        int found = 0;
        int changed;
        if (changesLinks == null && changesProperties == null) {
            return false;
        } else {
            for (String property : properties) {
                // all properties have to be changed
                if (this.hasChanges(property)) found++;
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

    private EntityIterable getAddedRemovedLinks(final String name, boolean removed) {
        if (isNew()) return EntityIterableBase.EMPTY;

        Map<String, LinkChange> changesLinks = getThreadStoreSession().getTransientChangesTracker().getChangedLinksDetailed(this);

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

    public EntityIterable getAddedLinks(final String name) {
        return getAddedRemovedLinks(name, false);
    }

    public EntityIterable getRemovedLinks(final String name) {
        return getAddedRemovedLinks(name, true);
    }

    private EntityIterable getAddedRemovedLinks(final Set<String> linkNames, boolean removed) {
        if (isNew()) return UniversalEmptyEntityIterable.INSTANCE;

        final Map<String, LinkChange> changedLinksDetailed = getThreadStoreSession().getTransientChangesTracker().getChangedLinksDetailed(this);
        return changedLinksDetailed == null ? UniversalEmptyEntityIterable.INSTANCE : AddedOrRemovedLinksFromSetTransientEntityIterable.get(
                changedLinksDetailed,
                linkNames, removed
        );
    }

    public EntityIterable getAddedLinks(final Set<String> linkNames) {
        return getAddedRemovedLinks(linkNames, false);
    }

    public EntityIterable getRemovedLinks(final Set<String> linkNames) {
        return getAddedRemovedLinks(linkNames, true);
    }

    @NotNull
    @Override
    public Transaction getTxn() {
        throw new UnsupportedOperationException();
    }
}
