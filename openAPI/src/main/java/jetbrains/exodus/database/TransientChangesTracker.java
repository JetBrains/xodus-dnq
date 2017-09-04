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
     *
     * @return
     */
    @NotNull
    Set<TransientEntityChange> getChangesDescription();

    /**
     * Return size of the result of getChangesDescription
     *
     * @return
     */
    int getChangesDescriptionCount();

    /**
     * Return change description for given entity
     *
     * @param e
     * @return
     */
    TransientEntityChange getChangeDescription(TransientEntity e);

    /**
     * Returns set of changed links for given entity
     *
     * @param e
     * @return
     */
    @Nullable
    Map<String, LinkChange> getChangedLinksDetailed(@NotNull TransientEntity e);

    /**
     * Returns set of changed properties for given entity
     *
     * @param e
     * @return
     */
    @Nullable
    Set<String> getChangedProperties(@NotNull TransientEntity e);

    PersistentStoreTransaction getSnapshot();

    TransientEntity getSnapshotEntity(TransientEntity e);

    TransientChangesTracker upgrade();

    void dispose();

    Set<TransientEntity> getChangedEntities();

    Set<TransientEntity> getRemovedEntities();

    Set<String> getAffectedEntityTypes();

    boolean isNew(TransientEntity e);

    boolean isSaved(TransientEntity transientEntity);

    boolean isRemoved(TransientEntity transientEntity);

    void linkChanged(TransientEntity source, String linkName, TransientEntity target, TransientEntity oldTarget, boolean add);

    void linksRemoved(TransientEntity source, String linkName, Iterable<Entity> links);

    void propertyChanged(TransientEntity e, String propertyName);

    void removePropertyChanged(TransientEntity e, String propertyName);

    void entityAdded(TransientEntity e);

    void entityRemoved(TransientEntity e);
}
