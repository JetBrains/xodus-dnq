package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.execution.Job;
import com.jetbrains.teamsys.core.execution.ThreadJobProcessorPool;
import com.jetbrains.teamsys.database.TransientEntityChange;

import java.util.Set;

public class TransientSessionDeferred extends TransientSessionImpl {

    protected TransientSessionDeferred(final TransientEntityStoreImpl store, final String name) {
        super(store, name);
    }

    protected TransientSessionDeferred(final TransientEntityStoreImpl store, final String name, final Object id) {
        super(store, name, id);
    }

    public void commit() {
        store.unregisterStoreSession(this);
        new Job(ThreadJobProcessorPool.getOrCreateJobProcessor("TransactionalDeferred " +
                ((TransientEntityStoreImpl) getStore()).getPersistentStore().getLocation())) {
            protected void execute() throws Throwable {
                if (log.isDebugEnabled()) {
                    log.debug("Commit transient session " + this);
                }
                switch (state) {
                    case Open:
                        // flush may produce runtime exceptions. if so - session stays open
                        Set<TransientEntityChange> changes = flush();
                        try {
                            closePersistentSession();
                        } finally {
                            deleteBlobsStore();
                            state = State.Committed;
                            dispose();
                            lock.release();
                        }
                        notifyCommitedListeners(changes);
                        break;

                    default:
                        throw new IllegalArgumentException("Can't commit in state " + state);
                }
            }
        };
    }

    public void intermediateCommit() {
        throw new IllegalAccessError("Flush is not allowed in deferred transactions");
    }
}
