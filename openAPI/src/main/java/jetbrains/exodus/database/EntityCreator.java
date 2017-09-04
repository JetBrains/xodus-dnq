package jetbrains.exodus.database;

import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class EntityCreator {

    @NotNull
    private final String type;

    public EntityCreator(@NotNull final String type) {
        this.type = type;
    }

    // if the result is not found, new entity will be created
    @Nullable
    public abstract Entity find();

    public abstract void created(@NotNull final Entity entity);

    @NotNull
    public String getType() {
        return type;
    }

}
