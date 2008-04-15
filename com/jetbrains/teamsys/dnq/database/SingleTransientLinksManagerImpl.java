package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityIterable;
import com.jetbrains.teamsys.database.TransientEntity;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Manages single link of TransinetEntity.
 */
class SingleTransientLinksManagerImpl implements TransientLinksManager {

  private static Log log = LogFactory.getLog(SingleTransientLinksManagerImpl.class);

  private final String linkName;
  private final TransientEntityImpl owner;
  private TransientEntity target = null;
  private State state;

  SingleTransientLinksManagerImpl(@NotNull String linkName, TransientEntityImpl owner) {
    this.linkName = linkName;
    this.owner = owner;
    this.state = State.LinksNotLoaded;
  }

  public void setLink(@NotNull TransientEntity target) {
    switch (owner.getState()) {
      case New:
        this.target = target;
        break;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            this.target = target;
            state = State.LinksLoaded;

          case LinksLoaded:
            this.target = target;
        }
    }

    owner.getTransientStoreSession().getTransientChangesTracker().linkSet(
            owner, linkName, target
    );
  }

  @Nullable
  public Entity getLink() {
    switch (owner.getState()) {
      case New:
        return target;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            Entity e =  owner.getPersistentEntity().getLink(linkName);
            target = e == null ? null : owner.getTransientStoreSession().newEntity(e);
            state = State.LinksLoaded;
            return target;

          case LinksLoaded:
            return target;
        }
    }

    throw new IllegalStateException();
  }

  public void deleteLink(@NotNull TransientEntity entity) {
    deleteLinks();
  }

  public void deleteLinks() {
    switch (owner.getState()) {
      case New:
        target = null;
        break;

      case Saved:
      case SavedNew:
        switch (state) {
          case LinksNotLoaded:
            target = null;
            // do not load links actually, because all of them are removed now
            state = State.LinksLoaded;
            break;

          case LinksLoaded:
            target = null;
            break;
        }
        break;
    }

    owner.getTransientStoreSession().getTransientChangesTracker().linksDeleted(
            owner, linkName);
  }

  public long getLinksSize() {
    switch (owner.getState()) {
      case New:
        return target == null ? 0 : 1;

      case SavedNew:
      case Saved:
        switch (state) {
          case LinksNotLoaded:
            return owner.getPersistentEntityInternal().getLinks(linkName).size();

          case LinksLoaded:
            return target == null ? 0 : 1;
        }
        break;
    }

    throw new IllegalStateException();
  }

  public void flushed() {
  }

  @NotNull
  public EntityIterable getLinks() {
    throw new IllegalStateException("getLinks is not supported for single link");
  }

  public void addLink(@NotNull TransientEntity entity) {
    throw new IllegalStateException("addLink is not supported for single link");
  }
}
