package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.execution.Job;
import com.jetbrains.teamsys.core.execution.ThreadJobProcessorPool;
import com.jetbrains.teamsys.database.TransientEntityChange;
import com.jetbrains.teamsys.database.TransientEntityStore;
import com.jetbrains.teamsys.database.TransientStoreSession;

import java.util.Set;

public class TransientSessionDeferred extends TransientSessionImpl {

    protected TransientSessionDeferred(final TransientEntityStoreImpl store, final String name) {
        super(store, name);
    }

    protected TransientSessionDeferred(final TransientEntityStoreImpl store, final String name, final Object id) {
        super(store, name, id);
    }

    public void commit() {
        TransientStoreUtil.suspend(this);

        new Job(ThreadJobProcessorPool.getOrCreateJobProcessor("TransactionalDeferred " +
                ((TransientEntityStoreImpl) getStore()).getPersistentStore().getLocation())) {

            protected void execute() throws Throwable {
                ((TransientEntityStore)getStore()).resumeSession(TransientSessionDeferred.this);
                try {
                    TransientSessionDeferred.super.commit();
                } catch (Exception e) {
                    TransientStoreUtil.abort(e, TransientSessionDeferred.this);
                    throw e;
                }
            }
            
        };
    }

    public void intermediateCommit() {
        throw new IllegalAccessError("Flush is not allowed in deferred transactions");
    }
}
