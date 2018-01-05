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
import jetbrains.exodus.entitystore.PersistentStoreTransaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

public interface TransientChangesTracker {

    /**
     * Return description of all changes
     */
    @NotNull
    Set<TransientEntityChange> getChangesDescription();

    /**
     * Return size of the result of getChangesDescription
     */
    int getChangesDescriptionCount();

    /**
     * Return change description for given entity
     */
    @NotNull
    TransientEntityChange getChangeDescription(@NotNull TransientEntity e);

    /**
     * Returns set of changed links for given entity
     */
    @Nullable
    Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e);

    /**
     * Returns set of changed properties for given entity
     */
    @Nullable
    Set<String> getChangedProperties(@NotNull TransientEntity e);

    @NotNull
    PersistentStoreTransaction getSnapshot();

    @NotNull
    TransientEntity getSnapshotEntity(@NotNull TransientEntity e);

    @NotNull
    TransientChangesTracker upgrade();

    void dispose();

    @NotNull
    Set<TransientEntity> getChangedEntities();

    @NotNull
    Set<TransientEntity> getRemovedEntities();

    @NotNull
    Set<String> getAffectedEntityTypes();

    boolean isNew(@NotNull TransientEntity e);

    boolean isSaved(@NotNull TransientEntity transientEntity);

    boolean isRemoved(@NotNull TransientEntity transientEntity);

    void linkChanged(@NotNull TransientEntity source, @NotNull String linkName, @NotNull TransientEntity target, @Nullable TransientEntity oldTarget, boolean add);

    void linksRemoved(@NotNull TransientEntity source, @NotNull String linkName, @NotNull Iterable<Entity> links);

    void propertyChanged(@NotNull TransientEntity e, @NotNull String propertyName);

    void removePropertyChanged(@NotNull TransientEntity e, @NotNull String propertyName);

    void entityAdded(TransientEntity e);

    void entityRemoved(TransientEntity e);
}
