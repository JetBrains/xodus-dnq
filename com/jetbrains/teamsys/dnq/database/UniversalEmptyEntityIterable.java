package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import jetbrains.exodus.database.impl.iterate.AbstractEntityIterable;
import jetbrains.exodus.database.impl.iterate.ConstantEntityIterableHandle;
import jetbrains.exodus.database.impl.iterate.EntityIterableBase;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class UniversalEmptyEntityIterable extends EntityIterableBase {

    public static final UniversalEmptyEntityIterable INSTANCE = new UniversalEmptyEntityIterable();

    public UniversalEmptyEntityIterable() {
        super(null);
    }

    @NotNull
    @Override
    protected EntityIterator getIteratorImpl() {
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
    protected long countImpl() {
        return 0;
    }

    public boolean canBeCached() {
        return false;
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
