/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore;

import jetbrains.exodus.core.dataStructures.hash.HashMap;
import jetbrains.exodus.core.execution.JobProcessor;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.exodus.query.metadata.EntityMetaData;
import jetbrains.exodus.query.metadata.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class EventsMultiplexer implements TransientStoreSessionListener, IEventsMultiplexer {
    private static final ExceptionHandlerImpl EX_HANDLER = new ExceptionHandlerImpl();
    static Logger logger = LoggerFactory.getLogger(EventsMultiplexer.class);

    private Map<FullEntityId, Queue<IEntityListener>> instanceToListeners = new HashMap<>();
    private Map<String, Queue<IEntityListener>> typeToListeners = new HashMap<>();
    private ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private boolean open = true;
    @Nullable
    private final JobProcessor eventsMultiplexerJobProcessor;

    public EventsMultiplexer() {
        this(null);
    }

    public EventsMultiplexer(@Nullable JobProcessor eventsMultiplexerJobProcessor) {
        this.eventsMultiplexerJobProcessor = eventsMultiplexerJobProcessor;
    }

    public void flushed(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changes) {
        // do nothing. actual job is in flushed(changesTracker)
    }

    /**
     * Called directly by transient session
     *
     * @param changesTracker changes tracker to dispose after async job
     */
    public void flushed(@NotNull TransientStoreSession session, @NotNull TransientChangesTracker changesTracker, @Nullable Set<TransientEntityChange> changes) {
        this.fire(session.getStore(), Where.SYNC_AFTER_FLUSH, changes);
        this.asyncFire(session, changes, changesTracker);
    }

    public void beforeFlushBeforeConstraints(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changes) {
        this.fire(session.getStore(), Where.SYNC_BEFORE_FLUSH_BEFORE_CONSTRAINTS, changes);
    }

    public void beforeFlushAfterConstraints(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changes) {
        this.fire(session.getStore(), Where.SYNC_BEFORE_FLUSH_AFTER_CONSTRAINTS, changes);
    }

    public void afterConstraintsFail(@NotNull TransientStoreSession session, @NotNull Set<DataIntegrityViolationException> exceptions) {
    }

    private void asyncFire(@NotNull TransientStoreSession session, final Set<TransientEntityChange> changes, TransientChangesTracker changesTracker) {
        JobProcessor asyncJobProcessor = getAsyncJobProcessor();
        if (asyncJobProcessor == null) {
            changesTracker.dispose();
        } else {
            asyncJobProcessor.queue(new EventsMultiplexerJob(session.getStore(), this, changes, changesTracker));
        }
    }

    void fire(TransientEntityStore store, Where where, Set<TransientEntityChange> changes) {
        for (TransientEntityChange c : changes) {
            this.handlePerEntityChanges(where, c);
            this.handlePerEntityTypeChanges(store, where, c);
        }
    }

    public void addListener(@NotNull Entity e, @NotNull IEntityListener listener) {
        if (((TransientEntity) e).isNew()) {
            throw new IllegalStateException("Entity is not saved into database - you can't listern to it.");
        }
        final FullEntityId id = new FullEntityId(e.getStore(), e.getId());
        this.rwl.writeLock().lock();
        try {
            if (open) {
                Queue<IEntityListener> listeners = this.instanceToListeners.get(id);
                if (listeners == null) {
                    listeners = new ConcurrentLinkedQueue<>();
                    this.instanceToListeners.put(id, listeners);
                }
                listeners.add(listener);
            }
        } finally {
            this.rwl.writeLock().unlock();
        }
    }

    public void removeListener(@NotNull Entity e, @NotNull IEntityListener listener) {
        final FullEntityId id = new FullEntityId(e.getStore(), e.getId());
        this.rwl.writeLock().lock();
        try {
            final Queue<IEntityListener> listeners = this.instanceToListeners.get(id);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.size() == 0) {
                    this.instanceToListeners.remove(id);
                }
            }
        } finally {
            this.rwl.writeLock().unlock();
        }
    }

    public void addListener(@NotNull String entityType, @NotNull IEntityListener listener) {
        //  ensure that this code will be executed outside of transaction
        this.rwl.writeLock().lock();
        try {
            if (open) {
                Queue<IEntityListener> listeners = this.typeToListeners.get(entityType);
                if (listeners == null) {
                    listeners = new ConcurrentLinkedQueue<>();
                    this.typeToListeners.put(entityType, listeners);
                }
                listeners.add(listener);
            }
        } finally {
            this.rwl.writeLock().unlock();
        }
    }

    public void removeListener(@NotNull String entityType, @NotNull IEntityListener listener) {
        this.rwl.writeLock().lock();
        try {
            if (open) {
                Queue<IEntityListener> listeners = this.typeToListeners.get(entityType);
                if (listeners != null) {
                    listeners.remove(listener);
                    if (listeners.size() == 0) {
                        this.typeToListeners.remove(entityType);
                    }
                }
            }
        } finally {
            this.rwl.writeLock().unlock();
        }
    }

    public void close() {
        if (logger.isInfoEnabled()) {
            logger.info("Cleaning EventsMultiplexer listeners");
        }
        this.rwl.writeLock().lock();
        open = false;
        final Map<FullEntityId, Queue<IEntityListener>> notClosedListeners;
        // clear listeners
        try {
            this.typeToListeners.clear();
            // copy set
            notClosedListeners = new java.util.HashMap<>(this.instanceToListeners);
            instanceToListeners.clear();
        } finally {
            this.rwl.writeLock().unlock();
        }
        for (final FullEntityId id : notClosedListeners.keySet()) {
            if (logger.isErrorEnabled()) {
                logger.error(listenerToString(id, notClosedListeners.get(id)));
            }
        }
    }

    public void onClose(@NotNull TransientEntityStore store) {
    }

    public boolean hasEntityListeners() {
        this.rwl.readLock().lock();
        try {
            return !(instanceToListeners.isEmpty());
        } finally {
            this.rwl.readLock().unlock();
        }
    }

    public boolean hasEntityListener(TransientEntity entity) {
        this.rwl.readLock().lock();
        try {
            return instanceToListeners.containsKey(new FullEntityId(entity.getStore(), entity.getId()));
        } finally {
            this.rwl.readLock().unlock();
        }
    }

    private String listenerToString(final FullEntityId id, Queue<IEntityListener> listeners) {
        final StringBuilder builder = new StringBuilder(40);
        builder.append("Unregistered entity to listener class: ");
        id.toString(builder);
        builder.append(" ->");
        for (IEntityListener listener : listeners) {
            builder.append(' ');
            builder.append(listener.getClass().getName());
        }
        return builder.toString();
    }

    private void handlePerEntityChanges(Where where, TransientEntityChange c) {
        final Queue<IEntityListener> listeners;
        final TransientEntity e = c.getTransientEntity();
        final FullEntityId id = new FullEntityId(e.getStore(), e.getId());
        if (where == Where.ASYNC_AFTER_FLUSH && c.getChangeType() == EntityChangeType.REMOVE) {
            // unsubscribe all entity listeners, but fire them anyway
            this.rwl.writeLock().lock();
            try {
                listeners = this.instanceToListeners.remove(id);
            } finally {
                this.rwl.writeLock().unlock();
            }
        } else {
            this.rwl.readLock().lock();
            try {
                listeners = this.instanceToListeners.get(id);
            } finally {
                this.rwl.readLock().unlock();
            }
        }
        this.handleChange(where, c, listeners);
    }

    private void handlePerEntityTypeChanges(TransientEntityStore store, Where where, TransientEntityChange c) {
        ModelMetaData modelMedatData = store.getModelMetaData();
        if (modelMedatData != null) {
            EntityMetaData emd = modelMedatData.getEntityMetaData(c.getTransientEntity().getType());
            if (emd != null) {
                for (String type : emd.getThisAndSuperTypes()) {
                    Queue<IEntityListener> listeners;
                    this.rwl.readLock().lock();
                    try {
                        listeners = this.typeToListeners.get(type);
                    } finally {
                        this.rwl.readLock().unlock();
                    }
                    this.handleChange(where, c, listeners);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleChange(Where where, TransientEntityChange c, Queue listeners) {
        if (listeners != null) {
            EventsMultiplexerInternalKt.handleChange(where, c, listeners);
        }
    }

    @Nullable
    public JobProcessor getAsyncJobProcessor() {
        return this.eventsMultiplexerJobProcessor;
    }
}
