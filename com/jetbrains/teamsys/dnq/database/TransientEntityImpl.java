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

  protected Map<String, TransientLinksManager> linksManagers = new HashMapDecorator<String, TransientLinksManager>();
  protected Map<String, Comparable> propertiesCache = new HashMapDecorator<String, Comparable>();
  private Map<String, File> fileBlobsCache = new HashMapDecorator<String, File>();

  TransientEntityImpl(@NotNull String type, @NotNull TransientStoreSession session) {
    setTransientStoreSession(session);
    setType(type);
    setState(State.New);
    setId(new TransientEntityIdImpl(TransientEntityImpl.this));

    session.getTransientChangesTracker().entityAdded(this);

    //trackEntityCreation(session);
  }

  TransientEntityImpl(@NotNull Entity persistentEntity, @NotNull TransientStoreSession session) {
    setTransientStoreSession(session);
    setState(State.New);
    setId(new TransientEntityIdImpl(TransientEntityImpl.this));

    setPersistentEntityInternal(persistentEntity);
    setState(State.Saved);

    //trackEntityCreation(session);
  }

  private static final StandartEventHandler<String, Object> getPropertyEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String propertyName, Object param2) {
      if (!(_(entity).propertiesCache.containsKey(propertyName))) {
        final Comparable v = entity.getPersistentEntityInternal().getProperty(propertyName);
        _(entity).propertiesCache.put(propertyName, v);
        return v;
      } else {
        return _(entity).propertiesCache.get(propertyName);
      }
    }

    Object processOpenNew(AbstractTransientEntity entity, String propertyName, Object param2) {
      return _(entity).propertiesCache.get(propertyName);
    }

    Object processTemporary(AbstractTransientEntity entity, String propertyName, Object param2) {
      return processOpenNew(entity, propertyName, param2);
    }

  };


  @Nullable
  public <T extends Comparable> T getProperty(@NotNull final String propertyName) {
    return (T) getPropertyEventHandler.handle(this, propertyName, null);
  }

  private static final StandartEventHandler<String, Comparable> setPropertyEventHandler = new StandartEventHandler2<String, Comparable>() {
    Object processOpenSaved(AbstractTransientEntity entity, String propertyName, Comparable value) {
      entity.getTransientStoreSession().getTransientChangesTracker().propertyChanged(entity, propertyName, entity.getProperty(propertyName), value);
      _(entity).propertiesCache.put(propertyName, value);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String propertyName, Comparable value) {
      return processOpenSaved(entity, propertyName, value);
    }

    Object processTemporary(AbstractTransientEntity entity, String propertyName, Comparable value) {
      _(entity).propertiesCache.put(propertyName, value);
      return null;
    }

  };


  public <T extends Comparable> void setProperty(@NotNull final String propertyName,
                                                 @NotNull final T value) {
    setPropertyEventHandler.handle(this, propertyName, value);
  }


  private static final StandartEventHandler<String, Object> deletePropertyEventHandler = new StandartEventHandler2<String, Object>() {

    Object processOpenSaved(AbstractTransientEntity entity, String propertyName, Object value) {
      entity.getTransientStoreSession().getTransientChangesTracker().propertyDeleted(entity, propertyName);
      _(entity).propertiesCache.put(propertyName, null);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String propertyName, Object value) {
      return processOpenSaved(entity, propertyName, value);
    }

  };


  public void deleteProperty(@NotNull final String propertyName) {
    deletePropertyEventHandler.handle(this, propertyName, null);
  }

  private static final StandartEventHandler<String, Object> getBlobEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String blobName, Object value) {
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

    Object processOpenNew(AbstractTransientEntity entity, String blobName, Object value) {
      File f = _(entity).fileBlobsCache.get(blobName);

      try {
        return f == null ? null : new FileInputStream(f);
      } catch (FileNotFoundException e) {
        throw new RuntimeException(e);
      }
    }

    Object processTemporary(AbstractTransientEntity entity, String blobName, Object value) {
      return processOpenNew(entity, blobName, value);
    }

  };


  @Nullable
  public InputStream getBlob(@NotNull final String blobName) {
    return (InputStream) getBlobEventHandler.handle(this, blobName, null);
  }

  private static final StandartEventHandler<String, InputStream> setBlobEventHandler = new StandartEventHandler2<String, InputStream>() {

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
      throw new RuntimeException("Can't save blob to file [" + outputFile.getAbsolutePath() + "]");
    } finally {
      if (out != null) {
        try {
          out.close();
        } catch (IOException e1) {
        }
      }
    }

    return outputFile;
  }

  private static final StandartEventHandler<String, File> setBlobFileEventHandler = new StandartEventHandler2<String, File>() {

    Object processOpenSaved(AbstractTransientEntity entity, String blobName, File file) {
      File f = _(entity).moveOrCopy(file);
      entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, f);
      _(entity).fileBlobsCache.put(blobName, f);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String blobName, File file) {
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
          } catch (IOException e) {
          }
        }
        if (out != null) {
          try {
            out.close();
          } catch (IOException e1) {
          }
        }
      }
    }

    return outputFile;
  }

  private static final StandartEventHandler<String, Object> deleteBlobEventHandler = new StandartEventHandler2<String, Object>() {

    Object processOpenSaved(AbstractTransientEntity entity, String blobName, Object param2) {
      _(entity).fileBlobsCache.put(blobName, null);
      entity.getTransientStoreSession().getTransientChangesTracker().blobDeleted(entity, blobName);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String blobName, Object param2) {
      return processOpenSaved(entity, blobName, param2);
    }

  };


  public void deleteBlob(@NotNull final String blobName) {
    deleteBlobEventHandler.handle(this, blobName, null);
  }


  private static final StandartEventHandler<String, Object> getBlobStringEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String blobName, Object param2) {
      if (!_(entity).propertiesCache.containsKey(blobName)) {
        final String value = entity.getPersistentEntityInternal().getBlobString(blobName);
        _(entity).propertiesCache.put(blobName, value);
        return value;
      }
      return _(entity).propertiesCache.get(blobName);
    }

    Object processOpenNew(AbstractTransientEntity entity, String blobName, Object param2) {
      return _(entity).propertiesCache.get(blobName);
    }

    Object processTemporary(AbstractTransientEntity entity, String blobName, Object param2) {
      return processOpenNew(entity, blobName, param2);
    }

  };


  @Nullable
  public String getBlobString(@NotNull final String blobName) {
    return (String) getBlobStringEventHandler.handle(this, blobName, null);
  }

  private static final StandartEventHandler<String, String> setBlobStringEventHandler = new StandartEventHandler2<String, String>() {
    Object processOpenSaved(AbstractTransientEntity entity, String blobName, String blobString) {
      entity.getTransientStoreSession().getTransientChangesTracker().blobChanged(entity, blobName, blobString);
      _(entity).propertiesCache.put(blobName, blobString);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String blobName, String blobString) {
      return processOpenSaved(entity, blobName, blobString);
    }

  };


  public void setBlobString(@NotNull final String blobName, @NotNull final String blobString) {
    setBlobStringEventHandler.handle(this, blobName, blobString);
  }

  private static final StandartEventHandler<String, Object> deleteBlobStringEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String blobName, Object blobString) {
      _(entity).propertiesCache.put(blobName, null);
      entity.getTransientStoreSession().getTransientChangesTracker().blobDeleted(entity, blobName);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String blobName, Object blobString) {
      return processOpenSaved(entity, blobName, blobString);
    }

  };


  public void deleteBlobString(@NotNull final String blobName) {
    deleteBlobStringEventHandler.handle(this, blobName, null);
  }

  private static final StandartEventHandler<String, Entity> addLinkEventHandler = new StandartEventHandler2<String, Entity>() {

    Object processOpenSaved(AbstractTransientEntity entity, String linkName, Entity target) {
      _(entity).getLinksManager(linkName).addLink((TransientEntity) target);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String linkName, Entity target) {
      return processOpenSaved(entity, linkName, target);
    }

  };

  public void addLink(@NotNull final String linkName, @NotNull final Entity target) {
    addLinkEventHandler.handle(this, linkName, target);
  }

  private static final StandartEventHandler<String, Entity> setLinkEventHandler = new StandartEventHandler2<String, Entity>() {
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

  private static final StandartEventHandler<String, Entity> deleteLinkEventHandler = new StandartEventHandler2<String, Entity>() {

    Object processOpenSaved(AbstractTransientEntity entity, String linkName, Entity target) {
      _(entity).getLinksManager(linkName).deleteLink((TransientEntity) target);
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String linkName, Entity target) {
      return processOpenSaved(entity, linkName, target);
    }

  };


  public void deleteLink(@NotNull final String linkName, @NotNull final Entity target) {
    deleteLinkEventHandler.handle(this, linkName, target);
  }

  private static final StandartEventHandler<String, Object> deleteLinksEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
      _(entity).getLinksManager(linkName).deleteLinks();
      return null;
    }

    Object processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
      return processOpenSaved(entity, linkName, param2);
    }

  };

  public void deleteLinks(@NotNull final String linkName) {
    deleteLinksEventHandler.handle(this, linkName, null);
  }

  private static final StandartEventHandler<String, Object> getLinksEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
      return _(entity).getLinksManager(linkName).getLinks();
    }

    Object processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
      return processOpenSaved(entity, linkName, param2);
    }

  };

  @NotNull
  public EntityIterable getLinks(@NotNull final String linkName) {
    return (EntityIterable) getLinksEventHandler.handle(this, linkName, null);
  }

  private static final StandartEventHandler<String, Object> getLinkEventHandler = new StandartEventHandler2<String, Object>() {

    Object processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
      return _(entity).getLinksManager(linkName).getLink();
    }

    Object processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
      return processOpenSaved(entity, linkName, param2);
    }

  };

  @Nullable
  public Entity getLink(@NotNull final String linkName) {
    return (Entity) getLinkEventHandler.handle(this, linkName, null);
  }

  private static final StandartEventHandler<String, Object> getLinksSizeEventHandler = new StandartEventHandler2<String, Object>() {
    Object processOpenSaved(AbstractTransientEntity entity, String linkName, Object param2) {
      return _(entity).getLinksManager(linkName).getLinksSize();
    }

    Object processOpenNew(AbstractTransientEntity entity, String linkName, Object param2) {
      return processOpenSaved(entity, linkName, param2);
    }

  };


  public long getLinksSize(@NotNull final String linkName) {
    return (Long) getLinksSizeEventHandler.handle(this, linkName, null);
  }

  @NotNull
  public Map<String, EntityId> tryDelete() {
    final Map<String, EntityId> result = new HashMapDecorator<String, EntityId>();
    final TransientStoreSession session = getTransientStoreSession();
    final StoreSession persistentSession = session.getPersistentSession();
    final ModelMetaData mmd = ((TransientEntityStore) session.getStore()).getModelMetaData();
    final EntityMetaData emd = mmd.getEntityMetaData(getType());
    // EntityMetaData can be null during refactorings
    if (emd != null) {
      for (final Map.Entry<String, Set<String>> entry : emd.getIncomingAssociations(mmd).entrySet()) {
        final String entityType = entry.getKey();
        for (final String linkName : entry.getValue()) {
          final EntityIteratorBase it = (EntityIteratorBase) persistentSession.findLinks(entityType, this, linkName).iterator();
          while (it.hasNext()) {
            result.put(linkName, it.nextId());
          }
        }
      }
    }
    if (result.size() == 0) {
      getPersistentEntityInternal().delete();
    }
    return result;
  }

  private static final StandartEventHandler deleteEventHandler = new StandartEventHandler2() {

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

  private static final StandartEventHandler newVersionEventHandler = new StandartEventHandler2() {

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
      ModelMetaData md = ((TransientEntityStore) getStore()).getModelMetaData();
      if (md == null) {
        if (log.isTraceEnabled()) {
          log.trace("Model-meta data is not defined. Use unified link manager for link [" + linkName + "]");
        }
        m = new UnifiedTransientLinksManagerImpl(linkName, this);
      } else {
        switch (md.getEntityMetaData(getType()).getAssociationEndMetaData(linkName).getCardinality()) {
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

  private static final StandartEventHandler hasChangesEventHandler = new StandartEventHandler2() {

    Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
      return true;
    }

    Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
      Map<String, PropertyChange> changesProperties = entity.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(entity);
      Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);

      return (changesLinks != null && !changesLinks.isEmpty()) || (changesProperties != null && !changesProperties.isEmpty());
    }

  };


  public boolean hasChanges() {
    return (Boolean) (hasChangesEventHandler.handle(this, null, null));
  }

  private static final StandartEventHandler<String, Object> hasChangesForPropertyEventHandler = new StandartEventHandler2<String, Object>() {

    Object processOpenNew(AbstractTransientEntity entity, String property, Object param2) {
      return processOpenSaved(entity, property, param2);
    }

    Object processTemporary(AbstractTransientEntity entity, String property, Object param2) {
      return false;
    }

    Object processOpenSaved(AbstractTransientEntity entity, String property, Object param2) {
      Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);
      Map<String, PropertyChange> changesProperties = entity.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(entity);

      return (changesLinks != null && changesLinks.containsKey(property)) ||
              (changesProperties != null && changesProperties.containsKey(property));
    }

  };

  public boolean hasChanges(final String property) {
    return (Boolean) (hasChangesForPropertyEventHandler.handle(this, property, null));
  }

  private static final StandartEventHandler addedRemovedLinksEventHandler = new StandartEventHandler2<String, Boolean>() {

    @NotNull
    Object processOpenNew(AbstractTransientEntity entity, String name, Boolean removed) {
      return EntityIterableBase.EMPTY;
    }

    @NotNull
    Object processOpenSaved(AbstractTransientEntity entity, String name, Boolean removed) {
      Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);

      if (changesLinks != null) {
        LinkChange linkChange = changesLinks.get(name);
        if (linkChange != null) {
          Set<TransientEntity> result = removed ? linkChange.getRemovedEntities() : linkChange.getAddedEntities();
          if (result != null) {
            return new TransientEntityIterable(result);
          }
        }
      }
      return EntityIterableBase.EMPTY;
    }
  };

  public EntityIterable getAddedLinks(final String name) {
    return (EntityIterable) (addedRemovedLinksEventHandler.handle(this, name, false));
  }

  public EntityIterable getRemovedLinks(final String name) {
    return (EntityIterable) (addedRemovedLinksEventHandler.handle(this, name, true));
  }

  private static abstract class StandartEventHandler2<P1, P2> extends StandartEventHandler<P1, P2> {
    TransientEntityImpl _(AbstractTransientEntity entity) {
      return (TransientEntityImpl) entity;
    }
  }

}
