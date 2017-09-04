package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.EntityStoreException;

public class EntityRemovedException extends EntityStoreException {

    private TransientEntity entity;

    /**
     * @param entity transient entity that was removed in database
     */
    public EntityRemovedException(TransientEntity entity) {
        super("Entity [" + entity + "] was removed by you.");
        this.entity = entity;
    }

}
