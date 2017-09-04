package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;

public class UserConstraintValidationException extends DataIntegrityViolationException {

    public UserConstraintValidationException(@NotNull String message, @NotNull TransientEntity entity) {
        super(message, null, entity);
    }

    public UserConstraintValidationException(@NotNull String message) {
        super(message);
    }

}
