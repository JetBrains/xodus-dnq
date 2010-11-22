package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.LinkedHashSet;
import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.impl.iterate.AbstractEntityIterable;
import com.jetbrains.teamsys.database.impl.iterate.EntityIterableBase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/**
 * Date: 28.12.2006
 * Time: 13:10:48
 *
 * @author Vadim.Gurov
 */
public class TransientEntityIterable implements EntityIterable {

    private static final Log log = LogFactory.getLog(TransientEntityIterable.class);

    private Set<TransientEntity> values;
    //@NotNull private final EntityIterable source;

    public TransientEntityIterable(@NotNull Set<TransientEntity> values
                                   /*@NotNull final EntityIterable source*/) {
        this.values = values;
        //this.source = source;
    }

    public long size() {
        if (log.isWarnEnabled()) {
            log.warn("Size is requested from TransientEntityIterable!");
        }
        return values.size();
    }

    public long count() {
        if (log.isWarnEnabled()) {
            log.warn("Count is requested from TransientEntityIterable!");
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
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

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

    public EntityIterable take(final int number) {
        if (number == 0) return EntityIterableBase.EMPTY;
        final Iterator<TransientEntity> it = values.iterator();
        final Set<TransientEntity> result = new LinkedHashSet<TransientEntity>();
        for (int i = 0; i < number && it.hasNext(); ++i) {
            result.add(it.next());
        }
        return new TransientEntityIterable(result);
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
        //return source;
    }

    public EntityIterator iterator() {
        if (log.isTraceEnabled()) {
            log.trace("New iterator requested for transient iterable " + this);
        }
        return new TransientEntityIterator(values.iterator());
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean canBeCached() {
        //TODO: revisit EntityIterable interface and remove these stub method
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }

    public AbstractEntityIterable getCachedWrapper() {
        //TODO: revisit EntityIterable interface and remove these stub method
        throw new UnsupportedOperationException("Not supported by TransientEntityIterable");
    }
}
