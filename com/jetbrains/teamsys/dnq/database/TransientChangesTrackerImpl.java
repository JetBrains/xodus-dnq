package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.decorators.MapDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.QueueDecorator;
import com.jetbrains.teamsys.core.dataStructures.decorators.SetDecorator;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.CantRemoveEntityException;
import com.jetbrains.teamsys.database.exceptions.ConstraintsValidationException;
import com.jetbrains.teamsys.database.exceptions.EntityRemovedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

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
  private LinkedList<Runnable> deletes = new LinkedList<Runnable>();
  private Queue<Runnable> rollbackChanges = new QueueDecorator<Runnable>();
  private Set<TransientEntity> changedPersistentEntities = new SetDecorator<TransientEntity>();
  private Set<TransientEntity> changedEntities = new SetDecorator<TransientEntity>();
  private Map<TransientEntity, Set<String>> entityToChangedLinks = new MapDecorator<TransientEntity, Set<String>>();
  private Map<TransientEntity, Set<String>> entityToChangedProperties = new MapDecorator<TransientEntity, Set<String>>();

  private Map<TransientEntity, Map<String, LinkChange>> entityToChangedLinksDetailed =
    new MapDecorator<TransientEntity, Map<String, LinkChange>>();
  private boolean wasChanges = false;

  public TransientChangesTrackerImpl(TransientStoreSession session) {
    this.session = session;
  }

  public boolean areThereChanges() {
    return !changes.isEmpty() || !deletes.isEmpty();
  }

  @NotNull
  public Queue<Runnable> getChanges() {
    Queue<Runnable> res = new LinkedList<Runnable>(changes);
    res.addAll(deletes);
    return res;
  }

  @NotNull
  public Queue<Runnable> getRollbackChanges() {
    return rollbackChanges;
  }

  public void markState() {
    wasChanges = false;
  }

  public boolean wasChangesAfterMarkState() {
    return wasChanges;
  }

  public void clear() {
    changes.clear();
    deletes.clear();
    rollbackChanges.clear();
    changedPersistentEntities.clear();
    changedEntities.clear();
    entityToChangedLinks.clear();
    entityToChangedProperties.clear();
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
    Set<TransientEntityChange> res = new HashSet<TransientEntityChange>();

    for (TransientEntity e : getChangedEntities()) {
      //TODO: return removed for text index?
      if (!e.isRemoved()) {
        res.add(new TransientEntityChange(e, getChangedProperties(e), getChangedLinks(e),
                getChangedLinksDetailed(e), e.isNew() ? EntityChangeType.ADD : EntityChangeType.UPDATE));
      }
    }

    return res;
  }


  public TransientEntityChange getChangeDescription(TransientEntity e) {
    if (!e.isRemoved()) {
      return new TransientEntityChange(e, getChangedProperties(e), getChangedLinks(e),
              getChangedLinksDetailed(e), e.isNew() ? EntityChangeType.ADD : EntityChangeType.UPDATE);
    } else {
      throw new EntityRemovedException(e);
    }
  }

  @Nullable
  public Set<String> getChangedLinks(@NotNull TransientEntity e) {
    return entityToChangedLinks.get(e);
  }

  public Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e) {
    return entityToChangedLinksDetailed.get(e);    
  }

  @Nullable
  public Set<String> getChangedProperties(@NotNull TransientEntity e) {
    return entityToChangedProperties.get(e);
  }

  private void linkChanged(TransientEntity e, String linkName, LinkChangeType changeType) {
    Set<String> links = entityToChangedLinks.get(e);

    if (links == null) {
      links = new HashSet<String>();
      entityToChangedLinks.put(e, links);
    }

    links.add(linkName);

    linkChangedDetailed(e, linkName, changeType);
  }

  private void linkChangedDetailed(TransientEntity e, String linkName, LinkChangeType changeType) {
    Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(e);
    if (linksDetailed == null) {
      linksDetailed = new HashMap<String, LinkChange>();
      entityToChangedLinksDetailed.put(e, linksDetailed);
      linksDetailed.put(linkName, new LinkChange(linkName, changeType));
    } else {
      LinkChange lc = linksDetailed.get(linkName);

      if (lc != null) {
        lc.setChangeType(lc.getChangeType().getMerged(changeType));
      } else {
        linksDetailed.put(linkName, new LinkChange(linkName, changeType));        
      }
    }
  }

  private void propertyChanged(TransientEntity e, String propertyName) {
    Set<String> properties = entityToChangedProperties.get(e);

    if (properties == null) {
      properties = new HashSet<String>();
      entityToChangedProperties.put(e, properties);
    }

    properties.add(propertyName);
  }

  public void entityAdded(@NotNull final TransientEntity e) {
    assert e.isNew();
    entityChanged(e);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemoved() && !e.isTemporary()) {
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

  public void linkAdded(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    entityChanged(source);
    linkChanged(source, linkName, LinkChangeType.ADD);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemoved() && !target.isRemoved()) {
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
    linkChanged(source, linkName, LinkChangeType.SET);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemoved() && !target.isRemoved()) {
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

          //TODO: use delete instead of tryDelete, but in session.check() check that there's no incomming links
          Map<String, EntityId> incomingLinks = ((TransientEntityImpl) e).getPersistentEntityInternal().tryDelete();
          if (incomingLinks.size() > 0) {
            Map<String, TransientEntity> _incomingLinks = new HashMap<String, TransientEntity>(incomingLinks.size());
            for (String key : incomingLinks.keySet()) {
              _incomingLinks.put(key, (TransientEntity) ((TransientStoreSession) e.getStore().getThreadSession()).getEntity(incomingLinks.get(key)));
            }
            throw new ConstraintsValidationException(new CantRemoveEntityException(e, _incomingLinks));
          }
        }
      }
    };

    // all delete links should go first
    deletes.addFirst(deleteOutgoingLinks);
    // all delete entities should go last
    deletes.addLast(deleteEntity);

    rollbackChanges.offer(new Runnable() {
      public void run() {
        if (e.isRemoved()) {
          // rollback entity state to New or Saved
          ((TransientEntityImpl) e).rollbackDelete();
        }
        // discard delete change
        deletes.remove(deleteEntity);
        deletes.remove(deleteOutgoingLinks);
      }
    });
  }

  public void linkDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    // target is not changed - it has new incomming link
    entityChanged(source);
    linkChanged(source, linkName, LinkChangeType.REMOVE);

    offerChange(new Runnable() {
      public void run() {
        // do not remove link if source or target removed and was new
        if (!((source.isRemoved() && source.wasNew()) || (target.isRemoved() && target.wasNew()))) {
          log.debug("Delete link: " + source + "-[" + linkName + "]-> " + target);
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLink(linkName, ((TransientEntityImpl) target).getPersistentEntityInternal());
        }
      }
    });
  }

  public void linksDeleted(@NotNull final TransientEntity source, @NotNull final String linkName) {
    entityChanged(source);
    linkChanged(source, linkName, LinkChangeType.REMOVE);

    offerChange(new Runnable() {
      public void run() {
        // remove link if source is not removed or source is removed and was not new
        if (!source.isRemoved() || (source.isRemoved() && !source.wasNew())) {
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
    propertyChanged(e, propertyName);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemoved() && !e.isTemporary()) {
          log.debug("Set property: " + e + "." + propertyName + "=" + propertyNewValue);
          e.getPersistentEntity().setProperty(propertyName, propertyNewValue);
        }
      }
    });
  }

  public void propertyDeleted(@NotNull final TransientEntity e, @NotNull final String propertyName) {
    entityChanged(e);
    propertyChanged(e, propertyName);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemoved() && !e.isTemporary()) {
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
    propertyChanged(e, blobName);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemoved() && !e.isTemporary()) {
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
    propertyChanged(e, blobName);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemoved() && !e.isTemporary()) {
          log.debug("Set blob property: " + e + "." + blobName + "=" + newValue);
          e.getPersistentEntity().setBlobString(blobName, newValue);
        }
      }
    });
  }

  public void blobDeleted(@NotNull final TransientEntity e, @NotNull final String blobName) {
    entityChanged(e);
    propertyChanged(e, blobName);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemoved() && !e.isTemporary()) {
          log.debug("Delete blob property: " + e + "." + blobName);
          e.getPersistentEntity().deleteBlob(blobName);
        }
      }
    });
  }

  public void offerChange(@NotNull final Runnable change) {
    changes.offer(change);
  }

}
