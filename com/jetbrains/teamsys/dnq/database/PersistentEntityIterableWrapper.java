package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.*;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wrapper for persistent iterable. Handles iterator.next and delegates it to transient session.
 *
 * @author Vadim.Gurov
 */
public class PersistentEntityIterableWrapper implements EntityIterableWrapper {

    protected final EntityIterable wrappedIterable;

    public PersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable) {
        if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
            throw new IllegalArgumentException("Can't wrap transient entity iterable with another transient entity iterable.");
        }

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
        return new PersistentEntityIterableWrapper(wrappedIterable.intersect(right.getSource()));
    }

    @NotNull
    public EntityIterable intersectSavingOrder(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(wrappedIterable.intersectSavingOrder(right.getSource()));
    }

    @NotNull
    public EntityIterable union(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(wrappedIterable.union(right.getSource()));
    }

    @NotNull
    public EntityIterable minus(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(wrappedIterable.minus(right.getSource()));
    }

    @NotNull
    public EntityIterable concat(@NotNull EntityIterable right) {
        return new PersistentEntityIterableWrapper(wrappedIterable.concat(right.getSource()));
    }

    public EntityIterable skip(int number) {
        return new PersistentEntityIterableWrapper(wrappedIterable.skip(number));
    }

    public EntityIterable take(int number) {
        return new PersistentEntityIterableWrapper(wrappedIterable.take(number));
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
        return new PersistentEntityIterableWrapper(wrappedIterable.asSortResult());
    }

    @NotNull
    public EntityIterable getSource() {
        return wrappedIterable;
    }

    public EntityIterator iterator() {
        return new PersistentEntityIteratorWrapper(wrappedIterable.iterator(),
                (TransientStoreSession) ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession());
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
