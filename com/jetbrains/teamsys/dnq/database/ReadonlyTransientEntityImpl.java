package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.impl.iterate.EntityIterableBase;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

public class ReadonlyTransientEntityImpl extends TransientEntityImpl {
  private Boolean hasChanges = null;
  private Map<String, Boolean> hasChangesForProperty;
  private Map<String, LinkChange> linksDetaled;

  ReadonlyTransientEntityImpl(@NotNull TransientEntityChange change, @NotNull TransientStoreSession session) {
    super(((AbstractTransientEntity)change.getTransientEntity()).getPersistentEntityInternal(), session);

    Map<String,PropertyChange> propertiesDetaled = change.getChangedPropertiesDetaled();

    if (propertiesDetaled != null) {
      for (String propertyName : propertiesDetaled.keySet()) {
        propertiesCache.put(propertyName, propertiesDetaled.get(propertyName).getOldValue());
      }
    }

    this.linksDetaled = change.getChangedLinksDetaled();
  }

  public boolean isReadonly() {
    return true;
  }

  @Override
  public <T extends Comparable> void setProperty(@NotNull String propertyName, @NotNull T value) {
    throw createReadonlyException();
  }

  @Override
  public void setBlob(@NotNull String blobName, @NotNull InputStream blob) {
    throw createReadonlyException();
  }

  @Override
  public void setBlob(@NotNull String blobName, @NotNull File file) {
    throw createReadonlyException();
  }

  @Override
  public void setBlobString(@NotNull String blobName, @NotNull String blobString) {
    throw createReadonlyException();
  }

  @Override
  public void setLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public void addLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public void deleteProperty(@NotNull String propertyName) {
    throw createReadonlyException();
  }

  @Override
  public void deleteBlob(@NotNull String blobName) {
    throw createReadonlyException();
  }

  @Override
  public void deleteLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public void deleteBlobString(@NotNull String blobName) {
    throw createReadonlyException();
  }

  @Override
  public void deleteLinks(@NotNull String linkName) {
    throw createReadonlyException();
  }

    @NotNull
    @Override
    public EntityIterable getLinks(@NotNull String linkName) {
        LinkChange c = linksDetaled.get(linkName);
        EntityIterable result = super.getLinks(linkName);
        if (c != null) {
            Set<TransientEntity> addedEntities = c.getAddedEntities();
            Set<TransientEntity> removedEntities = c.getRemovedEntities();
            if (addedEntities != null) {
                result = result.union(new TransientEntityIterable(addedEntities));
            }
            if (removedEntities != null) {
                result = result.minus(new TransientEntityIterable(removedEntities));
            }
        }
        return result;
    }

    @Override
    public long getLinksSize(@NotNull String linkName) {
        return super.getLinksSize(linkName);    //To change body of overridden methods use File | Settings | File Templates.
    }

    @Override
  public void delete() {
    throw createReadonlyException();
  }

  @Override
  public boolean hasChanges() {
    if (super.hasChanges()) {
      return true;
    } else {
      // lazy hasChanges evaluation
      if (hasChanges == null) evaluateHasChanges();
      return hasChanges;
    }
  }

  @Override
  public boolean hasChanges(final String property) {
    if (super.hasChanges(property)) {
      return true;
    } else {
      // lazy hasChanges evaluation
      if (hasChangesForProperty == null) evaluateHasChangesForProperty();
      Boolean hasChanges = hasChangesForProperty.get(property);
      return hasChanges == null ? false : hasChanges;
    }
  }

    @Override
    public synchronized EntityIterable getAddedLinks(String name) {
        LinkChange c = linksDetaled.get(name);
        if (c != null) {
            Set<TransientEntity> added = c.getAddedEntities();
            if (added != null) {
                return new TransientEntityIterable(added);
            }
        }
        return EntityIterableBase.EMPTY;
    }

    @Override
    public synchronized EntityIterable getRemovedLinks(String name) {
        LinkChange c = linksDetaled.get(name);
        if (c != null) {
            Set<TransientEntity> removed = c.getRemovedEntities();
            if (removed != null) {
                return new TransientEntityIterable(removed);
            }
        }
        return EntityIterableBase.EMPTY;
    }

    private void evaluateHasChanges() {
      boolean hasChanges = false;
      if (linksDetaled != null) {
        for (String linkName : linksDetaled.keySet()) {
            LinkChange linkChange = linksDetaled.get(linkName);
            if (linkChange != null) {
                Set<TransientEntity> addedEntities = linkChange.getAddedEntities();
                Set<TransientEntity> removedEntities = linkChange.getRemovedEntities();
                if (addedEntities != null) {
                    if (addedEntities.size() > 0) {
                        hasChanges = true;
                        break;
                    }
                }
                if (removedEntities != null) {
                    if (removedEntities.size() > 0) {
                        hasChanges = true;
                        break;
                    }
                }
            }
        }
      }
      this.hasChanges = hasChanges;
  }

  private void evaluateHasChangesForProperty() {
      hasChangesForProperty = new HashMap<String, Boolean>();
      if (linksDetaled != null) {
        for (String linkName : linksDetaled.keySet()) {
            LinkChange linkChange = linksDetaled.get(linkName);
            if (linkChange != null) {
                Set<TransientEntity> addedEntities = linkChange.getAddedEntities();
                Set<TransientEntity> removedEntities = linkChange.getRemovedEntities();
                if (addedEntities != null) {
                    if (addedEntities.size() > 0) {
                        hasChangesForProperty.put(linkName, true);
                        continue; // process next link name
                    }
                }
                if (removedEntities != null) {
                    if (removedEntities.size() > 0) {
                        hasChangesForProperty.put(linkName, true);
                        continue; // process next link name
                    }
                }
            }
        }
      }
  }

  private IllegalStateException createReadonlyException() {
    return new IllegalStateException("Entity is readonly.");
  }

    private class ReadonlyTransientLinksManager implements TransientLinksManager {
        private final Set<TransientEntity> links;

        public ReadonlyTransientLinksManager(Set<TransientEntity> links) {
            this.links = links;
        }


        public void setLink(@NotNull TransientEntity target) {
            throw createReadonlyException();
        }

        public Entity getLink() {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        public void addLink(@NotNull TransientEntity entity) {
            throw createReadonlyException();
        }

        public void deleteLink(@NotNull TransientEntity entity) {
            throw createReadonlyException();
        }

        public void deleteLinks() {
            throw createReadonlyException();
        }

        @NotNull
                public EntityIterable getLinks() {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        public long getLinksSize() {
            return 0;
        }

        public void flushed() { /* no body */}
    }
}


