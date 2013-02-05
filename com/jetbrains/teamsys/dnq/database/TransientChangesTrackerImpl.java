package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.core.dataStructures.decorators.LinkedHashSetDecorator;
import jetbrains.exodus.core.dataStructures.decorators.QueueDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * Saves in queue all changes made during transient session
 * TODO: implement more intelligent changes tracking for links and properties
 *
 * @author Vadim.Gurov
 */
public final class TransientChangesTrackerImpl implements TransientChangesTracker {

    private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

    private TransientStoreSession session;

    private Queue<Runnable> changes = new QueueDecorator<Runnable>();

    private Set<TransientEntity> changedEntities = new LinkedHashSetDecorator<TransientEntity>();

    private Map<TransientEntity, Map<String, LinkChange>> entityToChangedLinksDetailed = new HashMapDecorator<TransientEntity, Map<String, LinkChange>>();
    private Map<TransientEntity, Map<String, PropertyChange>> entityToChangedPropertiesDetailed = new HashMapDecorator<TransientEntity, Map<String, PropertyChange>>();

    private int changesCount = 0;
    private Set<TransientEntityChange> changesDescription;

    public TransientChangesTrackerImpl(TransientStoreSession session) {
        this.session = session;
    }

    public int getChangesCount() {
        return changesCount;
    }

    private void c() {
        changesDescription = null;
        changesCount++;
    }

    @NotNull
    public Queue<Runnable> getChanges() {
        return changes;
    }

    public void clear() {
        changes.clear();
        changedEntities.clear();
        entityToChangedLinksDetailed.clear();
        entityToChangedPropertiesDetailed.clear();
    }

    @NotNull
    public Set<TransientEntity> getChangedEntities() {
        return changedEntities;
    }

    public Set<TransientEntityChange> getChangesDescription() {
        //TODO: optimization hint: do not rebuild set on every request - incrementaly build it
        if (changesDescription == null) {
            changesDescription = new HashSetDecorator<TransientEntityChange>();

            for (TransientEntity e : getChangedEntities()) {
                // do not notify about RemovedNew entities
                if (e.isRemoved() && !e.wasSaved()) continue;

                changesDescription.add(new TransientEntityChange(e, getChangedPropertiesDetailed(e),
                        getChangedLinksDetailed(e), decodeState(e)));
            }
        }

        return changesDescription;
    }

    private EntityChangeType decodeState(TransientEntity e) {
        switch (((TransientEntityImpl) e).getState()) {
            case New:
                return EntityChangeType.ADD;

            case RemovedNew:
            case RemovedSaved:
            case RemovedSavedNew:
                return EntityChangeType.REMOVE;

            case SavedNew:
            case Saved:
                return EntityChangeType.UPDATE;

            default:
                throw new IllegalStateException("Can't decode change for state [" + ((TransientEntityImpl) e).getState() + "]");
        }
    }


    public TransientEntityChange getChangeDescription(TransientEntity e) {
        if (!e.isRemoved()) {
            return new TransientEntityChange(e, getChangedPropertiesDetailed(e),
                    getChangedLinksDetailed(e), e.isNew() ? EntityChangeType.ADD : EntityChangeType.UPDATE);
        } else {
            throw new EntityRemovedException(e);
        }
    }

    @Nullable
    public Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e) {
        return entityToChangedLinksDetailed.get(e);
    }

    @Nullable
    public Map<String, PropertyChange> getChangedPropertiesDetailed(@NotNull TransientEntity e) {
        return entityToChangedPropertiesDetailed.get(e);
    }

    private void registerLinkChange(@NotNull TransientEntity source, @NotNull String linkName, @NotNull TransientEntity target, @Nullable TransientEntity oldTarget, boolean add) {
        c();

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

        if (add) {
            if (oldTarget != null) {
                lc.addRemoved(oldTarget);
            }
            lc.addAdded(target);
        } else {
            lc.addRemoved(target);
        }

        if (lc.getAddedEntitiesSize() == 0 && lc.getRemovedEntitiesSize() == 0) {
            linksDetailed.remove(linkName);
            if (linksDetailed.size() == 0) {
                entityToChangedLinksDetailed.remove(source);
            }
        }
    }

    private void propertyChangedDetailed(TransientEntity e, String propertyName, Comparable origValue, PropertyChangeType changeType) {
        c();

        Map<String, PropertyChange> propertiesDetailed = entityToChangedPropertiesDetailed.get(e);
        if (propertiesDetailed == null) {
            propertiesDetailed = new HashMap<String, PropertyChange>();
            entityToChangedPropertiesDetailed.put(e, propertiesDetailed);
        }
        // get previous change if any
        PropertyChange propertyChange = propertiesDetailed.get(propertyName);
        if (propertyChange != null) {
            // use the very first origValue
            origValue = propertyChange.getOldValue();
        }
        propertiesDetailed.put(propertyName, new PropertyChange(propertyName, origValue, changeType));
    }

    public void entityAdded(@NotNull final TransientEntity e) {
        ((TransientEntityImpl) e).setPersistentEntity((PersistentEntity) session.getPersistentTransaction().newEntity(e.getType()));

        entityChanged(e);
        addChange(new Runnable() {
            public void run() {
                session.getPersistentTransaction().saveEntity(e);
            }
        });
    }

    public void linkAdded(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        entityChanged(source);
        //persistentEntityChanged(target);

        final Runnable change = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Add link: " + source + "-[" + linkName + "]-> " + target);
                }
                source.getPersistentEntity().addLink(linkName, target.getPersistentEntity());
            }
        };

        change.run();
        addChange(change);
        registerLinkChange(source, linkName, target, null, true);
    }

    public void linkSet(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        entityChanged(source);
        //persistentEntityChanged(target);

        final Runnable change = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Set link: " + source + "-[" + linkName + "]-> " + target);
                }
                source.getPersistentEntity().setLink(linkName, target.getPersistentEntity());
            }
        };

        registerLinkChange(source, linkName, target, (TransientEntity) source.getLink(linkName),  true);
        change.run();
        addChange(change);
    }

    public void linkDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        entityChanged(source);

        final Runnable change = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Delete link: " + source + "-[" + linkName + "]-> " + target);
                }
                source.getPersistentEntity().deleteLink(linkName, target.getPersistentEntity());
            }
        };

        registerLinkChange(source, linkName, target, null, false);
        change.run();
        addChange(change);
    }

    public void linksDeleted(@NotNull final TransientEntity source, @NotNull final String linkName) {
        entityChanged(source);

        final Runnable change = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Delete links: " + source + "-[" + linkName + "]-> *");
                }
                ((TransientEntityImpl) source).getPersistentEntity().deleteLinks(linkName);
            }
        };

        change.run();
        addChange(change);
    }

    private void entityChanged(TransientEntity source) {
        c();
        changedEntities.add(source);
    }

    public void entityDeleted(@NotNull final TransientEntity e) {
        entityChanged(e);

        final Runnable deleteEntity = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Delete entity: " + e);
                }
                // delete entity
                ((TransientSessionImpl)session).deleteIndexes(e);
                e.getPersistentEntity().delete();
            }
        };

        deleteEntity.run();
        addChange(deleteEntity);
    }

    public void propertyChanged(@NotNull final TransientEntity e,
                                @NotNull final String propertyName,
                                @Nullable final Comparable propertyOldValue,
                                @NotNull final Comparable propertyNewValue) {
        entityChanged(e);

        Runnable changeProperty = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Set property: " + e + "." + propertyName + "=" + propertyNewValue);
                }
                e.getPersistentEntity().setProperty(propertyName, propertyNewValue);
            }
        };

        changeProperty.run();
        addChange(changeProperty);
        propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.UPDATE);
    }

    public void propertyDeleted(@NotNull final TransientEntity e, @NotNull final String propertyName, @Nullable final Comparable propertyOldValue) {
        entityChanged(e);

        Runnable deleteProperty = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Delete property: " + e + "." + propertyName);
                }
                e.getPersistentEntity().deleteProperty(propertyName);
            }
        };

        deleteProperty.run();
        addChange(deleteProperty);
        propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.REMOVE);
    }

    public void historyCleared(@NotNull final String entityType) {
        c();

        addChange(new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Clear history of entities of type [" + entityType + "]");
                }
                session.getPersistentTransaction().clearHistory(entityType);
            }
        });
    }

    public void blobChanged(@NotNull final TransientEntity e,
                            @NotNull final String blobName,
                            @NotNull final InputStream file) {
        entityChanged(e);
        Runnable blobChanged = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Set blob property: " + e + "." + blobName + "=" + file);
                }
                e.getPersistentEntity().setBlob(blobName, file);
            }
        };

        blobChanged.run();
        propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE);
        addChange(blobChanged);
    }

    public void blobChanged(@NotNull final TransientEntity e,
                            @NotNull final String blobName,
                            @NotNull final File file) {
        entityChanged(e);
        Runnable blobChanged = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Set blob property: " + e + "." + blobName + "=" + file);
                }
                e.getPersistentEntity().setBlob(blobName, file);
            }
        };

        blobChanged.run();
        propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE);
        addChange(blobChanged);
    }

    public void blobChanged(@NotNull final TransientEntity e,
                            @NotNull final String blobName,
                            @NotNull final String newValue) {
        entityChanged(e);
        Runnable blobChanged = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Set blob property: " + e + "." + blobName + "=" + newValue);
                }
                e.getPersistentEntity().setBlobString(blobName, newValue);
            }
        };

        blobChanged.run();
        final String oldPropertyValue = e.getPersistentEntity().getBlobString(blobName);
        propertyChangedDetailed(e, blobName, oldPropertyValue, PropertyChangeType.UPDATE);
        addChange(blobChanged);
    }

    public void blobDeleted(@NotNull final TransientEntity e, @NotNull final String blobName) {
        entityChanged(e);

        Runnable deleteBlob = new Runnable() {
            public void run() {
                if (log.isDebugEnabled()) {
                    log.debug("Delete blob property: " + e + "." + blobName);
                }
                e.getPersistentEntity().deleteBlob(blobName);
            }
        };

        deleteBlob.run();
        propertyChangedDetailed(e, blobName, null, PropertyChangeType.REMOVE);
        addChange(deleteBlob);
    }

    void addChange(@NotNull final Runnable change) {
        changes.offer(change);
    }

    public void dispose() {
        session = null;
    }

}
