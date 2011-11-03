package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 */
abstract class AbstractTransientSession implements TransientStoreSession {
  protected TransientEntityStoreImpl store;
  protected Object id;
  protected int flushRetryOnLockConflict;

  AbstractTransientSession(final TransientEntityStoreImpl store, final Object id) {
    this.store = store;
    this.id = id;
    this.flushRetryOnLockConflict = store.getFlushRetryOnLockConflict();
  }

  public void close() {
    throw new UnsupportedOperationException("Unsupported for transient session. Use abort or commit instead.");
  }

    public void setQueryCancellingPolicy(QueryCancellingPolicy policy) {
        getPersistentSessionInternal().setQueryCancellingPolicy(policy);
    }

    public QueryCancellingPolicy getQueryCancellingPolicy() {
        return getPersistentSessionInternal().getQueryCancellingPolicy();
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

  public Object getId() {
    return id;
  }

  @Nullable
  public StoreTransaction getCurrentTransaction() {
    return this;
  }

  protected StoreSession getPersistentSessionInternal() {
    return store.getPersistentStore().getThreadSession();
  }
}
