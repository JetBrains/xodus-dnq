package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.ConstantEntityIterableHandle;
import jetbrains.exodus.database.impl.iterate.EntityIterableBase;
import jetbrains.exodus.database.impl.iterate.EntityIteratorBase;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class UniversalEmptyEntityIterable extends EntityIterableBase {

    public static final UniversalEmptyEntityIterable INSTANCE = new UniversalEmptyEntityIterable();

    public UniversalEmptyEntityIterable() {
        super(null);
    }

    @Override
    public EntityIterator iterator() {
        return Iterator.INSTANCE;
    }

    @NotNull
    @Override
    public EntityIterator getIteratorImpl(@NotNull final PersistentStoreTransaction txn) {
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

    public boolean contains(@NotNull Entity entity) {
        return false;
    }

    @NotNull
    @Override
    protected EntityIterableHandle getHandleImpl() {
        return new ConstantEntityIterableHandle(null, EntityIterableType.EMPTY) {
        };
    }

    @Override
    public int indexOf(@NotNull Entity entity) {
        return -1;
    }

    @Override
    protected long countImpl(@NotNull final PersistentStoreTransaction txn) {
        return 0;
    }

    public boolean canBeCached() {
        return false;
    }

    public static class Iterator extends EntityIteratorBase implements EntityIteratorWithPropId {

        public static final Iterator INSTANCE = new Iterator();

        protected Iterator() {
            super(UniversalEmptyEntityIterable.INSTANCE);
        }

        public String currentLinkName() {
            return null;
        }

        @Override
        protected boolean hasNextImpl() {
            return false;
        }

        @Nullable
        @Override
        protected EntityId nextIdImpl() {
            return null;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

}
