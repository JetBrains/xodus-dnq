/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
  Iterable<Entity> getLinks();

  long getLinksSize();

  /**
   * Called after successful flush
   */
  void flushed();

}
