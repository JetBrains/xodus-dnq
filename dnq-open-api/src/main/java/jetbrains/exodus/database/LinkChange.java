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

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class LinkChange {

    private String linkName;
    private Set<TransientEntity> addedEntities;
    private Set<TransientEntity> removedEntities;
    private Set<TransientEntity> deletedEntities;

    public LinkChange(@NotNull String linkName) {
        this.linkName = linkName;
    }

    public String getLinkName() {
        return linkName;
    }

    public LinkChangeType getChangeType() {
        final int added = getAddedEntitiesSize();
        final int removed = getRemovedEntitiesSize() + getDeletedEntitiesSize();

        if (added != 0 && removed == 0) return LinkChangeType.ADD;
        if (added == 0 && removed != 0) return LinkChangeType.REMOVE;
        if (added != 0 && removed != 0) return LinkChangeType.ADD_AND_REMOVE;

        throw new IllegalStateException("No added or removed links.");
    }

    public String toString() {
        return linkName + ":" + getChangeType();
    }

    public void addAdded(@NotNull TransientEntity e) {
        if (removedEntities != null) {
            if (removedEntities.remove(e)) return;
        }

        if (addedEntities == null) {
            addedEntities = new HashSet<TransientEntity>();
        }

        addedEntities.add(e);
    }

    public void addRemoved(@NotNull TransientEntity e) {
        if (addedEntities != null) {
            if (addedEntities.remove(e)) return;
        }

        if (removedEntities == null) {
            removedEntities = new HashSet<TransientEntity>();
        }

        removedEntities.add(e);
    }

    public void addDeleted(@NotNull TransientEntity e) {
        if (removedEntities != null) {
            removedEntities.remove(e);
        }

        if (addedEntities != null) {
            addedEntities.remove(e);
        }

        if (deletedEntities == null) {
            deletedEntities = new HashSet<TransientEntity>();
        }

        deletedEntities.add(e);
    }

    @Nullable
    public Set<TransientEntity> getRemovedEntities() {
        return removedEntities;
    }

    @Nullable
    public Set<TransientEntity> getDeletedEntities() {
        return deletedEntities;
    }

    @Nullable
    public Set<TransientEntity> getAddedEntities() {
        return addedEntities;
    }

    public int getAddedEntitiesSize() {
        return addedEntities == null ? 0 : addedEntities.size();
    }

    public int getRemovedEntitiesSize() {
        return removedEntities == null ? 0 : removedEntities.size();
    }

    public int getDeletedEntitiesSize() {
        return deletedEntities == null ? 0 : deletedEntities.size();
    }
}
