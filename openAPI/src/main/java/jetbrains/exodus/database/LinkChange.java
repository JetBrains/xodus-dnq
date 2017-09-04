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
