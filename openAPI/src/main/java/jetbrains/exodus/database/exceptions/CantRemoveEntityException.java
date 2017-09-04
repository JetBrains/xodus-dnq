package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class CantRemoveEntityException extends DataIntegrityViolationException {

    private String entityPresentation;
    private Collection<Collection<String>> incomingLinkDescriptions;


    public CantRemoveEntityException(Entity entity, String message, String entityPrsentation, Collection<Collection<String>> descriptions) {
        super(message, message, entity);
        this.entityPresentation = entityPrsentation;
        this.incomingLinkDescriptions = descriptions;
    }

    public String getEntityPresentation() {
        return entityPresentation;
    }

    public Collection<Collection<String>> getCauses() {
        return incomingLinkDescriptions;
    }

    public boolean relatesTo(@NotNull TransientEntity entity, @Nullable Object fieldIdent) {
        return entity.getId().equals(entityId);
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getMessage());
        for (Collection<String> description : incomingLinkDescriptions) {
            for (String line : description) {
                sb.append(line).append(" ");
            }
            sb.append("; ");
        }
        return sb.toString();
    }
}
