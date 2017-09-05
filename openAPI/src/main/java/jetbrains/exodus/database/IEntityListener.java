package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;

public interface IEntityListener<T extends Entity> {
    void addedAsync(T added);
    void addedSync(T added);
    void addedSyncBeforeFlush(T added);
    void addedSyncBeforeConstraints(T added);
    void updatedAsync(T old, T current);
    void updatedSync(T old, T current);
    void updatedSyncBeforeFlush(T old, T current);
    void updatedSyncBeforeConstraints(T old, T current);
    void removedAsync(T removed);
    void removedSync(T removed);
    void removedSyncBeforeFlush(T removed);
    void removedSyncBeforeConstraints(T removed);
}
