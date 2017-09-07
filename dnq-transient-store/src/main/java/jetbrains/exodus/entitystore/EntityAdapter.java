/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore;

import jetbrains.exodus.database.IEntityListener;

public abstract class EntityAdapter<T extends Entity> implements IEntityListener<T> {
  public EntityAdapter() {
  }

  public void addedAsync(T added) {
  }

  public void addedSync(T added) {
  }

  public void addedSyncBeforeFlush(T added) {
  }

  public void addedSyncBeforeConstraints(T added) {
  }

  public void updatedAsync(T old, T current) {
  }

  public void updatedSync(T old, T current) {
  }

  public void updatedSyncBeforeFlush(T old, T current) {
  }

  public void updatedSyncBeforeConstraints(T old, T current) {
  }

  public void removedAsync(T removed) {
  }

  public void removedSync(T removed) {
  }

  public void removedSyncBeforeFlush(T removed) {
  }

  public void removedSyncBeforeConstraints(T removed) {
  }
}
