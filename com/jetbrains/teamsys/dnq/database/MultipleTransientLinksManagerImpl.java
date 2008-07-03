package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityIterable;
import com.jetbrains.teamsys.database.TransientEntity;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Manages link related methods of TransinetEntity.
 * Main aim is not to load all links unless getLinks method is called.
 */
class MultipleTransientLinksManagerImpl implements TransientLinksManager {

  protected static Log log = LogFactory.getLog(MultipleTransientLinksManagerImpl.class);

  protected State state;
  protected final String linkName;
  protected final TransientEntityImpl owner;
  protected Set<TransientEntity> removed = new HashSet<TransientEntity>();
  protected Set<TransientEntity> added = new HashSet<TransientEntity>();
  protected Set<TransientEntity> links = new HashSet<TransientEntity>();

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
        links.add(entity);
        break;
      case Temporary:
        links.add(entity);
        return;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            if (!removed.remove(entity)) {
              added.add(entity);
            }
            break;
          case LinksLoaded:
            links.add(entity);
            break;
        }
        break;
    }

    owner.getTransientStoreSession().getTransientChangesTracker().linkAdded(
            owner, linkName, entity);
  }

  public void deleteLink(@NotNull final TransientEntity entity) {
    final AbstractTransientEntity.State state = owner.getState();
    switch (state) {
      case New:
      case Temporary:
        if (!links.remove(entity)) {
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
            if (!added.remove(entity)) {
              removed.add(entity);
            }
            break;
          case LinksLoaded:
            if (!links.remove(entity)) {
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
        links.clear();
        break;
      case Temporary:
        links.clear();
        return;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            added.clear();
            removed.clear();
            // do not load links actually, because all of them are removed now
            state = State.LinksLoaded;
            break;
          case LinksLoaded:
            links.clear();
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
        return new TransientEntityIterable(links /*, EntityIterableBase.EMPTY*/);

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            // if there were no changes for this link - query underlying database
            if (added.size() == 0 && removed.size() == 0) {
              return new PersistentEntityIterableWrapper(owner.getPersistentEntityInternal().getLinks(linkName), owner.getTransientStoreSession());
            } else {
              loadLinksAndMerge();
              state = State.LinksLoaded;
              return new TransientEntityIterable(links/*, owner.getPersistentEntityInternal().getLinks(linkName)*/);
            }

          case LinksLoaded:
            return new TransientEntityIterable(links/*, owner.getPersistentEntityInternal().getLinks(linkName)*/);
        }
    }

    throw new IllegalStateException();
  }

  public long getLinksSize() {
    switch (owner.getState()) {
      case New:
      case Temporary:
        return links.size();

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            //TODO: error prone code, we don't know if 'removed' contains 'real' records 
            return owner.getPersistentEntityInternal().getLinks(linkName).size() - removed.size() + added.size();
          case LinksLoaded:
            return links.size();
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
            removed.clear();
            added.clear();
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

      if (!removed.contains(te)) {
        links.add(te);
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
    links.addAll(added);
    added.clear();
    removed.clear();
  }

}
