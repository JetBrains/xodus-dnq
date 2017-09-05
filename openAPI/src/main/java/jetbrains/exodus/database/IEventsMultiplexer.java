package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public interface IEventsMultiplexer {
    void flushed(TransientChangesTracker oldChangesTracker, Set<TransientEntityChange> changesDescription);

    void onClose(TransientEntityStore transientEntityStore);

    void addListener(@NotNull Entity e, @NotNull IEntityListener listener);

    void removeListener(@NotNull Entity e, @NotNull IEntityListener listener);

    void addListener(@NotNull String entityType, @NotNull IEntityListener listener);

    void removeListener(@NotNull String entityType, @NotNull IEntityListener listener);
}
