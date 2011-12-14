package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.Priority;
import jetbrains.exodus.core.execution.Job;
import jetbrains.exodus.database.async.EntityStoreSharedAsyncProcessor;

public class TransientSessionDeferred extends TransientSessionImpl {

    protected TransientSessionDeferred(final TransientEntityStoreImpl store) {
        super(store);
    }

    protected TransientSessionDeferred(final TransientEntityStoreImpl store, final long id) {
        super(store, id);
    }

    public void commit() {
        TransientStoreUtil.suspend(this);
        EntityStoreSharedAsyncProcessor.getInstance().queue(new Job() {
            @Override
            protected void execute() throws Throwable {
                getStore().resumeSession(TransientSessionDeferred.this);
                try {
                    TransientSessionDeferred.super.commit();
                } catch (Exception e) {
                    TransientStoreUtil.abort(e, TransientSessionDeferred.this);
                    throw e;
                }
            }
        }, Priority.below_normal);
    }

    public void intermediateCommit() {
        throw new IllegalAccessError("Flush is not allowed in deferred transactions");
    }
}
