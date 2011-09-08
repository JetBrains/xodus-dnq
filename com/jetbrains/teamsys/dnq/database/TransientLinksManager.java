package com.jetbrains.teamsys.dnq.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.EntityIterable;
import jetbrains.exodus.database.TransientEntity;

import java.util.Set;

/**
 */
public interface TransientLinksManager {

  enum State {

    LinksNotLoaded("LinksNotLoaded"),
    LinksLoaded("LinksLoaded");

    private String name;

    State(String name) {
      this.name = name;
    }
  }

  void setLink(@NotNull TransientEntity target);

  @Nullable
  Entity getLink();

  void addLink(@NotNull TransientEntity entity);

  void deleteLink(@NotNull TransientEntity entity);

  void deleteLinks();

  Set<TransientEntity> getAdded();

  Set<TransientEntity> getRemoved();

  @NotNull
  EntityIterable getLinks();

  long getLinksSize();

  /**
   * Called after successful flush
   */
  void flushed();

}
