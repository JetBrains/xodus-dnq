package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.database.TransientEntity;

public class NullPropertyException extends SimplePropertyValidationException {

    public NullPropertyException(TransientEntity entity, String propertyName) {
        super("Property [" + entity + "." + propertyName + "]" + " can't be empty.", "Value is required", entity, propertyName);
    }

}
