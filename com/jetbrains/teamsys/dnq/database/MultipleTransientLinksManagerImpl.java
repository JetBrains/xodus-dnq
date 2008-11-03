package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityIterable;
import com.jetbrains.teamsys.database.TransientEntity;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;

/**
 * Manages link related methods of TransinetEntity.
 * Main aim is not to load all links unless getLinks method is called.
 */
class MultipleTransientLinksManagerImpl implements TransientLinksManager {

  protected static Log log = LogFactory.getLog(MultipleTransientLinksManagerImpl.class);
  private static final Set<TransientEntity> EMPTY = Collections.EMPTY_SET;

  protected State state;
  protected final String linkName;
  protected final TransientEntityImpl owner;
  private Set<TransientEntity> removed;
  private Set<TransientEntity> added;
  private Set<TransientEntity> links;

  MultipleTransientLinksManagerImpl(@NotNull String linkName, TransientEntityImpl owner) {
    this.state = State.LinksNotLoaded;
    this.linkName = linkName;
    this.owner = owner;
  }

  public void setLink(@NotNull TransientEntity entity) {
    throw new IllegalStateException("setLink can't be called for multiple link");
  }

  @Nullable
  public Entity getLink() {
    throw new IllegalStateException("getLink can't be called for multiple link");
  }

  public void addLink(@NotNull final TransientEntity entity) {
    switch (owner.getState()) {
      case New:
        getLinksSet().add(entity);
        break;
      case Temporary:
        getLinksSet().add(entity);
        return;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            if (removed == null || !removed.remove(entity)) {
              getAdded().add(entity);
            }
            break;
          case LinksLoaded:
            getLinksSet().add(entity);
            break;
        }
        break;
    }

    owner.getTransientStoreSession().getTransientChangesTracker().linkAdded(
            owner, linkName, entity);
  }

  private Set<TransientEntity> getAdded() {
    if (added == null) {
      added = new HashSet<TransientEntity>();
    }

    return added;
  }

  private Set<TransientEntity> getRemoved() {
    if (removed == null) {
      removed = new HashSet<TransientEntity>();
    }

    return removed;
  }

  private Set<TransientEntity> getLinksSet() {
    if (links == null) {
      links = new HashSet<TransientEntity>();
    }

    return links;
  }

  public void deleteLink(@NotNull final TransientEntity entity) {
    final AbstractTransientEntity.State state = owner.getState();
    switch (state) {
      case New:
      case Temporary:
        if (links == null || !links.remove(entity)) {
          throw new IllegalArgumentException("Can't find link [" + linkName + "] from [" + owner + "] to [" + entity + "] to remove.");
        }
        if (state == AbstractTransientEntity.State.Temporary) {
          return;
        }
        break;

      case Saved:
      case SavedNew:
        switch (this.state) {
          case LinksNotLoaded:
            if (added == null || !added.remove(entity)) {
              getRemoved().add(entity);
            }
            break;
          case LinksLoaded:
            if (links == null || !links.remove(entity)) {
              throw new IllegalArgumentException("Can't find link [" + linkName + "] from [" + owner + "] to [" + entity + "] to remove.");
            }
            break;
        }
        break;
    }

    owner.getTransientStoreSession().getTransientChangesTracker().linkDeleted(
            owner, linkName, entity);
  }

  public void deleteLinks() {
    switch (owner.getState()) {
      case New:
      case Temporary:
        if (links != null) {
          links.clear();
        }
        return;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            if (added != null) {
              added.clear();
            }
            if (removed != null) {
              removed.clear();
            }
            // do not load links actually, because all of them are removed now
            state = State.LinksLoaded;
            break;
          case LinksLoaded:
            if (links != null) {
              links.clear();
            }
            break;
        }
        break;
    }

    owner.getTransientStoreSession().getTransientChangesTracker().linksDeleted(
            owner, linkName);
  }

  @NotNull
  public EntityIterable getLinks() {
    switch (owner.getState()) {
      case New:
      case Temporary:
        return new TransientEntityIterable(links == null ? EMPTY : links);

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            // if there were no changes for this link - query underlying database
            if ((added == null || added.size() == 0) && (removed == null || removed.size() == 0)) {
              return new PersistentEntityIterableWrapper(owner.getPersistentEntityInternal().getLinks(linkName), owner.getTransientStoreSession());
            } else {
              loadLinksAndMerge();
              state = State.LinksLoaded;
              return new TransientEntityIterable(links == null ? EMPTY : links/*, owner.getPersistentEntityInternal().getLinks(linkName)*/);
            }

          case LinksLoaded:
            return new TransientEntityIterable(links == null ? EMPTY : links/*, owner.getPersistentEntityInternal().getLinks(linkName)*/);
        }
    }

    throw new IllegalStateException();
  }

  public long getLinksSize() {
    switch (owner.getState()) {
      case New:
      case Temporary:
        return links == null ? 0 : links.size();

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            //TODO: error prone code, we don't know if 'removed' contains 'real' records 
            return
                    owner.getPersistentEntityInternal().getLinks(linkName).size() -
                            (removed == null ? 0 : removed.size()) +
                            (added == null ? 0 : added.size());
          case LinksLoaded:
            return links == null ? 0 : links.size();
        }
    }

    throw new IllegalStateException();
  }

  public void flushed() {
    switch (owner.getState()) {
      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            // all changes are saved into database after flush - clear local data about changes
            if (removed != null) {
              removed.clear();
            }
            if (added != null) {
              added.clear();
            }
        }
    }
  }

  /**
   * Merges added and removed liks with loaded links
   */
  private void loadLinksAndMerge() {
    // links may not be empty if there were the following state changes:
    // 1. create new entity
    // 2. add "to n" link (links is not empty)
    // 3. flush (entity becomes SavedNew)
    // 4. add "to n" link
    // 5. get links
    // assert links.isEmpty();

    if (log.isTraceEnabled()) {
      log.trace("Load links [" + linkName + "] of [" + this + "] into memory");
    }

    // TODO: possible bottleneck: load all links into memory
    int i = 0;
    boolean warnReported = false;
    boolean errorReported = false;
    for (final Entity persistent : owner.getPersistentEntityInternal().getLinks(linkName)) {
      TransientEntity te = owner.getTransientStoreSession().newEntity(persistent);

      if (removed == null || !removed.contains(te)) {
        getLinksSet().add(te);
      }

      if (i++ > 100 && !warnReported) {
        if (log.isWarnEnabled()) {
          log.warn("Possible slow code. Loading more then 100 links [" + linkName + "] of [" + this + "]");
        }
        warnReported = true;
      }
      if (i > 1000 && !errorReported) {
        if (log.isErrorEnabled()) {
          log.error("Possible incorrect code. Loading more then 1000 links [" + linkName + "] of [" + this + "]");
        }
        errorReported = true;
      }
    }

    // add added links
    if (added != null) {
      getLinksSet().addAll(added);
      added.clear();
    }
    if (removed != null) {
      removed.clear();
    }
  }

}
