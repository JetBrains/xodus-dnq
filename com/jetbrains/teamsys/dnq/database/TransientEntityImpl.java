package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

/**
 * Date: 05.02.2007
 * Time: 16:34:36
 * <p/>
 * TODO: do not create new handler class on every method call
 * TODO: for blobs implement BlobsManagers, like LinkManager, because there're 3 blob types - String, File, InputStream
 *
 * @author Vadim.Gurov
 */
class TransientEntityImpl extends AbstractTransientEntity {

  private Map<String, TransientLinksManager> linksManagers = null;
  private Map<String, Comparable> propertiesCache = null;
  private Map<String, File> fileBlobsCache = null;

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

  @Nullable
  public <T extends Comparable> T getProperty(@NotNull final String propertyName) {
    return (T) new StandartEventHandler() {
      Object processOpenSaved() {
        if (!getPropertiesCache().containsKey(propertyName)) {
          getPropertiesCache().put(propertyName, getPersistentEntityInternal().getProperty(propertyName));
        }
        return (T) getPropertiesCache().get(propertyName);
      }

      Object processOpenNew() {
        return (T) getPropertiesCache().get(propertyName);
      }

      Object processTemporary() {
        return (T) getPropertiesCache().get(propertyName);
      }

    }.handle();
  }

  public <T extends Comparable> void setProperty(@NotNull final String propertyName,
                                                 @NotNull final T value) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getTransientStoreSession().getTransientChangesTracker().propertyChanged(TransientEntityImpl.this, propertyName, getProperty(propertyName), value);
        getPropertiesCache().put(propertyName, value);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        getPropertiesCache().put(propertyName, value);
        return null;
      }

    }.handle();
  }

  public void deleteProperty(@NotNull final String propertyName) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getTransientStoreSession().getTransientChangesTracker().propertyDeleted(TransientEntityImpl.this, propertyName);
        getPropertiesCache().put(propertyName, null);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        getPropertiesCache().put(propertyName, null);
        return null;
      }

    }.handle();
  }

  @Nullable
  public InputStream getBlob(@NotNull final String blobName) {
    return (InputStream) new StandartEventHandler() {
      Object processOpenSaved() {
        if (!getFileBlobsCache().containsKey(blobName)) {
          //TODO: bad solution - it breaks transaction isolation.
          //TODO: Better solution is to get blob from persistent store only ones and save it somehow in transient session.
          return getPersistentEntityInternal().getBlob(blobName);
        }

        File f = getFileBlobsCache().get(blobName);

        try {
          return f == null ? null : new FileInputStream(f);
        } catch (FileNotFoundException e) {
          throw new RuntimeException(e);
        }
      }

      Object processOpenNew() {
        File f = getFileBlobsCache().get(blobName);

        try {
          return f == null ? null : new FileInputStream(f);
        } catch (FileNotFoundException e) {
          throw new RuntimeException(e);
        }
      }

      Object processTemporary() {
        return processOpenNew();
      }

    }.handle();
  }

  public void setBlob(@NotNull final String blobName, @NotNull final InputStream blob) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        File f = createFile(blob);
        getTransientStoreSession().getTransientChangesTracker().blobChanged(TransientEntityImpl.this, blobName, f);
        getFileBlobsCache().put(blobName, f);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        File f = createFile(blob);
        getFileBlobsCache().put(blobName, f);
        return null;
      }

    }.handle();
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

  public void setBlob(@NotNull final String blobName, @NotNull final File file) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        File f = moveOrCopy(file);
        getTransientStoreSession().getTransientChangesTracker().blobChanged(TransientEntityImpl.this, blobName, f);
        getFileBlobsCache().put(blobName, f);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        File f = moveOrCopy(file);
        getFileBlobsCache().put(blobName, f);
        return null;
      }

    }.handle();
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

  public void deleteBlob(@NotNull final String blobName) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getFileBlobsCache().put(blobName, null);
        getTransientStoreSession().getTransientChangesTracker().blobDeleted(TransientEntityImpl.this, blobName);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        getFileBlobsCache().put(blobName, null);
        return null;
      }

    }.handle();
  }

  @Nullable
  public String getBlobString(@NotNull final String blobName) {
    return (String) new StandartEventHandler() {
      Object processOpenSaved() {
        if (!getPropertiesCache().containsKey(blobName)) {
          getPropertiesCache().put(blobName, getPersistentEntityInternal().getBlobString(blobName));
        }
        return getPropertiesCache().get(blobName);
      }

      Object processOpenNew() {
        return getPropertiesCache().get(blobName);
      }

      Object processTemporary() {
        return processOpenNew();
      }

    }.handle();
  }

  public void setBlobString(@NotNull final String blobName, @NotNull final String blobString) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getTransientStoreSession().getTransientChangesTracker().blobChanged(TransientEntityImpl.this, blobName, blobString);
        getPropertiesCache().put(blobName, blobString);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        getPropertiesCache().put(blobName, blobString);
        return null;
      }

    }.handle();
  }

  public void deleteBlobString(@NotNull final String blobName) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getPropertiesCache().put(blobName, null);
        getTransientStoreSession().getTransientChangesTracker().blobDeleted(TransientEntityImpl.this, blobName);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        getPropertiesCache().put(blobName, null);
        return null;
      }

    }.handle();
  }

  public void addLink(@NotNull final String linkName, @NotNull final Entity target) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getLinksManager(linkName).addLink((TransientEntity) target);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

    }.handle();
  }

  public void setLink(@NotNull final String linkName, @NotNull final Entity target) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getLinksManager(linkName).setLink((TransientEntity) target);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

    }.handle();
  }

  public void deleteLink(@NotNull final String linkName, @NotNull final Entity entity) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getLinksManager(linkName).deleteLink((TransientEntity) entity);
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

    }.handle();
  }

  public void deleteLinks(@NotNull final String linkName) {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getLinksManager(linkName).deleteLinks();
        return null;
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

    }.handle();
  }

  @NotNull
  public EntityIterable getLinks(@NotNull final String linkName) {
    return (EntityIterable) new StandartEventHandler() {
      Object processOpenSaved() {
        return getLinksManager(linkName).getLinks();
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

    }.handle();
  }

  @Nullable
  public Entity getLink(@NotNull final String linkName) {
    return (Entity) new StandartEventHandler() {
      Object processOpenSaved() {
        return getLinksManager(linkName).getLink();
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

    }.handle();
  }

  public long getLinksSize(@NotNull final String linkName) {
    return (Long) new StandartEventHandler() {
      Object processOpenSaved() {
        return getLinksManager(linkName).getLinksSize();
      }

      Object processOpenNew() {
        return getLinksManager(linkName).getLinksSize();
      }

      Object processTemporary() {
        return getLinksManager(linkName).getLinksSize();
      }

    }.handle();
  }

  @NotNull
  public Map<String, EntityId> tryDelete() {
    throw new UnsupportedOperationException("Unsupported operation for transient entity. Use delete()." + TransientEntityImpl.this);
  }

  public void delete() {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getTransientStoreSession().getTransientChangesTracker().entityDeleted(TransientEntityImpl.this);
        setState(State.RemovedSaved);
        return null;
      }

      Object processOpenNew() {
        getTransientStoreSession().getTransientChangesTracker().entityDeleted(TransientEntityImpl.this);
        setState(State.RemovedNew);
        return null;
      }

      Object processTemporary() {
        throw new IllegalStateException("Can't delete temporary entity. " + TransientEntityImpl.this);
      }

    }.handle();
  }

  /**
   * Called by session on session abort
   */
  void rollbackDelete() {
    new StandartEventHandler() {

      Object processOpenSaved() {
        throw new IllegalStateException("Can't rollback delete in current state. " + TransientEntityImpl.this);
      }

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return processOpenSaved();
      }

      Object processOpenRemoved() {
        switch (getState()) {
          case RemovedNew:
            setState(State.New);
            break;

          case RemovedSaved:
            setState(State.Saved);
            break;
        }

        return null;
      }
    }.handle();
  }

  public void newVersion() {
    new StandartEventHandler() {
      Object processOpenSaved() {
        getPersistentEntityInternal().newVersion();
        return null;
      }

      Object processOpenNew() {
        throw new UnsupportedOperationException("Not supported by transient entity in the current state. " + TransientEntityImpl.this);
      }

      Object processTemporary() {
        return processOpenNew();
      }
    }.handle();
  }

  public void markAsTemporary() {
    if (getState() != State.New) {
      throw new IllegalStateException("An entity in the New state only can be marked as temporary.");
    }
    setState(State.Temporary);
  }

  private TransientLinksManager getLinksManager(@NotNull String linkName) {
    TransientLinksManager m = getLinksManagers().get(linkName);

    if (m == null) {
      ModelMetaData md = ((TransientEntityStore) getStore()).getModelMetaData();
      if (md == null) {
        if (log.isTraceEnabled()) {
          log.trace("Model-meta data is not defined. Use unified link manager for link [" + linkName + "]");
        }
        m = new UnifiedTransientLinksManagerImpl(linkName, this);
      } else {
        switch (md.getEntityMetaData(getRealType()).getAssociationEndMetaData(linkName).getCardinality()) {
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

      getLinksManagers().put(linkName, m);
    }

    return m;
  }

  private Map<String, TransientLinksManager> getLinksManagers() {
    if (linksManagers == null) {
      linksManagers = new HashMap<String, TransientLinksManager>();
    }

    return linksManagers;
  }

  private Map<String, Comparable> getPropertiesCache() {
    if (propertiesCache == null) {
      propertiesCache = new HashMap<String, Comparable>();
    }

    return propertiesCache;
  }

  private Map<String, File> getFileBlobsCache() {
    if (fileBlobsCache == null) {
      fileBlobsCache = new HashMap<String, File>();
    }

    return fileBlobsCache;
  }

  /**
   * Is called by session on flush, because all files stored in temp location will be moved to persistent store location.
   */
  void clearFileBlobsCache() {
    getFileBlobsCache().clear();
  }

  /**
   * Notifies links managers about successful flush. Called by transient session
   */
  void updateLinkManagers() {
    for (TransientLinksManager lm : getLinksManagers().values()) {
      lm.flushed();
    }
  }

  public boolean hasChanges() {
    return (Boolean) (new StandartEventHandler() {

      Object processOpenNew() {
        return true;
      }

      Object processTemporary() {
        return false;
      }

      Object processOpenSaved() {
        Set<String> changesLinks = getTransientStoreSession().getTransientChangesTracker().getChangedLinks(TransientEntityImpl.this);
        Set<String> changesProperties = getTransientStoreSession().getTransientChangesTracker().getChangedProperties(TransientEntityImpl.this);
        return (changesLinks != null && !changesLinks.isEmpty()) || (changesProperties != null && !changesProperties.isEmpty());
      }

    }.handle());
  }

  public boolean hasChanges(final String property) {
    return (Boolean) (new StandartEventHandler() {

      Object processOpenNew() {
        return processOpenSaved();
      }

      Object processTemporary() {
        return false;
      }

      Object processOpenSaved() {
        Set<String> changesLinks = getTransientStoreSession().getTransientChangesTracker().getChangedLinks(TransientEntityImpl.this);
        Set<String> changesProperties = getTransientStoreSession().getTransientChangesTracker().getChangedProperties(TransientEntityImpl.this);
        return (changesLinks != null && changesLinks.contains(property)) ||
                (changesProperties != null && changesProperties.contains(property));
      }

    }.handle());
  }

}
