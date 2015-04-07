package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wrapper for persistent iterable. Handles iterator.next and delegates it to transient session.
 *
 * @author Vadim.Gurov
 */
public class PersistentEntityIterableWrapper implements EntityIterableWrapper {

    protected final EntityIterable wrappedIterable;
    protected final TransientEntityStore store;

    public PersistentEntityIterableWrapper(@NotNull final TransientEntityStore store, @NotNull EntityIterable wrappedIterable) {
        if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
            throw new IllegalArgumentException("Can't wrap transient entity iterable with another transient entity iterable.");
        }
        this.store = store;
        this.wrappedIterable = wrappedIterable;
    }

    public long size() {
        return wrappedIterable.size();
    }

    public long count() {
        return wrappedIterable.count();
    }

    public long getRoughCount() {
        return wrappedIterable.getRoughCount();
    }

    public long getRoughSize() {
        return wrappedIterable.getRoughSize();
    }

    public int indexOf(@NotNull Entity entity) {
        return wrappedIterable.indexOf(entity);
    }

    public boolean contains(@NotNull Entity entity) {
        return wrappedIterable.contains(entity);
    }

    @NotNull
    public EntityIterableHandle getHandle() {
        return wrappedIterable.getHandle();
    }

    @NotNull
    public EntityIterable intersect(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.intersect(right.getSource()));
    }

    @NotNull
    public EntityIterable intersectSavingOrder(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.intersectSavingOrder(right.getSource()));
    }

    @NotNull
    public EntityIterable union(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.union(right.getSource()));
    }

    @NotNull
    public EntityIterable minus(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.minus(right.getSource()));
    }

    @NotNull
    public EntityIterable concat(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.concat(right.getSource()));
    }

    public EntityIterable skip(int number) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.skip(number));
    }

    public EntityIterable take(int number) {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.take(number));
    }

    @NotNull
    @Override
    public EntityIterable distinct() {
        return wrappedIterable.distinct();
    }

    @NotNull
    @Override
    public EntityIterable selectDistinct(@NotNull String linkName) {
        return wrappedIterable.selectDistinct(linkName);
    }

    @NotNull
    @Override
    public EntityIterable selectManyDistinct(@NotNull String linkName) {
        return wrappedIterable.selectManyDistinct(linkName);
    }

    @Nullable
    @Override
    public Entity getFirst() {
        return wrappedIterable.getFirst();
    }

    @Nullable
    @Override
    public Entity getLast() {
        return wrappedIterable.getLast();
    }

    @NotNull
    @Override
    public EntityIterable reverse() {
        return wrappedIterable.reverse();
    }

    public boolean isSortResult() {
        return wrappedIterable.isSortResult();
    }

    public EntityIterable asSortResult() {
        return new PersistentEntityIterableWrapper(store, wrappedIterable.asSortResult());
    }

    @NotNull
    public EntityIterable getSource() {
        return wrappedIterable;
    }

    public EntityIterator iterator() {
        return new PersistentEntityIteratorWrapper(wrappedIterable.iterator(), store.getThreadSession());
    }

    @NotNull
    @Override
    public StoreTransaction getTransaction() {
        return null;
    }

    public boolean isEmpty() {
        return wrappedIterable.isEmpty();
    }

}
