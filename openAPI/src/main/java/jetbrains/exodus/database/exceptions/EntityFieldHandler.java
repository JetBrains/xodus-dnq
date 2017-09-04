package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.entitystore.EntityId;
import org.jetbrains.annotations.NotNull;

public class EntityFieldHandler {

    private EntityId entityId;
    private String fieldName;

    private EntityFieldHandler(@NotNull EntityId entityId, String fieldName) {
        this.entityId = entityId;
        this.fieldName = fieldName;
    }

    public EntityId getEntityId() {
        return entityId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public int hashCode() {
        return fieldName == null ? entityId.hashCode() : (entityId.hashCode() ^ fieldName.hashCode());
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof EntityFieldHandler)) {
            return false;
        }

        return ((EntityFieldHandler) obj).entityId.equals(this.entityId) &&
                (((EntityFieldHandler) obj).fieldName != null &&
                        ((EntityFieldHandler) obj).fieldName.equals(this.fieldName));
    }

    public static EntityFieldHandler create(@NotNull EntityId entityId, String fieldName) {
        return new EntityFieldHandler(entityId, fieldName);
    }

}
