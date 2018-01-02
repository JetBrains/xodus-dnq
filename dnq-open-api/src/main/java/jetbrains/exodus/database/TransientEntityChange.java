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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

public class TransientEntityChange {

    @NotNull
    private final TransientEntity transientEntity;
    @Nullable
    private final Map<String, LinkChange> changedLinksDetailed;
    @Nullable
    private final Set<String> changedProperties;
    @NotNull
    private final EntityChangeType changeType;
    @NotNull
    private final TransientChangesTracker changesTracker;

    public TransientEntityChange(@NotNull TransientChangesTracker changesTracker,
                                 @NotNull TransientEntity transientEntity,
                                 @Nullable Set<String> changedProperties,
                                 @Nullable Map<String, LinkChange> changedLinksDetailed,
                                 @NotNull EntityChangeType changeType) {
        this.changesTracker = changesTracker;
        this.transientEntity = transientEntity;
        this.changedLinksDetailed = changedLinksDetailed;
        this.changedProperties = changedProperties;
        this.changeType = changeType;
    }

    @NotNull
    public EntityChangeType getChangeType() {
        return changeType;
    }

    @NotNull
    public TransientEntity getTransientEntity() {
        return transientEntity;
    }

    @NotNull
    public TransientChangesTracker getChangesTracker() {
        return changesTracker;
    }

    public TransientEntity getSnapshotEntity() {
        return changesTracker.getSnapshotEntity(transientEntity);
    }

    @Deprecated
    public TransientEntity getSnaphotEntity() {
        return changesTracker.getSnapshotEntity(transientEntity);
    }

    @Nullable
    public Map<String, LinkChange> getChangedLinksDetaled() {
        return changedLinksDetailed;
    }

    @Nullable
    public Set<String> getChangedProperties() {
        return changedProperties;
    }

    public String toString() {
        return changeType + ":" + transientEntity;
    }

}
