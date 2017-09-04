package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;

public interface EntityStoreRefactorings {

    boolean entityTypeExists(@NotNull final String entityTypeName);

    void renameEntityTypeRefactoring(@NotNull final String oldEntityTypeName, @NotNull final String newEntityTypeName);

    void deleteEntityTypeRefactoring(@NotNull final String entityTypeName);

    void deleteEntityRefactoring(@NotNull final Entity entity);

    void deleteLinksRefactoring(@NotNull final Entity entity, @NotNull final String linkName);

    void deleteLinkRefactoring(@NotNull final Entity entity, @NotNull final String linkName, @NotNull final Entity link);
}
