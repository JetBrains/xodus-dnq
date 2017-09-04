package jetbrains.exodus.textindex;

import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.Nullable;

public interface FieldTextExtractor {

    /**
     * @param entity the entity.
     * @return for the specified entity, text of the field to be indexed.
     */
    @Nullable
    String getText(final Entity entity);
}
