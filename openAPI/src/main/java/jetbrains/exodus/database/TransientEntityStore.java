package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityStore;
import jetbrains.exodus.query.QueryEngine;
import jetbrains.exodus.query.metadata.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Allows to suspend and resume session.
 */
public interface TransientEntityStore extends EntityStore, EntityStoreRefactorings {

    EntityStore getPersistentStore();

    @NotNull
    @Override
    TransientStoreSession beginReadonlyTransaction();

    TransientStoreSession beginSession();

    @Nullable
    TransientStoreSession getThreadSession();

    @Nullable
    TransientStoreSession suspendThreadSession();

    /**
     * Resumes previously suspened session
     *
     * @param session
     */
    void resumeSession(TransientStoreSession session);

    void setModelMetaData(final ModelMetaData modelMetaData);

    @Nullable
    ModelMetaData getModelMetaData();

    void addListener(TransientStoreSessionListener listener);

    /**
     * Adds listener with a priority.
     * The higher priority the earlier listener will be visited by the TransientEntityStoreImpl.forAllListeners().
     */
    void addListener(TransientStoreSessionListener listener, int priority);

    void removeListener(TransientStoreSessionListener listener);

    QueryEngine getQueryEngine();

    Entity getCachedEnumValue(@NotNull final String className, @NotNull final String propName);

    boolean isOpen();

}
