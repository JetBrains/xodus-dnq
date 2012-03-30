package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.ConstantEntityIterableHandle;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
import org.jetbrains.annotations.NotNull;

public class UniversalEmptyEntityIterable implements EntityIterable {

    public static final UniversalEmptyEntityIterable INSTANCE = new UniversalEmptyEntityIterable();

    public EntityIterator iterator() {
        return Iterator.INSTANCE;
    }

    public boolean isEmpty() {
        return true;
    }

    public long size() {
        return 0;
    }

    public long count() {
        return 0;
    }

    public long getRoughCount() {
        return 0;
    }

    public int indexOf(@NotNull Entity entity) {
        return -1;
    }

    public boolean contains(@NotNull Entity entity) {
        return false;
    }

    @NotNull
    public EntityIterableHandle getHandle() {
        //noinspection EmptyClass
        return new ConstantEntityIterableHandle(null, EntityIterableType.EMPTY) {
        };
    }

    @NotNull
    public EntityIterable intersect(@NotNull EntityIterable right) {
        return this;
    }

    @NotNull
    public EntityIterable intersectSavingOrder(@NotNull EntityIterable right) {
        return this;
    }

    @NotNull
    public EntityIterable union(@NotNull EntityIterable right) {
        return right;
    }

    @NotNull
    public EntityIterable minus(@NotNull EntityIterable right) {
        return this;
    }

    @NotNull
    public EntityIterable concat(@NotNull EntityIterable right) {
        return right;
    }

    public EntityIterable skip(int number) {
        return this;
    }

    public EntityIterable take(int number) {
        return this;
    }

    public boolean isSortResult() {
        return true;
    }

    public EntityIterable asSortResult() {
        return this;
    }

    @NotNull
    public EntityIterable getSource() {
        return this;
    }

    public static class Iterator implements EntityIteratorWithPropId {
        public static final Iterator INSTANCE = new Iterator();

        public String currentLinkName() {
            return null;
        }

        public boolean skip(int number) {
            return false;
        }

        public EntityId nextId() {
            return null;
        }

        public boolean dispose() {
            return false;
        }

        public boolean shouldBeDisposed() {
            return false;
        }

        public boolean hasNext() {
            return false;
        }

        public Entity next() {
            return null;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

}
