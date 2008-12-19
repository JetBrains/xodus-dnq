package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.HashMapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.HashSetDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.QueueDecorator;
import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.CantRemoveEntityException;
import com.jetbrains.teamsys.database.exceptions.ConstraintsValidationException;
import com.jetbrains.teamsys.database.exceptions.EntityRemovedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Saves in queue all changes made during transient session
 * TODO: implement more intelligent changes tracking for links and properties
 *
 * @author Vadim.Gurov
 */
final class TransientChangesTrackerImpl implements TransientChangesTracker {

  private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

  private TransientStoreSession session;
  private Queue<Runnable> changes = new QueueDecorator<Runnable>();
  private LinkedList<Runnable> deleted = null;
  private Queue<Runnable> rollbackChanges = new QueueDecorator<Runnable>();
  private Set<TransientEntity> changedPersistentEntities = new HashSetDecorator<TransientEntity>();
  private Set<TransientEntity> changedEntities = new HashSetDecorator<TransientEntity>();

  private Map<TransientEntity, Map<String, LinkChange>> entityToChangedLinksDetailed = new HashMapDecorator<TransientEntity, Map<String, LinkChange>>();
  private Map<TransientEntity, Map<String, PropertyChange>> entityToChangedPropertiesDetailed = new HashMapDecorator<TransientEntity, Map<String, PropertyChange>>();

  private boolean wasChanges = false;

  public TransientChangesTrackerImpl(TransientStoreSession session) {
    this.session = session;
  }

  public boolean areThereChanges() {
    return !(changes.isEmpty() && (deleted == null || deleted.isEmpty()));
  }

  @NotNull
  public Queue<Runnable> getChanges() {
    Queue<Runnable> res = new LinkedList<Runnable>(changes);
    if (deleted != null) {
      res.addAll(getDeleted());
    }
    return res;
  }

  @NotNull
  public Queue<Runnable> getRollbackChanges() {
    return rollbackChanges;
  }

  public void markState() {
    wasChanges = false;
  }

  public boolean wereChangesAfterMarkState() {
    return wasChanges;
  }

  public void clear() {
    changes.clear();
    if (deleted != null) {
      getDeleted().clear();
    }
    rollbackChanges.clear();
    changedPersistentEntities.clear();
    changedEntities.clear();
    entityToChangedLinksDetailed.clear();
    entityToChangedPropertiesDetailed.clear();
  }

  @NotNull
  public Set<TransientEntity> getChangedPersistentEntities() {
    return changedPersistentEntities;
  }

  @NotNull
  public Set<TransientEntity> getChangedEntities() {
    return changedEntities;
  }

  public Set<TransientEntityChange> getChangesDescription() {
    //TODO: optimization hint: do not rebuild set on every request - incrementaly build it 
    Set<TransientEntityChange> res = new HashSetDecorator<TransientEntityChange>();

    for (TransientEntity e : getChangedEntities()) {
      if (!e.isTemporary()) {
        res.add(new TransientEntityChange(e, getChangedPropertiesDetailed(e),
          getChangedLinksDetailed(e), decodeState(e)));
      }
    }

    return res;
  }

  private EntityChangeType decodeState(TransientEntity e) {
    switch (((AbstractTransientEntity)e).getState()) {
      case New:
      case SavedNew:
        return EntityChangeType.ADD;

      case RemovedSaved:
      case RemovedNew:
        return EntityChangeType.REMOVE;

      case Saved:
        return EntityChangeType.UPDATE;

      default:
        throw new IllegalStateException("Can't decode change for state [" + ((AbstractTransientEntity)e).getState() + "]");
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

  private void linkChangedDetailed(TransientEntity e, String linkName, LinkChangeType changeType, Set<TransientEntity> addedEntities, Set<TransientEntity> removedEntities) {
    Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(e);
    if (linksDetailed == null) {
      linksDetailed = new HashMap<String, LinkChange>();
      entityToChangedLinksDetailed.put(e, linksDetailed);
      linksDetailed.put(linkName, new LinkChange(linkName, changeType, addedEntities, removedEntities));
    } else {
      LinkChange lc = linksDetailed.get(linkName);
      if (lc != null) {
        lc.setChangeType(lc.getChangeType().getMerged(changeType));
        if (addedEntities != null) {
            lc.setAddedEntities(addedEntities);
        }
        if (removedEntities != null) {
            lc.setRemovedEntities(removedEntities);
        }
      } else {
        linksDetailed.put(linkName, new LinkChange(linkName, changeType, addedEntities, removedEntities));
      }
    }
  }

  private void propertyChangedDetailed(TransientEntity e, String propertyName, Comparable origValue, PropertyChangeType changeType) {
    Map<String, PropertyChange> propertiesDetailed = entityToChangedPropertiesDetailed.get(e);
    if (propertiesDetailed == null) {
      propertiesDetailed = new HashMap<String, PropertyChange>();
      entityToChangedPropertiesDetailed.put(e, propertiesDetailed);
    }
    propertiesDetailed.put(propertyName, new PropertyChange(propertyName, origValue, changeType));
  }

  public void entityAdded(@NotNull final TransientEntity e) {
    assert e.isNew();
    entityChanged(e);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          assert e.isNew();
          log.debug("Add new entity: " + e);
          ((TransientEntityImpl) e).setPersistentEntity(session.getPersistentSession().newEntity(e.getType()));
          assert e.isSaved();
        }
      }
    });

    rollbackChanges.offer(new Runnable() {
      public void run() {
        // rollback only if entity was actually saved
        if (e.isSaved()) {
          log.debug("Rollback in-memory transient entity from saved state: " + e);
          ((TransientEntityImpl) e).clearPersistentEntity();
          assert e.isNew();
        }
      }
    });
  }

  public void linkAdded(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target, Set<TransientEntity> added) {
    entityChanged(source);
    linkChangedDetailed(source, linkName, LinkChangeType.ADD, added, null);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemovedOrTemporary() && !target.isRemovedOrTemporary()) {
          log.debug("Add link: " + source + "-[" + linkName + "]-> " + target);
          source.getPersistentEntity().addLink(linkName, target.getPersistentEntity());
        }
      }
    });
  }

  private void entityChanged(TransientEntity source) {
    wasChanges = true;
    if (source.isSaved()) {
      changedPersistentEntities.add(source);
    }
    changedEntities.add(source);
  }

  public void linkSet(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    entityChanged(source);
    linkChangedDetailed(source, linkName, LinkChangeType.SET, null, null);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemovedOrTemporary() && !target.isRemovedOrTemporary()) {
          log.debug("Set link: " + source + "-[" + linkName + "]-> " + target);
          source.getPersistentEntity().setLink(linkName, target.getPersistentEntity());
        }
      }
    });
  }

  public void entityDeleted(@NotNull final TransientEntity e) {
    // delete may be rolledback, so it's reasonable to store deleted entities in a separate set and merge with usual on getChanges request
    // also this set of deleted entities should be rolled back on delete rollback
    entityChanged(e);

    final Runnable deleteOutgoingLinks = new Runnable() {
      public void run() {
        if (!e.wasNew()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete outgoing links for entity: " + e);
          }

          ((BerkeleyDbEntity)((TransientEntityImpl) e).getPersistentEntityInternal()).deleteLinks();
        }
      }
    };

    final Runnable deleteEntity = new Runnable() {
      public void run() {
        // do not delete entity that was new in this session
        if (!e.wasNew()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete entity: " + e);
          }

          e.delete();
          //TODO: use delete instead of tryDelete, but in session.check() check that there's no incomming links
          /* Map<String, EntityId> incomingLinks = e.tryDelete();
          //Map<String, EntityId> incomingLinks = ((TransientEntityImpl)e).getPersistentEntityInternal().tryDelete();
          if (incomingLinks.size() > 0) {
            Map<String, TransientEntity> _incomingLinks = new HashMap<String, TransientEntity>(incomingLinks.size());
            for (String key : incomingLinks.keySet()) {
              _incomingLinks.put(key, (TransientEntity) e.getStore().getThreadSession().getEntity(incomingLinks.get(key)));
            }
            throw new ConstraintsValidationException(new CantRemoveEntityException(e, _incomingLinks));
          }  */
        }
      }
    };

    // all delete links should go first
    getDeleted().addFirst(deleteOutgoingLinks);
    // all delete entities should go last
    getDeleted().addLast(deleteEntity);

    rollbackChanges.offer(new Runnable() {
      public void run() {
        if (e.isRemoved()) {
          // rollback entity state to New or Saved
          ((TransientEntityImpl) e).rollbackDelete();
        }
        // discard delete change
        getDeleted().remove(deleteEntity);
        getDeleted().remove(deleteOutgoingLinks);
      }
    });
  }

  public void linkDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    // target is not changed - it has new incomming link
    entityChanged(source);

    HashSet<TransientEntity> removed = null;
    if (target != null) {
        removed = new HashSet<TransientEntity>();
        removed.add(target);
    }
    linkChangedDetailed(source, linkName, LinkChangeType.REMOVE, null, removed);

    offerChange(new Runnable() {
      public void run() {
        // do not remove link if source or target removed and was new, or source or target is temporary
        if (!(((source.isRemoved() && source.wasNew()) || source.isTemporary()) || ((target.isRemoved() && target.wasNew()) || target.isTemporary()))) {
          log.debug("Delete link: " + source + "-[" + linkName + "]-> " + target);
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLink(linkName, ((TransientEntityImpl) target).getPersistentEntityInternal());
        }
      }
    });
  }

  public void linksDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, Set<TransientEntity> removed) {
    entityChanged(source);
    linkChangedDetailed(source, linkName, LinkChangeType.REMOVE, null, removed);

    offerChange(new Runnable() {
      public void run() {
        // remove link if source is not removed or source is removed and was not new
        if (!source.isRemovedOrTemporary() || (source.isRemoved() && !source.wasNew())) {
          log.debug("Delete links: " + source + "-[" + linkName + "]-> *");
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLinks(linkName);
        }
      }
    });
  }

  public void propertyChanged(@NotNull final TransientEntity e,
                              @NotNull final String propertyName,
                              @Nullable final Comparable propertyOldValue,
                              @NotNull final Comparable propertyNewValue) {
    entityChanged(e);
    propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.UPDATE);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set property: " + e + "." + propertyName + "=" + propertyNewValue);
          e.getPersistentEntity().setProperty(propertyName, propertyNewValue);
        }
      }
    });
  }

  public void propertyDeleted(@NotNull final TransientEntity e, @NotNull final String propertyName) {
    entityChanged(e);
    propertyChangedDetailed(e, propertyName, null, PropertyChangeType.REMOVE);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Delete property: " + e + "." + propertyName);
          e.getPersistentEntity().deleteProperty(propertyName);
        }
      }
    });
  }

  public void historyCleared(@NotNull final String entityType) {
    offerChange(new Runnable() {
      public void run() {
        log.debug("Clear history of entities of type [" + entityType + "]");
        session.getPersistentSession().clearHistory(entityType);
      }
    });
  }

  public void blobChanged(@NotNull final TransientEntity e,
                          @NotNull final String blobName,
                          @NotNull final File file) {
    entityChanged(e);
    propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set blob property: " + e + "." + blobName + "=" + file);
          e.getPersistentEntity().setBlob(blobName, file);
        }
      }
    });
  }

  public void blobChanged(@NotNull final TransientEntity e,
                          @NotNull final String blobName,
                          @NotNull final String newValue) {
    entityChanged(e);
    propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set blob property: " + e + "." + blobName + "=" + newValue);
          e.getPersistentEntity().setBlobString(blobName, newValue);
        }
      }
    });
  }

  public void blobDeleted(@NotNull final TransientEntity e, @NotNull final String blobName) {
    entityChanged(e);
    propertyChangedDetailed(e, blobName, null, PropertyChangeType.REMOVE);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Delete blob property: " + e + "." + blobName);
          e.getPersistentEntity().deleteBlob(blobName);
        }
      }
    });
  }

  public void offerChange(@NotNull final Runnable change) {
    changes.offer(change);
  }

  public void dispose() {
    session = null;
  }

  private LinkedList<Runnable> getDeleted() {
    if (deleted == null) {
      deleted = new LinkedList<Runnable>();
    }
    return deleted;
  }
}
