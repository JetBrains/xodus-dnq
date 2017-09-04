package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.query.metadata.Index;
import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;

public class UniqueIndexIntegrityException extends DataIntegrityViolationException {

    public UniqueIndexIntegrityException(@NotNull TransientEntity entity, @NotNull Index index, @NotNull Throwable cause) {
        super("Index [" + index + "]" + " is corrupted.", entity, cause);
    }
}
