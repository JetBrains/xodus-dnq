package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.EntityIterableBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.*;

public class ReadonlyTransientEntityImpl extends TransientEntityImpl {
  private Boolean hasChanges = null;
  private Map<String, LinkChange> linksDetaled;
  private Set<String> changedProperties;

  public ReadonlyTransientEntityImpl(@Nullable TransientEntityChange change, @NotNull PersistentEntity snapshot, @NotNull TransientEntityStore store) {
    super(snapshot, store);

    if (change != null) {
      this.changedProperties = change.getChangedProperties();
      this.linksDetaled = change.getChangedLinksDetaled();
    }
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
  public boolean setLink(@NotNull String linkName, @Nullable Entity target) {
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
  public void deleteLinks(@NotNull String linkName) {
    throw createReadonlyException();
  }

  @Override
  public Comparable getProperty(@NotNull String propertyName) {
    return super.getProperty(propertyName);
  }

  @Override
  public Entity getLink(@NotNull String linkName) {
    final PersistentEntity link = (PersistentEntity) persistentEntity.getLink(linkName);
    return link == null ? null : newReadOnlyEntity(link);
  }

  @NotNull
  @Override
  public Iterable<Entity> getLinks(@NotNull String linkName) {
    return new ReadOnlyIterable(persistentEntity.getLinks(linkName));
  }

  @NotNull
  @Override
  public EntityIterable getLinks(@NotNull final Collection<String> linkNames) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean delete() {
    throw createReadonlyException();
  }

  @Override
  public boolean hasChanges() {
    // lazy hasChanges evaluation
    if (hasChanges == null) {
      evaluateHasChanges();
    }
    return hasChanges;
  }

  @Override
  public boolean hasChanges(final String property) {
    if (super.hasChanges(property)) {
      return true;
    } else {
      if (linksDetaled != null && linksDetaled.containsKey(property)) {
        LinkChange change = linksDetaled.get(property);
        return change.getAddedEntitiesSize() > 0 || change.getRemovedEntitiesSize() > 0 || change.getDeletedEntitiesSize() > 0;
      }

      return changedProperties != null && changedProperties.contains(property);
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
      if (changedProperties != null) {
        if (changedProperties.size() > properties.length) {
          // by Dirichlet principle, even if 'properties' param is malformed
          return true;
        }
        final Set<String> propertiesDetailedCopy = new HashSet(changedProperties);
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
          if (linkChange.getAddedEntitiesSize() > 0 || linkChange.getRemovedEntitiesSize() > 0 || linkChange.getDeletedEntitiesSize() > 0) {
            hasChanges = true;
            break;
          }
        }
      }
    }

    if (changedProperties != null && changedProperties.size() > 0) {
      hasChanges = true;
    }

    this.hasChanges = hasChanges;
  }

  private IllegalStateException createReadonlyException() {
    return new IllegalStateException("Entity is readonly.");
  }

  private ReadonlyTransientEntityImpl newReadOnlyEntity(@Nullable PersistentEntity entity) {
    return entity == null ? null : new ReadonlyTransientEntityImpl(null, new ReadOnlyPersistentEntity(persistentEntity.getTxn(), entity.getId()), store);
  }

  private class ReadOnlyIterable implements Iterable<Entity> {

    @NotNull
    private final EntityIterable source;

    public ReadOnlyIterable(@NotNull EntityIterable source) {
      this.source = source;
    }

    @NotNull
    public Iterator<Entity> iterator() {
      return new PersistentIteratorWrapper(source.iterator());
    }
  }

  private class PersistentIteratorWrapper implements Iterator<Entity> {


    private final EntityIterator source;

    private PersistentIteratorWrapper(EntityIterator source) {
      this.source = source;
    }

    @Override
    public boolean hasNext() {
      return source.hasNext();
    }

    @Override
    public Entity next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      PersistentEntity next = (PersistentEntity) source.next();
      return newReadOnlyEntity(next);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}
