package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 */
abstract class AbstractTransientSession implements TransientStoreSession {
  protected TransientEntityStoreImpl store;
  protected long id;
  protected int flushRetryOnVersionMismatch;

  AbstractTransientSession(final TransientEntityStoreImpl store, final long id) {
    this.store = store;
    this.id = id;
    this.flushRetryOnVersionMismatch = store.getFlushRetryOnLockConflict();
  }

  public void close() {
    throw new UnsupportedOperationException("Unsupported for transient session. Use abort or commit instead.");
  }

    public void setQueryCancellingPolicy(QueryCancellingPolicy policy) {
        getPersistentTransactionInternal().setQueryCancellingPolicy(policy);
    }

    public QueryCancellingPolicy getQueryCancellingPolicy() {
        return getPersistentTransactionInternal().getQueryCancellingPolicy();
    }


    @NotNull
  public StoreTransaction beginTransaction() {
    throw new UnsupportedOperationException("Unsupported for transient session.");
  }

  @NotNull
  public StoreTransaction beginExclusiveTransaction() {
    throw new UnsupportedOperationException("Unsupported for transient session.");
  }

  public void lockForUpdate(@NotNull Iterable<Entity> entities) {
    throw new UnsupportedOperationException("Unsupported for transient session.");
  }

  @NotNull
  public TransientEntityStore getStore() {
    return store;
  }

  public long getId() {
    return id;
  }

    protected StoreTransaction getPersistentTransactionInternal() {
    return store.getPersistentStore().getCurrentTransaction();
  }
}
