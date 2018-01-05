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

import jetbrains.exodus.database.LinkChange;
import jetbrains.exodus.database.TransientChangesTracker;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityChange;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.PersistentStoreTransaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unchecked")
public class ReadOnlyTransientChangesTrackerImpl implements TransientChangesTracker {
    @Nullable
    private PersistentStoreTransaction snapshot;

    ReadOnlyTransientChangesTrackerImpl(@NotNull PersistentStoreTransaction snapshot) {
        this.snapshot = snapshot;
    }

    @NotNull
    @Override
    public Set<TransientEntityChange> getChangesDescription() {
        return Collections.EMPTY_SET;
    }

    @Override
    @NotNull
    public TransientEntityChange getChangeDescription(@NotNull TransientEntity e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getChangesDescriptionCount() {
        return 0;
    }

    @Nullable
    @Override
    public Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e) {
        return Collections.EMPTY_MAP;
    }

    @Nullable
    @Override
    public Set<String> getChangedProperties(@NotNull TransientEntity e) {
        return Collections.EMPTY_SET;
    }

    @Override
    @NotNull
    public PersistentStoreTransaction getSnapshot() {
        PersistentStoreTransaction snapshot = this.snapshot;
        if (snapshot == null) {
            throw new IllegalStateException("Cannot get persistent store transaction because changes tracker is already disposed");
        }
        return snapshot;
    }

    @Override
    @NotNull
    public TransientEntity getSnapshotEntity(@NotNull TransientEntity e) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public Set<TransientEntity> getChangedEntities() {
        return Collections.EMPTY_SET;
    }

    @Override
    @NotNull
    public Set<TransientEntity> getRemovedEntities() {
        return Collections.EMPTY_SET;
    }

    @Override
    @NotNull
    public Set<String> getAffectedEntityTypes() {
        return Collections.EMPTY_SET;
    }

    @Override
    public boolean isNew(@NotNull TransientEntity e) {
        return false;
    }

    @Override
    public boolean isSaved(@NotNull TransientEntity transientEntity) {
        return true;
    }

    @Override
    public boolean isRemoved(@NotNull TransientEntity transientEntity) {
        return false;
    }

    @Override
    public void linkChanged(@NotNull TransientEntity source, @NotNull String linkName, @NotNull TransientEntity target, @Nullable TransientEntity oldTarget, boolean add) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void linksRemoved(@NotNull TransientEntity source, @NotNull String linkName, @NotNull Iterable<Entity> links) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void propertyChanged(@NotNull TransientEntity e, @NotNull String propertyName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removePropertyChanged(@NotNull TransientEntity e, @NotNull String propertyName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void entityAdded(@NotNull TransientEntity e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void entityRemoved(@NotNull TransientEntity e) {
        throw new UnsupportedOperationException();
    }

    @Override
    @NotNull
    public TransientChangesTracker upgrade() {
        return new TransientChangesTrackerImpl(getSnapshot());
    }

    @Override
    public void dispose() {
        if (snapshot != null) {
            snapshot.abort();
            snapshot = null;
        }
    }
}
