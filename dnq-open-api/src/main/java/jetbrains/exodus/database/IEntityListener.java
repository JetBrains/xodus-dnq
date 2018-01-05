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
package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;

public interface IEntityListener<T extends Entity> {
    void addedAsync(@NotNull T added);
    void addedSync(@NotNull T added);
    void addedSyncAfterConstraints(@NotNull T added);
    void addedSyncBeforeConstraints(@NotNull T added);
    void updatedAsync(@NotNull T old, @NotNull T current);
    void updatedSync(@NotNull T old, @NotNull T current);
    void updatedSyncAfterConstraints(@NotNull T old, @NotNull T current);
    void updatedSyncBeforeConstraints(@NotNull T old, @NotNull T current);
    void removedAsync(@NotNull T removed);
    void removedSync(@NotNull T removed);
    void removedSyncAfterConstraints(@NotNull T added);
    void removedSyncBeforeConstraints(@NotNull T removed);

    @Deprecated
    void addedSyncBeforeFlush(@NotNull T added); // use addedSyncAfterConstraints
    @Deprecated
    void updatedSyncBeforeFlush(@NotNull T old, @NotNull T current); // use updatedSyncAfterConstraints
    @Deprecated
    void removedSyncBeforeFlush(@NotNull T removed); // use removedSyncAfterConstraints
}
