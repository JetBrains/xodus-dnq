package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

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
  private List<TransientEntity> temporaryLinks;

  MultipleTransientLinksManagerImpl(@NotNull String linkName, TransientEntityImpl owner) {
    this.linkName = linkName;
    this.owner = owner;

    switch (owner.getState()) {
        case Temporary:
        case New:
            this.state = State.LinksLoaded;
            break;
        default:
            this.state = State.LinksNotLoaded;
    }
  }

  public void setLink(@NotNull TransientEntity entity) {
    throw new IllegalStateException("setLink can't be called for multiple link");
  }

  @Nullable
  public Entity getLink() {
    throw new IllegalStateException("getLink can't be called for multiple link");
  }

  private void setAsAdded(final TransientEntity entity) {
      if (removed == null   ||
          !removed.remove(entity)) {

          getAdded().add(entity);
      }
  }

  private void setAsRemoved(final TransientEntity entity) {
      if (added == null   ||
          !added.remove(entity)) {

          getRemoved().add(entity);
      }
  }


  public Set<TransientEntity> getAdded() {
    if (added == null) {
      added = new HashSet<TransientEntity>();
    }

    return added;
  }

  public Set<TransientEntity> getRemoved() {
    if (removed == null) {
      removed = new HashSet<TransientEntity>();
    }

    return removed;
  }

  private void reinitAddedAndRemoved() {
    added = null;
    removed = null;
  }

  private Set<TransientEntity> getLinksSet() {
    if (links == null) {
      links = new HashSet<TransientEntity>();
    }

    return links;
  }

  private List<TransientEntity> getTemporaryLinksList() {
    if (temporaryLinks == null) {
      temporaryLinks = new LinkedList<TransientEntity>();
    }

    return temporaryLinks;
  }

  private void deleteLinkForTemporary(@NotNull final TransientEntity entity) {
    if (temporaryLinks != null) {
      for (Iterator<TransientEntity> i = temporaryLinks.iterator(); i.hasNext(); ) {
        TransientEntity e = i.next();
        if (EntityOperations.equals(e, entity)) {
          i.remove();
          return;
        }
      }
    }
    throw new IllegalArgumentException("Can't find link [" + linkName + "] from [" + owner + "] to [" + entity + "] to remove.");
  }

  public void addLink(@NotNull final TransientEntity entity) {
    switch (owner.getState()) {
      case New:
        getLinksSet().add(entity);
        setAsAdded(entity);
        break;

      case Temporary:
        getTemporaryLinksList().add(entity);
        return;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            setAsAdded(entity);
            break;
          case LinksLoaded:
            getLinksSet().add(entity);
            setAsAdded(entity);
            break;
        }
        break;
    }

    TransientChangesTracker tracker = owner.getTransientStoreSession().getTransientChangesTracker();
    tracker.linkAdded(owner, linkName, entity);
    tracker.registerLinkChanges(owner, linkName, added, removed);
  }

  public void deleteLink(@NotNull final TransientEntity entity) {
    final AbstractTransientEntity.State state = owner.getState();
    switch (state) {
      case New:
        if (links == null || !links.remove(entity)) {
          throw new IllegalArgumentException("Can't find link [" + linkName + "] from [" + owner + "] to [" + entity + "] to remove.");
        }
        setAsRemoved(entity);
        break;

      case Temporary:
        deleteLinkForTemporary(entity);
        return;

      case Saved:
      case SavedNew:
        switch (this.state) {
          case LinksNotLoaded:
            setAsRemoved(entity);
            break;
          case LinksLoaded:
            if (links == null || !links.remove(entity)) {
              throw new IllegalArgumentException("Can't find link [" + linkName + "] from [" + owner + "] to [" + entity + "] to remove.");
            }
            setAsRemoved(entity);
            break;
        }
        break;
    }

    TransientChangesTracker tracker = owner.getTransientStoreSession().getTransientChangesTracker();
    tracker.linkDeleted(owner, linkName, entity);
    tracker.registerLinkChanges(owner, linkName, added, removed);
  }

  public void deleteLinks() {
    // Information about removed links stays inconsistent after method applying
    // Such behavior allows to avoid load of all removed links into memory
    switch (owner.getState()) {
      case New:
        if (links != null) {
          links.clear();
        }
        if (added != null) {
          added.clear();
        }
        return;

      case Temporary:
        if (temporaryLinks != null) {
          temporaryLinks.clear();
        }
        return;

      case Saved:
      case SavedNew:
        if (added != null) {
          added.clear();
        }
        switch (state) {
          case LinksNotLoaded:
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

    owner.getTransientStoreSession().getTransientChangesTracker().linksDeleted(owner, linkName);
  }

  @NotNull
  public EntityIterable getLinks() {
    switch (owner.getState()) {
      case New:
        return new TransientEntityIterable(links == null ? EMPTY : links);

      case Temporary:
        return new TransientEntityIterable(temporaryLinks == null ? EMPTY : new HashSet<TransientEntity>(temporaryLinks));


      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            // if there were no changes for this link - query underlying database
            if ((added == null || added.size() == 0) && (removed == null || removed.size() == 0)) {
              return new PersistentEntityIterableWrapper(owner.getPersistentEntityInternal().getLinks(linkName));
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
          return links == null ? 0 : links.size();

      case Temporary:
          return temporaryLinks == null ? 0 : temporaryLinks.size();

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
            reinitAddedAndRemoved();
            break;

          case LinksLoaded:
            state = State.LinksNotLoaded;
            if (links != null) links.clear();
            reinitAddedAndRemoved();
            break;
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
    }
  }

}
