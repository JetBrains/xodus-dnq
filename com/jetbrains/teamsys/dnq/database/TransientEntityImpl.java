package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.database.*;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.iterate.EntityIterableBase;
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId;
import jetbrains.exodus.entitystore.metadata.AssociationEndMetaData;
import jetbrains.exodus.entitystore.metadata.EntityMetaData;
import jetbrains.exodus.entitystore.metadata.ModelMetaData;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * @author Vadim.Gurov
 */
class TransientEntityImpl implements TransientEntity {

    protected static final Log log = LogFactory.getLog(TransientEntity.class);

    protected final TransientEntityStore store;
    private Object entity;

    TransientEntityImpl(@NotNull String type, @NotNull TransientEntityStore store) {
        this.store = store;
        getAndCheckThreadStoreSession().createEntity(this, type);
    }

    TransientEntityImpl(@NotNull EntityCreator creator, @NotNull TransientEntityStore store) {
        this.store = store;
        getAndCheckThreadStoreSession().createEntity(this, creator);
    }

    TransientEntityImpl(@NotNull PersistentEntity persistentEntity, @NotNull TransientEntityStore store) {
        this.store = store;
        setPersistentEntity(persistentEntity);
    }

    @NotNull
    public PersistentEntity getPersistentEntity() {
        return entity instanceof PersistentEntity ? (PersistentEntity) entity :
                ((PersistentEntityStoreImpl) store.getPersistentStore()).getEntity((EntityId) entity);
    }

    protected void setPersistentEntity(@NotNull PersistentEntity persistentEntity) {
        if (persistentEntity instanceof ReadOnlyPersistentEntity) {
            entity = persistentEntity;
        } else {
            entity = persistentEntity.getId();
        }
    }

    @NotNull
    public TransientEntityStore getStore() {
        return store;
    }

    @NotNull
    TransientSessionImpl getAndCheckThreadStoreSession() {
        final TransientSessionImpl result = (TransientSessionImpl) store.getThreadSession();
        if (result == null) {
            throw new IllegalStateException("No store session in current thread!");
        }
        return result;
    }

    public boolean isNew() {
        return getAndCheckThreadStoreSession().changesTracker.isNew(this);
    }

    public boolean isSaved() {
        return getAndCheckThreadStoreSession().changesTracker.isSaved(this);
    }

    public boolean isRemoved() {
        return getAndCheckThreadStoreSession().changesTracker.isRemoved(this);
    }

    public boolean isReadonly() {
        return false;
    }

    @NotNull
    public String getType() {
        return getPersistentEntity().getType();
    }

    @NotNull
    public EntityId getId() {
        return getPersistentEntity().getId();
    }

    @NotNull
    public String toIdString() {
        return getPersistentEntity().toIdString();
    }

    @NotNull
    public List<String> getPropertyNames() {
        return getPersistentEntity().getPropertyNames();
    }

    @NotNull
    public List<String> getBlobNames() {
        return getPersistentEntity().getBlobNames();
    }

    @NotNull
    public List<String> getLinkNames() {
        return getPersistentEntity().getLinkNames();
    }

    public int getVersion() {
        return getPersistentEntity().getVersion();
    }

    public boolean isUpToDate() {
        return getPersistentEntity().isUpToDate();
    }

    @NotNull
    public List<Entity> getHistory() {
        if (isNew()) {
            return Collections.emptyList();
        }

        final List<Entity> history = getPersistentEntity().getHistory();
        final List<Entity> result = new ArrayList<Entity>(history.size());
        final TransientStoreSession session = getAndCheckThreadStoreSession();
        for (final Entity _entity : history) {
            result.add(session.newEntity(_entity));
        }
        return result;
    }

    @Nullable
    public Entity getNextVersion() {
        if (isNew()) return null;

        final Entity e = getPersistentEntity().getNextVersion();
        return e == null ? null : getAndCheckThreadStoreSession().newEntity(e);
    }

    @Nullable
    public Entity getPreviousVersion() {
        if (isNew()) return null;

        final Entity e = getPersistentEntity().getPreviousVersion();
        return e == null ? null : getAndCheckThreadStoreSession().newEntity(e);
    }

    public int compareTo(final Entity e) {
        return getPersistentEntity().compareTo(e);
    }

    /**
     * Called by BasePersistentClassImpl by default
     *
     * @return debug presentation
     */
    public String getDebugPresentation() {
        return getPersistentEntity().toString();
    }

    public String toString() {
        return getDebugPresentation();
    }

    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof TransientEntity)) return false;
        return getPersistentEntity().equals(((TransientEntity) obj).getPersistentEntity());
    }

    public int hashCode() {
        return getPersistentEntity().hashCode();
    }

    @Nullable
    public Comparable getProperty(@NotNull final String propertyName) {
        return getPersistentEntity().getProperty(propertyName);
    }

    @Nullable
    @Override
    public ByteIterable getRawProperty(@NotNull String propertyName) {
        return getPersistentEntity().getRawProperty(propertyName);
    }

    @Nullable
    public Comparable getPropertyOldValue(@NotNull final String propertyName) {
        final PersistentStoreTransaction snapshot = getAndCheckThreadStoreSession().getTransientChangesTracker().getSnapshot();
        return getPersistentEntity().getSnapshot(snapshot).getProperty(propertyName);
    }


    public boolean setProperty(@NotNull final String propertyName, @NotNull final Comparable value) {
        return getAndCheckThreadStoreSession().setProperty(this, propertyName, value);
    }

    public boolean deleteProperty(@NotNull final String propertyName) {
        return getAndCheckThreadStoreSession().deleteProperty(this, propertyName);
    }

    @Nullable
    public InputStream getBlob(@NotNull final String blobName) {
        return getPersistentEntity().getBlob(blobName);
    }

    public long getBlobSize(@NotNull final String blobName) {
        return getPersistentEntity().getBlobSize(blobName);
    }

    public void setBlob(@NotNull final String blobName, @NotNull final InputStream blob) {
        getAndCheckThreadStoreSession().setBlob(this, blobName, blob);
    }

    public void setBlob(@NotNull final String blobName, @NotNull final File file) {
        getAndCheckThreadStoreSession().setBlob(this, blobName, file);
    }

    public boolean setBlobString(@NotNull final String blobName, @NotNull final String blobString) {
        return getAndCheckThreadStoreSession().setBlobString(this, blobName, blobString);
    }

    public boolean deleteBlob(@NotNull final String blobName) {
        return getAndCheckThreadStoreSession().deleteBlob(this, blobName);
    }

    @Nullable
    public String getBlobString(@NotNull final String blobName) {
        return getPersistentEntity().getBlobString(blobName);
    }

    public boolean setLink(@NotNull final String linkName, @NotNull final Entity target) {
        checkCardinality(linkName, this);

        return getAndCheckThreadStoreSession().setLink(this, linkName, (TransientEntity) target);
    }

    private void checkCardinality(@NotNull String oneToManyLinkName, @NotNull Entity entity) {
        final AssociationEndMetaData aemd = getAssociationEndMetaData(oneToManyLinkName, entity);
        if (aemd != null && !aemd.getCardinality().isMultiple())
            throw new IllegalArgumentException("Can not call this opperation for non-multiple association");
    }

    @Nullable
    private AssociationEndMetaData getAssociationEndMetaData(@NotNull String linkName, @NotNull Entity entity) {
        final ModelMetaData mmd = store.getModelMetaData();
        if (mmd != null) {
            final EntityMetaData emd = mmd.getEntityMetaData(entity.getType());
            if (emd != null) {
                return emd.getAssociationEndMetaData(linkName);
            }
        }

        return null;
    }

    public boolean addLink(@NotNull final String linkName, @NotNull final Entity target) {
        checkCardinality(linkName, this);

        return getAndCheckThreadStoreSession().addLink(this, linkName, (TransientEntity) target);
    }

    public boolean deleteLink(@NotNull final String linkName, @NotNull final Entity target) {
        return getAndCheckThreadStoreSession().deleteLink(this, linkName, (TransientEntity) target);
    }

    public void deleteLinks(@NotNull final String linkName) {
        getAndCheckThreadStoreSession().deleteLinks(this, linkName);
    }

    @NotNull
    public EntityIterable getLinks(@NotNull final String linkName) {
        return new PersistentEntityIterableWrapper(store, getPersistentEntity().getLinks(linkName));
    }

    @Nullable
    public Entity getLink(@NotNull final String linkName) {
        final PersistentEntity persistentEntity = getPersistentEntity();
        final Entity link = persistentEntity.getLink(linkName);
        if (link == null || (!persistentEntity.isUpToDate() && link.getVersion() < 0)) {
            return null;
        }
        final TransientSessionImpl session = getAndCheckThreadStoreSession();
        final TransientEntity result = session.newEntity(link);
        return session.changesTracker.isRemoved(result) ? null : result;
    }

    @NotNull
    public EntityIterable getLinks(@NotNull final Collection<String> linkNames) {
        return new PersistentEntityIterableWrapper(store, getPersistentEntity().getLinks(linkNames)) {
            @Override
            public EntityIterator iterator() {
                return new PersistentEntityIteratorWithPropIdWrapper((EntityIteratorWithPropId) wrappedIterable.iterator(),
                        store.getThreadSession());
            }
        };
    }

    public long getLinksSize(@NotNull final String linkName) {
        //TODO: slow method
        return getPersistentEntity().getLinks(linkName).size();
    }

    @NotNull
    public List<Pair<String, EntityIterable>> getIncomingLinks() {
        final List<Pair<String, EntityIterable>> result = new ArrayList<Pair<String, EntityIterable>>();
        final TransientStoreSession session = getAndCheckThreadStoreSession();
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
        getAndCheckThreadStoreSession().deleteEntity(this);
        return true;
    }

    public boolean hasChanges() {
        if (isNew()) return true;

        final TransientStoreSession session = getAndCheckThreadStoreSession();
        Set<String> changesProperties = session.getTransientChangesTracker().getChangedProperties(this);
        Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(this);

        return (changesLinks != null && !changesLinks.isEmpty()) || (changesProperties != null && !changesProperties.isEmpty());
    }

    public boolean hasChanges(final String property) {
        final TransientStoreSession session = getAndCheckThreadStoreSession();
        Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(this);
        Set<String> changesProperties = session.getTransientChangesTracker().getChangedProperties(this);

        return (changesLinks != null && changesLinks.containsKey(property)) || (changesProperties != null && changesProperties.contains(property));
    }

    public boolean hasChangesExcepting(String[] properties) {
        final TransientStoreSession session = getAndCheckThreadStoreSession();
        Map<String, LinkChange> changesLinks = session.getTransientChangesTracker().getChangedLinksDetailed(this);
        Set<String> changesProperties = session.getTransientChangesTracker().getChangedProperties(this);

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

        Map<String, LinkChange> changesLinks = getAndCheckThreadStoreSession().getTransientChangesTracker().getChangedLinksDetailed(this);

        if (changesLinks != null) {
            final LinkChange linkChange = changesLinks.get(name);
            if (linkChange != null) {
                EntityIterable result = removed ? getRemovedWrapper(linkChange) : getAddedWrapper(linkChange);
                if (removed) {
                    TransientEntityIterable deleted = getDeletedWrapper(linkChange);
                    if (result == null || result.isEmpty()) {
                        result = deleted;
                    } else if (deleted != null) {
                        result = result.concat(deleted);
                    }
                }
                if (result != null) {
                    return result;
                }
            }
        }
        return EntityIterableBase.EMPTY;
    }

    private TransientEntityIterable getAddedWrapper(final LinkChange change) {
        Set<TransientEntity> addedEntities = change.getAddedEntities();
        if (addedEntities == null) {
            return null;
        }
        return new TransientEntityIterable(addedEntities) {
            @Override
            public long size() {
                return change.getAddedEntitiesSize();
            }

            @Override
            public long count() {
                return change.getAddedEntitiesSize();
            }
        };
    }

    ;

    private TransientEntityIterable getRemovedWrapper(final LinkChange change) {
        Set<TransientEntity> removedEntities = change.getRemovedEntities();
        if (removedEntities == null) {
            return null;
        }
        return new TransientEntityIterable(removedEntities) {
            @Override
            public long size() {
                return change.getRemovedEntitiesSize();
            }

            @Override
            public long count() {
                return change.getRemovedEntitiesSize();
            }
        };
    }

    ;

    private TransientEntityIterable getDeletedWrapper(final LinkChange change) {
        Set<TransientEntity> deletedEntities = change.getDeletedEntities();
        if (deletedEntities == null) {
            return null;
        }
        return new TransientEntityIterable(deletedEntities) {
            @Override
            public long size() {
                return change.getDeletedEntitiesSize();
            }

            @Override
            public long count() {
                return change.getDeletedEntitiesSize();
            }
        };
    }

    ;

    public EntityIterable getAddedLinks(final String name) {
        return getAddedRemovedLinks(name, false);
    }

    public EntityIterable getRemovedLinks(final String name) {
        return getAddedRemovedLinks(name, true);
    }

    private EntityIterable getAddedRemovedLinks(final Set<String> linkNames, boolean removed) {
        if (isNew()) return UniversalEmptyEntityIterable.INSTANCE;

        final Map<String, LinkChange> changedLinksDetailed = getAndCheckThreadStoreSession().getTransientChangesTracker().getChangedLinksDetailed(this);
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

    @Override
    public void setToOne(@NotNull String linkName, @Nullable Entity target) {
        getAndCheckThreadStoreSession().setToOne(this, linkName, (TransientEntity) target);
    }

    public void setManyToOne(@NotNull String manyToOneLinkName, @NotNull String oneToManyLinkName, @Nullable Entity one) {
        if (one != null) {
            checkCardinality(oneToManyLinkName, one);
        }
        getAndCheckThreadStoreSession().setManyToOne(this, manyToOneLinkName, oneToManyLinkName, (TransientEntity) one);
    }

    @Override
    public void clearOneToMany(@NotNull String manyToOneLinkName, @NotNull String oneToManyLinkName) {
        checkCardinality(oneToManyLinkName, this);

        getAndCheckThreadStoreSession().clearOneToMany(this, manyToOneLinkName, oneToManyLinkName);
    }

    @Override
    public void createManyToMany(@NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @NotNull Entity e2) {
        checkCardinality(e1Toe2LinkName, this);
        checkCardinality(e2Toe1LinkName, e2);

        getAndCheckThreadStoreSession().createManyToMany(this, e1Toe2LinkName, e2Toe1LinkName, (TransientEntity) e2);
    }

    @Override
    public void clearManyToMany(@NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName) {
        checkCardinality(e1Toe2LinkName, this);

        getAndCheckThreadStoreSession().clearManyToMany(this, e1Toe2LinkName, e2Toe1LinkName);
    }

    @Override
    public void setOneToOne(@NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @Nullable Entity e2) {
        getAndCheckThreadStoreSession().setOneToOne(this, e1Toe2LinkName, e2Toe1LinkName, (TransientEntity) e2);
    }

    @Override
    public void removeOneToMany(@NotNull String manyToOneLinkName, @NotNull String oneToManyLinkName, @NotNull Entity many) {
        getAndCheckThreadStoreSession().removeOneToMany(this, manyToOneLinkName, oneToManyLinkName, (TransientEntity) many);
    }

    @Override
    public void removeFromParent(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName) {
        getAndCheckThreadStoreSession().removeFromParent(this, parentToChildLinkName, childToParentLinkName);
    }

    @Override
    public void removeChild(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName) {
        getAndCheckThreadStoreSession().removeChild(this, parentToChildLinkName, childToParentLinkName);
    }

    @Override
    public void setChild(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
        getAndCheckThreadStoreSession().setChild(this, parentToChildLinkName, childToParentLinkName, (TransientEntity) child);
    }

    @Override
    public void clearChildren(@NotNull String parentToChildLinkName) {
        getAndCheckThreadStoreSession().clearChildren(this, parentToChildLinkName);
    }

    @Override
    public void addChild(@NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
        getAndCheckThreadStoreSession().addChild(this, parentToChildLinkName, childToParentLinkName, (TransientEntity) child);
    }

    @Override
    public Entity getParent() {
        return getAndCheckThreadStoreSession().getParent(this);
    }
}
