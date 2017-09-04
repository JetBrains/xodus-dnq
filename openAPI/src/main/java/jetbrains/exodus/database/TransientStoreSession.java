package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.StoreTransaction;
import org.jetbrains.annotations.NotNull;

//TODO: rename to TransientStoreTransaction
public interface TransientStoreSession extends StoreTransaction {

    @NotNull
    TransientEntityStore getStore();

    /**
     * Retruns internal persistent session
     *
     * @return
     * @throws IllegalStateException
     */
    @NotNull
    StoreTransaction getPersistentTransaction() throws IllegalStateException;

    /**
     * Returns changes tracker
     *
     * @return
     * @throws IllegalStateException
     */
    @NotNull
    TransientChangesTracker getTransientChangesTracker() throws IllegalStateException;

    /**
     * Creates wrapper for persistent iterable
     *
     * @param wrappedIterable
     * @return
     */
    @NotNull
    EntityIterable createPersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable);

    @NotNull
    TransientEntity newEntity(@NotNull final String entityType);

    /**
     * Creates new wrapper for persistent entity
     *
     * @param persistentEntity
     * @return
     */
    @NotNull
    TransientEntity newEntity(@NotNull final Entity persistentEntity);

    @NotNull
    TransientEntity newEntity(@NotNull final EntityCreator creator);

    /**
     * Used by dnq to create session local copies of transient entities that come from another session
     *
     * @param entity
     * @return
     */
    @NotNull
    TransientEntity newLocalCopy(@NotNull final TransientEntity entity);

    boolean hasChanges();

    /**
     * Checks if entity entity was removed in this transaction or in database
     *
     * @param entity
     * @return true if e was removed, false if it wasn't removed at all
     */
    boolean isRemoved(@NotNull final Entity entity);

    /**
     * True if session is opened
     *
     * @return
     */
    boolean isOpened();

    /**
     * True if session is commited
     *
     * @return
     */
    boolean isCommitted();

    /**
     * True if session is aborted
     *
     * @return
     */
    boolean isAborted();

    /**
     * Flushes transation without checking any constraints, without saving history and without versions check.
     */
    void quietIntermediateCommit();

    void setUpgradeHook(Runnable hook);
}
