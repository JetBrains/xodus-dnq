/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.iterate.EntityIterableBase;
import jetbrains.exodus.entitystore.iterate.EntityIteratorBase;
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId;
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
        return EntityIterableBase.EMPTY.getHandle();
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

    @NotNull
    @Override
    public EntityIterableBase getSource() {
        return EntityIterableBase.EMPTY;
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

        @Override
        public boolean shouldBeDisposed() {
            return false;
        }
    }

}
