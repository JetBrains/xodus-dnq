package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class OrphanChildException extends DataIntegrityViolationException {

    private Set<String> parents;

    public OrphanChildException(@NotNull TransientEntity entity, @NotNull Set<String> parents) {
        super("Entity [" + entity + "] has no parent, but should have.", null, entity);
        this.parents = parents;
    }

    public boolean relatesTo(@NotNull TransientEntity entity, @Nullable Object fieldIdent) {
        return super.relatesTo(entity, fieldIdent) && parents.contains(fieldIdent);
    }

    public EntityFieldHandler getEntityFieldHandler() {
        return EntityFieldHandler.create(entityId, parents.iterator().next());
    }
}
