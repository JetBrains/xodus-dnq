package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.query.QueryEngine;
import jetbrains.exodus.query.SortEngine;

public class TransientSortEngineImpl extends SortEngine {

    private TransientEntityStore store;

    public TransientSortEngineImpl() {
    }

    public TransientSortEngineImpl(QueryEngine queryEngine) {
        super(queryEngine);
    }

    public void setEntityStore(TransientEntityStore store) {
        this.store = store;
    }

    @Override
    protected Entity attach(Entity entity) {
        // TODO: seems that it's needed only for compatibility
        final TransientStoreSession threadSession = store.getThreadSession();
        return (threadSession == null ?
                entity :
                threadSession.newEntity(entity)
        );
    }
}
