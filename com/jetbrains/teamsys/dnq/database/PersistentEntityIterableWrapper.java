package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Wrapper for persistent iterable. Handles iterator.next and delegates it to transient session.
 *
 * @author Vadim.Gurov
 */
class PersistentEntityIterableWrapper implements EntityIterable {

  private static final Log log = LogFactory.getLog(PersistentEntityIterableWrapper.class);

  private EntityIterable wrappedIterable;
  private TransientStoreSession session;

  PersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable, @NotNull TransientStoreSession session) {
    if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
      throw new IllegalArgumentException("Can't wrap transient entity iterbale with another transient entity iterable.");
    }

    this.wrappedIterable = wrappedIterable;
    this.session = session;
  }

  public void dispose() {
    wrappedIterable.dispose();
  }

  public long size() {
    return wrappedIterable.size();
  }

  public long count() {
    return wrappedIterable.count();
  }

  public int indexOf(@NotNull Entity entity) {
    return wrappedIterable.indexOf(entity);
  }

  @NotNull
  public EntityIterableHandle getHandle() {
    return wrappedIterable.getHandle();
  }

  @NotNull
  public EntityIterable intersect(@NotNull EntityIterable right) {
    return new PersistentEntityIterableWrapper(wrappedIterable.intersect(right.getSource()), session);
  }

  @NotNull
  public EntityIterable union(@NotNull EntityIterable right) {
    return new PersistentEntityIterableWrapper(wrappedIterable.union(right.getSource()), session);
  }

  @NotNull
  public EntityIterable minus(@NotNull EntityIterable right) {
    return new PersistentEntityIterableWrapper(wrappedIterable.minus(right.getSource()), session);
  }

  @NotNull
  public EntityIterable concat(@NotNull EntityIterable right) {
    return new PersistentEntityIterableWrapper(wrappedIterable.concat(right.getSource()), session);
  }

  public EntityIterable skip(int number) {
    return new PersistentEntityIterableWrapper(wrappedIterable.skip(number), session);
  }

  @NotNull
  public EntityIterable getSource() {
    return wrappedIterable;
  }

  public EntityIterator iterator() {
    if (log.isTraceEnabled()) {
      log.trace("New iterator requested for persistent iterable wrapper " + this);
    }

    return new PersistentEntityIteratorWrapper(wrappedIterable.iterator(), session);
  }

  public String toString() {
    return super.toString() + " in transient session " + session;
  }
}
