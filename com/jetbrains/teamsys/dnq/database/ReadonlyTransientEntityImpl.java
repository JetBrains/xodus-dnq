package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.EntityIterableBase;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

public class ReadonlyTransientEntityImpl extends TransientEntityImpl {
  private Boolean hasChanges = null;
  private Map<String, LinkChange> linksDetaled;
  private Map<String, PropertyChange> propertiesDetaled;

  ReadonlyTransientEntityImpl(@NotNull TransientEntityChange change, @NotNull TransientStoreSession session) {
    super(((TransientEntityImpl) change.getTransientEntity()).getPersistentEntity(), session);

    this.propertiesDetaled = change.getChangedPropertiesDetaled();
    this.linksDetaled = change.getChangedLinksDetaled();
  }

  public boolean isReadonly() {
    return true;
  }

  @Override
  public boolean setProperty(@NotNull String propertyName, @NotNull Comparable value) {
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
  public boolean setBlobString(@NotNull String blobName, @NotNull String blobString) {
    throw createReadonlyException();
  }

  @Override
  public boolean setLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public boolean addLink(@NotNull String linkName, @NotNull Entity target) {
    throw createReadonlyException();
  }

  @Override
  public boolean deleteProperty(@NotNull String propertyName) {
    throw createReadonlyException();
  }

  @Override
  public boolean deleteBlob(@NotNull String blobName) {
    throw createReadonlyException();
  }

  @Override
  public boolean deleteLink(@NotNull String linkName, @NotNull Entity target) {
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

  @Override
  public Comparable getProperty(@NotNull String propertyName) {
    // return old property value if it was changed
    if (propertiesDetaled != null && propertiesDetaled.containsKey(propertyName)) {
      return propertiesDetaled.get(propertyName).getOldValue();
    } else {
      return super.getProperty(propertyName);
    }
  }

  @Override
  public Entity getLink(@NotNull String linkName) {
    if (linksDetaled != null) {
      LinkChange c = linksDetaled.get(linkName);
      if (c != null) {
        Set<TransientEntity> removedEntities = c.getRemovedEntities();
        if (removedEntities != null) {
          return removedEntities.iterator().next();
        }
      }
    }
    return super.getLink(linkName);
  }

/* TODO: implement it, but union and minus throw exception
  @NotNull
  @Override
  public EntityIterable getLinks(@NotNull String linkName) {
    EntityIterable result = super.getLinks(linkName);

    // add added links to result and and remove removed links
    if (linksDetaled != null) {
      LinkChange c = linksDetaled.get(linkName);
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
    }
    return result;
  }
*/

  @Override
  public long getLinksSize(@NotNull String linkName) {
    return super.getLinksSize(linkName);
  }

  @Override
  public boolean delete() {
    throw createReadonlyException();
  }

  @Override
  public boolean hasChanges() {
    if (super.hasChanges()) {
      return true;
    } else {
      // lazy hasChanges evaluation
      if (hasChanges == null) {
        evaluateHasChanges();
      }
      return hasChanges;
    }
  }

  @Override
  public boolean hasChanges(final String property) {
    if (super.hasChanges(property)) {
      return true;
    } else {
      if (linksDetaled != null && linksDetaled.containsKey(property)) {
        LinkChange change = linksDetaled.get(property);
        return change.getAddedEntitiesSize() > 0 || change.getRemovedEntitiesSize() > 0;
      }

      return propertiesDetaled != null && propertiesDetaled.containsKey(property);
    }
  }

  @Override
  public boolean hasChangesExcepting(String[] properties) {
    if (super.hasChangesExcepting(properties)) {
      return true;
    } else {
      if (linksDetaled != null) {
        if (linksDetaled.size() > properties.length) {
          // by Dirichlet principle, even if 'properties' param is malformed
          return true;
        }
        final Set<String> linksDetaledCopy = new HashSet(linksDetaled.keySet());
        for (String property : properties) {
          linksDetaledCopy.remove(property);
        }
        if (!linksDetaledCopy.isEmpty()) {
          return true;
        }
      }
      if (propertiesDetaled != null) {
        if (propertiesDetaled.size() > properties.length) {
          // by Dirichlet principle, even if 'properties' param is malformed
          return true;
        }
        final Set<String> propertiesDetailedCopy = new HashSet(propertiesDetaled.keySet());
        for (String property : properties) {
          propertiesDetailedCopy.remove(property);
        }
        if (!propertiesDetailedCopy.isEmpty()) {
          return true;
        }
      }
      return false;
    }
  }

  @Override
  public EntityIterable getAddedLinks(String name) {
    if (linksDetaled != null) {
      final LinkChange c = linksDetaled.get(name);
      if (c != null) {
        Set<TransientEntity> added = c.getAddedEntities();

        if (added != null) {
          return new TransientEntityIterable(added) {
            @Override
            public long size() {
              return c.getAddedEntitiesSize();
            }

            @Override
            public long count() {
              return c.getAddedEntitiesSize();
            }
          };
        }
      }
    }
    return EntityIterableBase.EMPTY;
  }

  @Override
  public EntityIterable getRemovedLinks(String name) {
    if (linksDetaled != null) {
      final LinkChange c = linksDetaled.get(name);
      if (c != null) {
        Set<TransientEntity> removed = c.getRemovedEntities();
        if (removed != null) {
          return new TransientEntityIterable(removed) {
            @Override
            public long size() {
              return c.getRemovedEntitiesSize();
            }

            @Override
            public long count() {
              return c.getRemovedEntitiesSize();
            }
          };
        }
      }
    }
    return EntityIterableBase.EMPTY;
  }

    @Override
    public EntityIterable getAddedLinks(Set<String> linkNames) {
      if (linksDetaled != null) {
        return AddedOrRemovedLinksFromSetTransientEntityIterable.get(linksDetaled, linkNames, false);
      }
      return UniversalEmptyEntityIterable.INSTANCE;
    }

    @Override
    public EntityIterable getRemovedLinks(Set<String> linkNames) {
      if (linksDetaled != null) {
        return AddedOrRemovedLinksFromSetTransientEntityIterable.get(linksDetaled, linkNames, true);
      }
      return UniversalEmptyEntityIterable.INSTANCE;
    }

    private void evaluateHasChanges() {
    boolean hasChanges = false;
    if (linksDetaled != null) {
      for (String linkName : linksDetaled.keySet()) {
        LinkChange linkChange = linksDetaled.get(linkName);
        if (linkChange != null) {
          if (linkChange.getAddedEntitiesSize() > 0 || linkChange.getRemovedEntitiesSize() > 0) {
            hasChanges = true;
            break;
          }
        }
      }
    }

    if (propertiesDetaled != null && propertiesDetaled.size() > 0) {
      hasChanges = true;
    }

    this.hasChanges = hasChanges;
  }

  private IllegalStateException createReadonlyException() {
    return new IllegalStateException("Entity is readonly.");
  }

}
