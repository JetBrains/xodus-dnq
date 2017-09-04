package jetbrains.exodus.database;

import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface TransientStoreSessionListener {

    /**
     * Called on session flush, only if were changes. Thread session is still available here, but it moved to last database root.
     *
     * @param changedEntities
     */
    void flushed(@Nullable Set<TransientEntityChange> changedEntities);

    /**
     * Before commit or flush, only if were changes.
     *
     * @param changedEntities
     */
    void beforeFlush(@Nullable Set<TransientEntityChange> changedEntities);

    /**
     * Before commit or flush, only if were changes.
     * Is not allowed to have side effects, i.e. make database changes.
     *
     * @param changedEntities
     */
    @Deprecated
    void beforeFlushAfterConstraintsCheck(@Nullable Set<TransientEntityChange> changedEntities);

    /**
     * After constraints if check is failed
     * Is not allowed to have side effects, i.e. make database changes.
     *
     * @param changedEntities
     */
    void afterConstraintsFail(@NotNull Set<DataIntegrityViolationException> exceptions);

}
