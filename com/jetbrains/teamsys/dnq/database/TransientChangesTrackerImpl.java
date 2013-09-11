package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.core.dataStructures.decorators.LinkedHashSetDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Vadim.Gurov
 */
public final class TransientChangesTrackerImpl implements TransientChangesTracker {

    private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

    private Set<TransientEntity> changedEntities = new LinkedHashSetDecorator<TransientEntity>();
    private Set<TransientEntity> addedEntities = new HashSetDecorator<TransientEntity>();
    private Set<TransientEntity> removedEntities = new HashSetDecorator<TransientEntity>();
    private Map<TransientEntity, List<LinkChange>> removedFrom = new HashMapDecorator<TransientEntity, List<LinkChange>>();
    private Map<TransientEntity, Map<String, LinkChange>> entityToChangedLinksDetailed = new HashMapDecorator<TransientEntity, Map<String, LinkChange>>();
    private Map<TransientEntity, Set<String>> entityToChangedProperties = new HashMapDecorator<TransientEntity, Set<String>>();
    private PersistentStoreTransaction snapshot;

    TransientChangesTrackerImpl(PersistentStoreTransaction snapshot) {
        this.snapshot = snapshot;
    }

    @NotNull
    public Set<TransientEntity> getChangedEntities() {
        return changedEntities;
    }

    public PersistentStoreTransaction getSnapshot() {
        return snapshot;
    }

    @Override
    public TransientEntityImpl getSnapshotEntity(TransientEntity e) {
        final ReadOnlyPersistentEntity ro = e.getPersistentEntity().getSnapshot(snapshot);
        return new ReadonlyTransientEntityImpl(getChangeDescription(e), ro, (TransientEntityStore) e.getStore());
    }

    @NotNull
    public Set<TransientEntityChange> getChangesDescription() {
        Set<TransientEntityChange> changesDescription = new HashSetDecorator<TransientEntityChange>();

        for (TransientEntity e : getChangedEntities()) {
            // do not notify about RemovedNew entities - such entities was created and removed during same transaction
            if (wasCreatedAndRemovedInSameTransaction(e)) continue;

            changesDescription.add(new TransientEntityChange(this, e, getChangedProperties(e), getChangedLinksDetailed(e), getEntityChangeType(e)));
        }

        return changesDescription;
    }

    private EntityChangeType getEntityChangeType(TransientEntity e) {
        if (addedEntities.contains(e)) return EntityChangeType.ADD;
        if (removedEntities.contains(e)) return EntityChangeType.REMOVE;
        return EntityChangeType.UPDATE;
    }

    public TransientEntityChange getChangeDescription(TransientEntity e) {
        return new TransientEntityChange(this, e, getChangedProperties(e), getChangedLinksDetailed(e), getEntityChangeType(e));
    }

    @Nullable
    public Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e) {
        return entityToChangedLinksDetailed.get(e);
    }

    @Nullable
    public Set<String> getChangedProperties(@NotNull TransientEntity e) {
        return entityToChangedProperties.get(e);
    }

    Set<TransientEntity> getRemovedEntities() {
        return removedEntities;
    }

    boolean isNew(@NotNull TransientEntity e) {
        return addedEntities.contains(e);
    }

    boolean isRemoved(@NotNull TransientEntity e) {
        return removedEntities.contains(e);
    }

    boolean isSaved(@NotNull TransientEntity e) {
        return !addedEntities.contains(e) && !removedEntities.contains(e);
    }

    boolean wasCreatedAndRemovedInSameTransaction(@NotNull TransientEntity e) {
        return addedEntities.contains(e) && removedEntities.contains(e);
    }

    void linksRemoved(@NotNull TransientEntity source, @NotNull String linkName, Iterable<Entity> links) {
        entityChanged(source);

        final Pair<Map<String, LinkChange>, LinkChange> lc = getLinkChange(source, linkName);
        for (Entity entity : links) {
            addRemoved(lc.getSecond(), (TransientEntity) entity);
        }
    }

    private Pair<Map<String, LinkChange>, LinkChange> getLinkChange(TransientEntity source, String linkName) {
        Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(source);
        if (linksDetailed == null) {
            linksDetailed = new HashMap<String, LinkChange>();
            entityToChangedLinksDetailed.put(source, linksDetailed);
        }

        LinkChange lc = linksDetailed.get(linkName);
        if (lc == null) {
            lc = new LinkChange(linkName);
            linksDetailed.put(linkName, lc);
        }

        return new Pair<Map<String, LinkChange>, LinkChange>(linksDetailed, lc);
    }

    void linkChanged(@NotNull TransientEntity source, @NotNull String linkName, @NotNull TransientEntity target, @Nullable TransientEntity oldTarget, boolean add) {
        entityChanged(source);

        final Pair<Map<String, LinkChange>, LinkChange> pair = getLinkChange(source, linkName);
        final LinkChange lc = pair.getSecond();
        if (add) {
            if (oldTarget != null) {
                addRemoved(lc, oldTarget);
            }
            lc.addAdded(target);
        } else {
            addRemoved(lc, target);
        }
        if (lc.getAddedEntitiesSize() == 0 && lc.getRemovedEntitiesSize() == 0) {
            pair.getFirst().remove(linkName);
            if (pair.getFirst().size() == 0) {
                entityToChangedLinksDetailed.remove(source);
            }
        }
    }

    private void addRemoved(@NotNull final LinkChange change, @NotNull final TransientEntity entity) {
        change.addRemoved(entity);
        List<LinkChange> changes = removedFrom.get(entity);
        if (changes == null) {
            changes = new ArrayList<LinkChange>();
            removedFrom.put(entity, changes);
        }
        changes.add(change);
    }

    void propertyChanged(TransientEntity e, String propertyName) {
        entityChanged(e);

        Set<String> properties = entityToChangedProperties.get(e);
        if (properties == null) {
            properties = new HashSet<String>();
            entityToChangedProperties.put(e, properties);
        }

        properties.add(propertyName);
    }

    void removePropertyChanged(TransientEntity e, String propertyName) {
        Set<String> properties = entityToChangedProperties.get(e);
        if (properties != null) {
            properties.remove(propertyName);
            if (properties.isEmpty()) {
                entityToChangedProperties.remove(e);
            }
        }
    }

    void entityChanged(TransientEntity e) {
        changedEntities.add(e);
    }

    void entityAdded(TransientEntity e) {
        entityChanged(e);
        addedEntities.add(e);
    }

    void entityRemoved(TransientEntity e) {
        entityChanged(e);
        removedEntities.add(e);
        List<LinkChange> changes = removedFrom.get(e);
        if (changes != null) {
            for (LinkChange change : changes) {
                change.addDeleted(e);
            }
        }
    }

    @Override
    public void dispose() {
        if (snapshot != null) {
            snapshot.abort();
            snapshot = null;
        }
    }
}
