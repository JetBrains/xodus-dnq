package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

/**
 * Manages single and multiple links TransinetEntity.
 * Should be used when link cardinality is unknown (no meta-data).
 * Main aim is not to load all links unless getLinks method is called.
 */
class UnifiedTransientLinksManagerImpl extends MultipleTransientLinksManagerImpl {

  UnifiedTransientLinksManagerImpl(@NotNull String linkName, TransientEntityImpl owner) {
    super(linkName, owner);
  }

  public void setLink(@NotNull TransientEntity target) {
    deleteLinks();
    addLink(target);
  }

  @Nullable
  public Entity getLink() {
    Iterator<Entity> i = getLinks().iterator();
    return i.hasNext() ? i.next() : null;
  }

}
