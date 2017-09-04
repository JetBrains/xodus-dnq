package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SimplePropertyValidationException extends DataIntegrityViolationException {

    protected String propertyName;

    public SimplePropertyValidationException(String message, String displayMessage, TransientEntity entity, String propertyName) {
        super(message, displayMessage, entity);
        this.propertyName = propertyName;
    }

    public boolean relatesTo(@NotNull TransientEntity entity, @Nullable Object fieldIdent) {
        return super.relatesTo(entity, fieldIdent) && propertyName.equals(fieldIdent);
    }

    public EntityFieldHandler getEntityFieldHandler() {
        return EntityFieldHandler.create(entityId, propertyName);
    }

    public String getPropertyName() {
        return propertyName;
    }
}
