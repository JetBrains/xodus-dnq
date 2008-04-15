package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Created by IntelliJ IDEA.
 * User: vadim
 * Date: Nov 8, 2007
 * Time: 2:34:27 PM
 * To change this template use File | Settings | File Templates.
 */
abstract class AbstractTransientSession implements TransientStoreSession {
  protected TransientEntityStoreImpl store;
  protected Object id;
  protected String name;

  AbstractTransientSession(final TransientEntityStoreImpl store, final String name, final Object id) {
    this.store = store;
    this.name = (name == null || name.length() == 0) ? "unnamed" : name;
    this.id = id;
  }

  public void close() {
    throw new UnsupportedOperationException("Unsupported for transient session. Use abort or commit instead.");
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
  public EntityStore getStore() {
    return store;
  }

  public Object getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  @Nullable
  public StoreTransaction getCurrentTransaction() {
    return this;
  }

  protected StoreSession getPersistentSessionInternal() {
    return store.getPersistentStore().getThreadSession();
  }
}
