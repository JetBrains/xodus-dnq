package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.query.metadata.AssociationEndMetaData;
import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CardinalityViolationException extends DataIntegrityViolationException {

    private String associationEndName;

    public CardinalityViolationException(String message, @NotNull TransientEntity entity, @NotNull String name) {
        super(message, "Value is required", entity);
        this.associationEndName = name;
    }

    public CardinalityViolationException(@NotNull TransientEntity entity, @NotNull AssociationEndMetaData md) {
        super("Cardinality violation for [" + entity + "." + md.getName() + "]. Required cardinality is [" + md.getCardinality().getName() + "]", "Value is required", entity);
        this.associationEndName = md.getName();
    }

    public boolean relatesTo(@NotNull TransientEntity entity, @Nullable Object fieldIdent) {
        return super.relatesTo(entity, fieldIdent) && associationEndName.equals(fieldIdent);
    }

    public EntityFieldHandler getEntityFieldHandler() {
        return EntityFieldHandler.create(entityId, associationEndName);
    }

}
