package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.execution.Job;
import jetbrains.exodus.core.execution.ThreadJobProcessorPool;

public class TransientSessionDeferred extends TransientSessionImpl {

    protected TransientSessionDeferred(final TransientEntityStoreImpl store) {
        super(store);
    }

    protected TransientSessionDeferred(final TransientEntityStoreImpl store, final Object id) {
        super(store, id);
    }

    public void commit() {
        TransientStoreUtil.suspend(this);

        new Job(ThreadJobProcessorPool.getOrCreateJobProcessor("TransactionalDeferred " +
                getStore().getPersistentStore().getLocation())) {

            protected void execute() throws Throwable {
                getStore().resumeSession(TransientSessionDeferred.this);
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
