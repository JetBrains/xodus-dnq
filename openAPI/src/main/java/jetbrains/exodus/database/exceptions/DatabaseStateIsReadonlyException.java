package jetbrains.exodus.database.exceptions;

import jetbrains.exodus.entitystore.EntityStoreException;

public class DatabaseStateIsReadonlyException extends EntityStoreException {

    public DatabaseStateIsReadonlyException(String message) {
        super(message);
    }
}
