package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.core.dataStructures.decorators.QueueDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.*;
import jetbrains.exodus.database.impl.OperationFailureException;
import jetbrains.exodus.exceptions.PhysicalLayerException;
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
public final class TransientChangesTrackerImpl implements TransientChangesTracker {

  private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

  private TransientStoreSession session;

  private Queue<Runnable> changes = new QueueDecorator<Runnable>();
  private Queue<Runnable> deleteIndexes = new QueueDecorator<Runnable>();
  private Queue<Rollback> rollbackChanges = new QueueDecorator<Rollback>();
  private LinkedList<Runnable> deleted = null;

  private Set<TransientEntity> changedPersistentEntities = new HashSetDecorator<TransientEntity>();
  private Set<TransientEntity> changedEntities = new HashSetDecorator<TransientEntity>();

  private Map<TransientEntity, Map<String, LinkChange>> entityToChangedLinksDetailed = new HashMapDecorator<TransientEntity, Map<String, LinkChange>>();
  private Map<TransientEntity, Map<String, PropertyChange>> entityToChangedPropertiesDetailed = new HashMapDecorator<TransientEntity, Map<String, PropertyChange>>();
  private Map<TransientEntity, Map<Index, Runnable>> entityToIndexChanges = new HashMapDecorator<TransientEntity, Map<Index, Runnable>>();

  private boolean wereChangesAfterMark = false;
  private int changesCount = 0;
  private Set<TransientEntityChange> changesDescription;

  public TransientChangesTrackerImpl(TransientStoreSession session) {
    this.session = session;
  }

  public boolean areThereChanges() {
    return !(changes.isEmpty() && (deleted == null || deleted.isEmpty()));
  }

  public int getChangesCount() {
    return changesCount;
  }

  @Deprecated
  public void markState() {
    wereChangesAfterMark = false;
  }

  @Deprecated
  public boolean wereChangesAfterMarkState() {
    return wereChangesAfterMark;
  }

  private void c() {
    wereChangesAfterMark = true;
    changesDescription = null;
    changesCount++;
  }

    @NotNull
  public Queue<Runnable> getChanges() {
    Queue<Runnable> res = new LinkedList<Runnable>(deleteIndexes);
    res.addAll(changes);
    if (deleted != null) {
      res.addAll(getDeleted());
    }
    return res;
  }

  @NotNull
  public Queue<Rollback> getRollbackChanges(boolean isFinalRollback) {
    return rollbackChanges;
  }

  public void clear() {
    deleteIndexes.clear();
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
    if (changesDescription == null) {
       changesDescription = new HashSetDecorator<TransientEntityChange>();

       for (TransientEntity e : getChangedEntities()) {
         // do not notify about temp and RemovedNew entities
         if (e.isTemporary() || (e.isRemoved() && !e.wasSaved())) continue;

         changesDescription.add(new TransientEntityChange(e, getChangedPropertiesDetailed(e),
                 getChangedLinksDetailed(e), decodeState(e)));
       }
    }

    return changesDescription;
  }

  private EntityChangeType decodeState(TransientEntity e) {
    switch (((AbstractTransientEntity) e).getState()) {
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
        throw new IllegalStateException("Can't decode change for state [" + ((AbstractTransientEntity) e).getState() + "]");
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

  public void registerLinkChanges(@NotNull TransientEntity source,
                                  @NotNull String linkName,
                                  Set<TransientEntity> added,
                                  Set<TransientEntity> removed) {
    c();

    final boolean noAdded = (added == null) || added.isEmpty();
    final boolean noRemoved = (removed == null) || removed.isEmpty();

    if (noAdded && noRemoved) {
        Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(source);
        if (linksDetailed != null)
            linksDetailed.remove(linkName);
        return;
    }

    LinkChangeType changeType;
    if (!noAdded && !noRemoved) {
        changeType = LinkChangeType.ADD_AND_REMOVE;
    } else {
        changeType = noAdded ? LinkChangeType.REMOVE : LinkChangeType.ADD;
    }

    Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(source);
    if (linksDetailed == null) {
      linksDetailed = new HashMap<String, LinkChange>();
      entityToChangedLinksDetailed.put(source, linksDetailed);
      linksDetailed.put(linkName, new LinkChange(linkName, changeType, added, removed));
    } else {
      LinkChange lc = linksDetailed.get(linkName);
      if (lc != null) {
        if (lc.getAddedEntities() != added) {
            lc.setAddedEntities(added);
        }
        if (lc.getRemovedEntities() != removed) {
            lc.setRemovedEntities(removed);
        }
        lc.setChangeType(changeType);
      } else {
        linksDetailed.put(linkName, new LinkChange(linkName, changeType, added, removed));
      }
    }
  }

  @Deprecated
  private void linkChangedDetailed(TransientEntity e, String linkName, LinkChangeType changeType, Set<TransientEntity> addedEntities, Set<TransientEntity> removedEntities) {
    c();

    Map<String, LinkChange> linksDetailed = entityToChangedLinksDetailed.get(e);
    if (linksDetailed == null) {
      linksDetailed = new HashMap<String, LinkChange>();
      entityToChangedLinksDetailed.put(e, linksDetailed);
      linksDetailed.put(linkName, new LinkChange(linkName, changeType, addedEntities, removedEntities));
    } else {
      LinkChange lc = linksDetailed.get(linkName);
      if (lc != null) {
        if (addedEntities != null) {
          final boolean addedGone;
          if (addedGone = annihilateSymmetricChanges(addedEntities, lc.getRemovedEntities())) {
            lc.setAddedEntities(null);
          } else {
            lc.setAddedEntities(addedEntities);
          }
          final boolean removedGone;
          if (removedEntities == null) {
              removedGone = lc.getRemovedEntitiesSize() == 0;
          } else {
            if (removedGone = annihilateSymmetricChanges(removedEntities, lc.getAddedEntities())) {
              lc.setRemovedEntities(null);
            } else {
              lc.setRemovedEntities(removedEntities);
            }
          }
          if (addedGone && removedGone) {
            linksDetailed.remove(linkName);
          } else if (!addedGone && !removedGone) {
            lc.setChangeType(LinkChangeType.ADD_AND_REMOVE); // set change type only if some links removed
          } else {
            if (changeType == LinkChangeType.ADD_AND_REMOVE) { // opposite type gone
              lc.setChangeType(addedGone ? LinkChangeType.REMOVE : LinkChangeType.ADD);
            }
          }
        } else if (removedEntities != null) {
          final boolean removedGone = annihilateSymmetricChanges(removedEntities, lc.getAddedEntities());
          if (removedGone) {
            if (lc.getAddedEntitiesSize() == 0) {
              linksDetailed.remove(linkName);
            } else if (changeType == LinkChangeType.ADD_AND_REMOVE) {
                lc.setChangeType(LinkChangeType.ADD); // removed gone
            }
          } else {
            lc.setRemovedEntities(removedEntities);
            lc.setChangeType(lc.getChangeType().getMerged(changeType)); // set change type only if some links removed
          }
        }
      } else {
        linksDetailed.put(linkName, new LinkChange(linkName, changeType, addedEntities, removedEntities));
      }
    }
  }

    @Deprecated
    private final boolean annihilateSymmetricChanges(final Set<TransientEntity> newSet, final Set<TransientEntity> oldSet) {
        if (oldSet != null) {
          Iterator<TransientEntity> addedItr = newSet.iterator();
          while (addedItr.hasNext()) {
            if (oldSet.remove(addedItr.next())) {
              if (newSet.size() == 1) {
                  return true; // new set will be completely annihilated, hack for immutable NanoSet
              }
              addedItr.remove();
            }
          }
          return newSet.isEmpty() && oldSet.isEmpty();
        }
        return newSet.isEmpty();
    }

  private Runnable propertyChangedDetailed(TransientEntity e, String propertyName, Comparable origValue, PropertyChangeType changeType, Runnable change) {
    c();

    Map<String, PropertyChange> propertiesDetailed = entityToChangedPropertiesDetailed.get(e);
    if (propertiesDetailed == null) {
      propertiesDetailed = new HashMap<String, PropertyChange>();
      entityToChangedPropertiesDetailed.put(e, propertiesDetailed);
    }
    // get previous change if any
    PropertyChange propertyChange = propertiesDetailed.get(propertyName);
    Runnable prevChange = propertyChange == null ? null : ((PropertyChangeInternal)propertyChange).getChange();
    propertiesDetailed.put(propertyName, new PropertyChangeInternal(propertyName, origValue, changeType, change));

    return prevChange;
  }

  @Nullable
  private Runnable rollbackPropertyChangedDetailed(TransientEntity e, String propertyName) {
    Map<String, PropertyChange> propertiesDetailed = entityToChangedPropertiesDetailed.get(e);
    if (propertiesDetailed != null) {
        PropertyChange propertyChange = propertiesDetailed.get(propertyName);

        if (propertyChange != null) {
            Runnable prevChange = ((PropertyChangeInternal)propertyChange).getChange();
            propertiesDetailed.remove(propertyName);

            return prevChange;
        }
    }

    return null;
  }

  public void entityAdded(@NotNull final TransientEntity e) {
    assert e.isNew();
    entityChanged(e);

    offerChange(new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          assert e.isNew();
          if (log.isDebugEnabled()) {
            log.debug("Add new entity: " + e);
          }
          ((TransientEntityImpl) e).setPersistentEntity(session.getPersistentTransaction().newEntity(e.getType()));
          assert e.isSaved();
        }
      }
    });

    rollbackChanges.offer(new Rollback() {
        public void rollback(boolean isFinalRollback) {
            // rollback only if entity was actually saved
            if (e.isSaved() && e.wasNew()) {
              if (log.isDebugEnabled()) {
                log.debug("Rollback in-memory transient entity from saved state: " + e);
              }
              ((TransientEntityImpl) e).clearPersistentEntity();
              assert e.isNew();
            }
        }
    });
  }

  public void linkAdded(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    entityChanged(source);
    persistentEntityChanged(target);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemovedOrTemporary() && !target.isRemovedOrTemporary()) {
          if (log.isDebugEnabled()) {
            log.debug("Add link: " + source + "-[" + linkName + "]-> " + target);
          }
          source.getPersistentEntity().addLink(linkName, target.getPersistentEntity());
        }
      }
    });
  }

  private void entityChanged(TransientEntity source) {
    persistentEntityChanged(source);
    changedEntities.add(source);
  }

  private void persistentEntityChanged(TransientEntity entity) {
    c();
    if (entity.isSaved()) {
      changedPersistentEntities.add(entity);
    }
  }

  public void linkSet(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    entityChanged(source);
    persistentEntityChanged(target);

    offerChange(new Runnable() {
      public void run() {
        if (!source.isRemovedOrTemporary()) {
            if (!target.isRemovedOrTemporary()) {
                if (log.isDebugEnabled()) {
                    log.debug("Set link: " + source + "-[" + linkName + "]-> " + target);
                }
                source.getPersistentEntity().setLink(linkName, target.getPersistentEntity());
            } else if (target.isRemoved()) {
                source.getPersistentEntity().deleteLinks(linkName);
            }
        }
      }
    });

    changeIndexes(source, linkName);
  }

  public void entityDeleted(@NotNull final TransientEntity e) {
    // delete may be rolledback, so it's reasonable to store deleted entities in a separate set and merge with usual on getChanges request
    // also this set of deleted entities should be rolled back on delete rollback
    entityChanged(e);

    final Runnable deleteUniqueProperties = new Runnable() {
      public void run() {
        if (e.isSaved() || e.wasSaved()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete unique properties for entity: " + e);
          }

          deleteIndexKeys(e);
        }
      }
    };
    deleteIndexes.offer(deleteUniqueProperties);

    final Runnable deleteOutgoingLinks = new Runnable() {
      public void run() {
        if (e.isSaved() || e.wasSaved()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete outgoing links for entity: " + e);
          }

          ((PersistentEntity) ((TransientEntityImpl) e).getPersistentEntityInternal()).deleteLinks();
        }
      }
    };

    /* Commented code below (PART I and PART II) helps to determine from where incorrect entity deletion
       (when some incoming links to deleted entity are left in database) was made. */

    // PART I
    /* final Throwable cause;
    try {
      throw new RuntimeException();
    } catch (Throwable t) {
      cause = t;
    } */

    final Runnable deleteEntity = new Runnable() {
      public void run() {
        // do not delete entity that was not saved in this session
        if (e.isSaved() || e.wasSaved()) {
          if (log.isDebugEnabled()) {
            log.debug("Delete entity: " + e);
          }

          // PART II
          /* Map<String, EntityId> incomingLinks = e.getIncomingLinks();
          for (String linkName: incomingLinks.keySet()) {
            EntityId id = incomingLinks.get(linkName);
            throw new IllegalStateException("Incoming link " + linkName + " from entity with id " + id + " to " + e.getType() + " with id " + e.getId(), cause);
          } */

          // delete entity
          e.deleteInternal();
        }
      }
    };

    // all delete links must go first
    getDeleted().addFirst(deleteOutgoingLinks);
    // all delete entities must go last
    getDeleted().addLast(deleteEntity);

    rollbackChanges.offer(new Rollback() {
        public void rollback(boolean isFinalRollback) {
            if (e.isRemoved()) {
              // rollback entity state to New or Saved or SavedNew
              ((TransientEntityImpl) e).rollbackDelete();
            }

            if (isFinalRollback) {
                // discard delete change
                deleteIndexes.remove(deleteUniqueProperties);
                getDeleted().remove(deleteEntity);
                getDeleted().remove(deleteOutgoingLinks);
            }
        }
    });
  }

  public void linkDeleted(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
    // target is not changed - it has new incomming link
    entityChanged(source);

    offerChange(new Runnable() {
      public void run() {
        //do not remove link if source or target removed, wasn't saved and was new, or source or target is temporary
        if (!(((source.isRemoved() && !source.wasSaved()) || source.isTemporary()) || ((target.isRemoved() && !target.wasSaved()) || target.isTemporary()))) {
          log.debug("Delete link: " + source + "-[" + linkName + "]-> " + target);
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLink(linkName, ((TransientEntityImpl) target).getPersistentEntityInternal());
        }
      }
    });
  }

  public void linksDeleted(@NotNull final TransientEntity source, @NotNull final String linkName) {
    entityChanged(source);

    offerChange(new Runnable() {
      public void run() {
        // remove link if source is not removed or source is removed and was not new
        if (!source.isRemovedOrTemporary() || (source.isRemoved() && !source.wasNew())) {
          log.debug("Delete links: " + source + "-[" + linkName + "]-> *");
          ((TransientEntityImpl) source).getPersistentEntityInternal().deleteLinks(linkName);
        }
      }
    });
    // do not change indexes - empty link from index must be coutch during constraints phase
  }

  public void propertyChanged(@NotNull final TransientEntity e,
                              @NotNull final String propertyName,
                              @Nullable final Comparable propertyOldValue,
                              @NotNull final Comparable propertyNewValue) {
    if (propertyNewValue.equals(propertyOldValue)) {
      // rollback property change
      offerChange(null, rollbackPropertyChangedDetailed(e, propertyName));
    } else {
      entityChanged(e);

      Runnable changeProperty = new Runnable() {
        public void run() {
          if (!e.isRemovedOrTemporary()) {
            if (log.isDebugEnabled()) {
              log.debug("Set property: " + e + "." + propertyName + "=" + propertyNewValue);
            }
            try {
              e.getPersistentEntity().setProperty(propertyName, propertyNewValue);
            } catch (OperationFailureException ofe) {
              if (log.isErrorEnabled()) {
                log.error("Failed set property: " + e + "." + propertyName + "=" + propertyNewValue, ofe);
              }
              throw ofe;
            }
          }
        }
      };

      offerChange(changeProperty, propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.UPDATE, changeProperty));
      changeIndexes(e, propertyName);
    }
  }

  public void propertyDeleted(@NotNull final TransientEntity e, @NotNull final String propertyName, @Nullable final Comparable propertyOldValue) {
    //
    if (propertyOldValue == null) {
      // rollback property change
      offerChange(null, rollbackPropertyChangedDetailed(e, propertyName));
    } else {
      entityChanged(e);
      Runnable deleteProperty = new Runnable() {
        public void run() {
          if (!e.isRemovedOrTemporary()) {
            if (log.isDebugEnabled()) {
              log.debug("Delete property: " + e + "." + propertyName);
            }
            try {
              e.getPersistentEntity().deleteProperty(propertyName);
              deleteIndexKeys(e, propertyName);
            } catch (OperationFailureException ofe) {
              if (log.isErrorEnabled()) {
                log.error("Failed delete property: " + e + "." + propertyName, ofe);
              }
              throw ofe;
            }
          }
        }
      };
      offerChange(deleteProperty, propertyChangedDetailed(e, propertyName, propertyOldValue, PropertyChangeType.REMOVE, deleteProperty));
      // do not change indexes - empty link from index must be coutch during constraints phase
    }
  }

  public void historyCleared(@NotNull final String entityType) {
    c();

    offerChange(new Runnable() {
      public void run() {
        log.debug("Clear history of entities of type [" + entityType + "]");
        session.getPersistentTransaction().clearHistory(entityType);
      }
    });
  }

  public void blobChanged(@NotNull final TransientEntity e,
                          @NotNull final String blobName,
                          @NotNull final File file) {
    entityChanged(e);
    Runnable blobChanged = new Runnable() {
      public void run() {
        if (!e.isRemovedOrTemporary()) {
          log.debug("Set blob property: " + e + "." + blobName + "=" + file);
          e.getPersistentEntity().setBlob(blobName, file);
        }
      }
    };
    offerChange(blobChanged, propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE, blobChanged));
  }

  public void blobChanged(@NotNull final TransientEntity e,
                          @NotNull final String blobName,
                          @NotNull final String newValue) {
    String oldPropertyValue = e.isSaved() ? ((TransientEntityImpl)e).getPersistentEntityInternal().getBlobString(blobName) : null;
    if (newValue.equals(oldPropertyValue)) {
      // rollback property change
      offerChange(null, rollbackPropertyChangedDetailed(e, blobName));
    } else {
      entityChanged(e);
      Runnable blobChanged = new Runnable() {
        public void run() {
          if (!e.isRemovedOrTemporary()) {
            log.debug("Set blob property: " + e + "." + blobName + "=" + newValue);
            e.getPersistentEntity().setBlobString(blobName, newValue);
          }
        }
      };
      offerChange(blobChanged, propertyChangedDetailed(e, blobName, null, PropertyChangeType.UPDATE, blobChanged));
    }
  }

  public void blobDeleted(@NotNull final TransientEntity e, @NotNull final String blobName) {
    final boolean hasOldValue;
    if (e.isSaved()) {
        PersistentEntity persistentEntity = (PersistentEntity) ((TransientEntityImpl) e).getPersistentEntityInternal();
        hasOldValue = persistentEntity.hasBlob(blobName);
    } else {
        hasOldValue = false;
    }
    if (!hasOldValue) {
      offerChange(null, rollbackPropertyChangedDetailed(e, blobName));
    } else {
      entityChanged(e);

      Runnable deleteBlob = new Runnable() {
        public void run() {
          if (!e.isRemovedOrTemporary()) {
            log.debug("Delete blob property: " + e + "." + blobName);
            e.getPersistentEntity().deleteBlob(blobName);
          }
        }
      };
      offerChange(deleteBlob, propertyChangedDetailed(e, blobName, null, PropertyChangeType.REMOVE, deleteBlob));
    }
  }

  private void offerChange(final Runnable change, @Nullable Runnable changeToRemove) {
    if (changeToRemove != null) {
      changes.remove(changeToRemove);
    }

    if (change != null) {
      changes.offer(change);
    }
  }

  void offerChange(@NotNull final Runnable change) {
    offerChange(change, null);
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

  private Set<Index> getMetadataIndexes(TransientEntity e) {
    EntityMetaData md = getEntityMetaData(e);
    return md == null ? null : md.getIndexes();
  }

  private Set<Index> getMetadataIndexes(TransientEntity e, String field) {
    EntityMetaData md = getEntityMetaData(e);
    return md == null ? null : md.getIndexes(field);
  }

  private void changeIndexes(final TransientEntity e, String propertyName) {
    if (TransientStoreUtil.isPostponeUniqueIndexes()) {
        return;
    }
    // update all indexes for this property
    Set<Index> indexes = getMetadataIndexes(e, propertyName);
    final boolean isNew = e.isNew();
    // remember original values
    if (indexes != null) {
      for (final Index index: indexes) {
        offerIndexChange(e, index, new Runnable(){
          public void run() {
            try {
              if (!e.isRemovedOrTemporary()) {
                if (isNew) {
                  // create new index
                  getPersistentSession().insertUniqueKey(
                          index, getIndexFieldsFinalValues(e, index), ((TransientEntityImpl) e).getPersistentEntityInternal());
                } else {
                  // update existing index
                  getPersistentSession().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
                  getPersistentSession().insertUniqueKey(
                          index, getIndexFieldsFinalValues(e, index), ((TransientEntityImpl) e).getPersistentEntityInternal());
                }
              }
            } catch (PhysicalLayerException ex) {
              throwIndexUniquenessViolationException(e, index);
            }
          }
        });
      }
    }
  }

  private void offerIndexChange(TransientEntity e, Index index, Runnable change) {
    // actual index change will be performed just after last change of property or link, that are part of particalar index
    Map<Index, Runnable> indexChanges = entityToIndexChanges.get(e);
    if (indexChanges == null) {
      indexChanges = new HashMap<Index, Runnable>();
      entityToIndexChanges.put(e, indexChanges);
    }

    Runnable prevChange = indexChanges.put(index, change);
    if (prevChange != null) {
      changes.remove(prevChange);
    }

    changes.add(change);
  }

  private EntityMetaData getEntityMetaData(TransientEntity e) {
    ModelMetaData mdd = ((TransientEntityStore) session.getStore()).getModelMetaData();
    return mdd == null ? null : mdd.getEntityMetaData(e.getType());
  }

  private void deleteIndexKeys(TransientEntity e) {
    if (TransientStoreUtil.isPostponeUniqueIndexes()) {
        return;
    }
    EntityMetaData emd = getEntityMetaData(e);
    if (emd != null) {
      for (Index index : emd.getIndexes()) {
        getPersistentSession().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
      }
    }
  }

  private void deleteIndexKeys(TransientEntity e, String name) {
    if (TransientStoreUtil.isPostponeUniqueIndexes()) {
        return;
    }
    EntityMetaData emd = getEntityMetaData(e);
    if (emd != null) {
      for (Index index : emd.getIndexes(name)) {
        getPersistentSession().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
      }
    }
  }

  private List<Comparable> getIndexFieldsOriginalValues(TransientEntity e, Index index) {
    List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
    for (IndexField f: index.getFields()) {
      if (f.isProperty()) {
        res.add(getOriginalPropertyValue(e, f.getName()));
      } else {
        res.add(getOriginalLinkValue(e, f.getName()));
      }
    }
    return res;
  }

  private List<Comparable> getIndexFieldsFinalValues(TransientEntity e, Index index) {
    List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
    for (IndexField f: index.getFields()) {
      if (f.isProperty()) {
        res.add(e.getProperty(f.getName()));
      } else {
        res.add(e.getLink(f.getName()));
      }
    }
    return res;
  }

  private Comparable getOriginalPropertyValue(TransientEntity e, String propertyName) {
    // get from saved changes, if not - from db
    Map<String, PropertyChange> propertiesDetailed = getChangedPropertiesDetailed(e);
    if (propertiesDetailed != null) {
      PropertyChange propertyChange = propertiesDetailed.get(propertyName);
      if (propertyChange != null) {
        return propertyChange.getOldValue();
      }
    }
    return ((TransientEntityImpl)e).getPersistentEntityInternal().getProperty(propertyName);
  }

  private Comparable getOriginalLinkValue(TransientEntity e, String linkName) {
    // get from saved changes, if not - from db
    Map<String, LinkChange> linksDetailed = getChangedLinksDetailed(e);
    if (linksDetailed != null) {
      LinkChange change = linksDetailed.get(linkName);
      if (change != null) {
        switch (change.getChangeType()) {
            case ADD_AND_REMOVE:
            case REMOVE:
                if (change.getRemovedEntitiesSize() != 1) {
                    throw new IllegalStateException("Can't determine original link value: " + e.getType() + "." + linkName);
                }
                return change.getRemovedEntities().iterator().next();
            default:
                throw new IllegalStateException("Incorrect change type for link that is part of index: " + e.getType() + "." + linkName + ": " + change.getChangeType().getName());
        }
      }
    }
    return ((TransientEntityImpl)e).getPersistentEntityInternal().getLink(linkName);
  }

  private StoreTransaction getPersistentSession() {
    return session.getPersistentTransaction();
  }

  private void throwIndexUniquenessViolationException(TransientEntity e, Index index) {
    throw new ConstraintsValidationException(new UniqueIndexViolationException(e, index));
  }

}
