package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.core.dataStructures.hash.LinkedHashSet;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.iterate.EntityIterableBase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/**
 * Date: 28.12.2006
 * Time: 13:10:48
 *
 * @author Vadim.Gurov
 */
public class TransientEntityIterable implements EntityIterableWrapper {

    private static final Log log = LogFactory.getLog(TransientEntityIterable.class);

    protected final Set<TransientEntity> values;
    //@NotNull private final EntityIterable source;

    public TransientEntityIterable(@NotNull Set<TransientEntity> values
                                   /*@NotNull final EntityIterable source*/) {
        this.values = values;
        //this.source = source;
    }

    public long size() {
        if (log.isWarnEnabled()) {
            log.warn("size() is requested from TransientEntityIterable!");
        }
        return values.size();
    }

    public long count() {
        if (log.isWarnEnabled()) {
            log.warn("count() is requested from TransientEntityIterable!");
        }
        return values.size();
    }

    public long getRoughCount() {
        if (log.isWarnEnabled()) {
            log.warn("getRoughCount() is requested from TransientEntityIterable!");
        }
        return values.size();
    }

    public long getRoughSize() {
        if (log.isWarnEnabled()) {
            log.warn("getRoughCount() is requested from TransientEntityIterable!");
        }
        return values.size();
    }

    public int indexOf(@NotNull Entity entity) {
        return Arrays.asList(values.toArray(new Entity[values.size()])).indexOf(entity);
    }

    public boolean contains(@NotNull Entity entity) {
        return values.contains(entity);
    }

    @NotNull
    public EntityIterableHandle getHandle() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
        //return source.getHandle();
    }

    @NotNull
    public EntityIterable intersect(@NotNull EntityIterable right) {
        //return source.intersect(right);
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    public EntityIterable intersectSavingOrder(@NotNull EntityIterable right) {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    public EntityIterable union(@NotNull EntityIterable right) {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    public EntityIterable minus(@NotNull EntityIterable right) {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    public EntityIterable concat(@NotNull EntityIterable right) {
        if (!(right instanceof TransientEntityIterable)) {
            throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
        };
        final HashSet<TransientEntity> result = new HashSet<TransientEntity>();
        result.addAll(values);
        result.addAll(((TransientEntityIterable)right).values);
        return new TransientEntityIterable(result);
    }

    @NotNull
    public EntityIterable skip(final int number) {
        if (number == 0) return this;
        final Iterator<TransientEntity> it = values.iterator();
        final Set<TransientEntity> result = new LinkedHashSet<TransientEntity>();
        for (int i = 0; i < number && it.hasNext(); ++i) {
            it.next();
        }
        while (it.hasNext()) {
            result.add(it.next());
        }
        return new TransientEntityIterable(result);
    }

    @NotNull
    public EntityIterable take(final int number) {
        if (number == 0) return EntityIterableBase.EMPTY;
        final Iterator<TransientEntity> it = values.iterator();
        final Set<TransientEntity> result = new LinkedHashSet<TransientEntity>();
        for (int i = 0; i < number && it.hasNext(); ++i) {
            result.add(it.next());
        }
        return new TransientEntityIterable(result);
    }

    @NotNull
    @Override
    public EntityIterable distinct() {
        return this;
    }

    @NotNull
    @Override
    public EntityIterable selectDistinct(@NotNull String linkName) {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    @Override
    public EntityIterable selectManyDistinct(@NotNull String linkName) {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @Nullable
    @Override
    public Entity getFirst() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @Nullable
    @Override
    public Entity getLast() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    @Override
    public EntityIterable reverse() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    public boolean isSortResult() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    public EntityIterable asSortResult() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    @NotNull
    public EntityIterable getSource() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    public EntityIterator iterator() {
        if (log.isTraceEnabled()) {
            log.trace("New iterator requested for transient iterable " + this);
        }
        return new TransientEntityIterator(values.iterator());
    }

    @NotNull
    @Override
    public StoreTransaction getTransaction() {
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
