package jetbrains.exodus.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

public class TransientEntityChange {

    private TransientEntity transientEntity;
    private Map<String, LinkChange> changedLinksDetailed;
    private Set<String> changedProperties;
    private EntityChangeType changeType;
    private TransientChangesTracker changesTracker;

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
