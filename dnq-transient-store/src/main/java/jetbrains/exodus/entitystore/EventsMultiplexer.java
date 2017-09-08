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
import jetbrains.exodus.core.execution.Job;
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
    private static Logger logger = LoggerFactory.getLogger(EventsMultiplexer.class);

    private Map<EventsMultiplexer.FullEntityId, Queue<IEntityListener>> instanceToListeners = new HashMap<>();
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

    public void beforeFlush(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changes) {
        this.fire(session.getStore(), Where.SYNC_BEFORE_CONSTRAINTS, changes);
    }

    public void beforeFlushAfterConstraintsCheck(@NotNull TransientStoreSession session, @Nullable Set<TransientEntityChange> changes) {
        this.fire(session.getStore(), Where.SYNC_BEFORE_FLUSH, changes);
    }

    public void afterConstraintsFail(@NotNull TransientStoreSession session, @NotNull Set<DataIntegrityViolationException> exceptions) {
    }

    private void asyncFire(@NotNull TransientStoreSession session, final Set<TransientEntityChange> changes, TransientChangesTracker changesTracker) {
        JobProcessor asyncJobProcessor = getAsyncJobProcessor();
        if (asyncJobProcessor == null) {
            changesTracker.dispose();
        } else {
            asyncJobProcessor.queue(new EventsMultiplexer.JobImpl(session.getStore(), this, changes, changesTracker));
        }
    }

    private void fire(TransientEntityStore store, Where where, Set<TransientEntityChange> changes) {
        for (TransientEntityChange c : changes) {
            this.handlePerEntityChanges(where, c);
            this.handlePerEntityTypeChanges(store, where, c);
        }
    }

    public void addListener(@NotNull Entity e, @NotNull IEntityListener listener) {
        if (((TransientEntity) e).isNew()) {
            throw new IllegalStateException("Entity is not saved into database - you can't listern to it.");
        }
        final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
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
        final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
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
        final Map<EventsMultiplexer.FullEntityId, Queue<IEntityListener>> notClosedListeners;
        // clear listeners
        try {
            this.typeToListeners.clear();
            // copy set
            notClosedListeners = new java.util.HashMap<>(this.instanceToListeners);
            instanceToListeners.clear();
        } finally {
            this.rwl.writeLock().unlock();
        }
        for (final EventsMultiplexer.FullEntityId id : notClosedListeners.keySet()) {
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
            return instanceToListeners.containsKey(new EventsMultiplexer.FullEntityId(entity.getStore(), entity.getId()));
        } finally {
            this.rwl.readLock().unlock();
        }
    }

    private String listenerToString(final EventsMultiplexer.FullEntityId id, Queue<IEntityListener> listeners) {
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
        final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
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
    private void handleChange(Where where, TransientEntityChange c, Queue<IEntityListener> listeners) {
        if (listeners != null) {
            for (IEntityListener l : listeners) {
                try {
                    switch (where) {
                        case SYNC_BEFORE_CONSTRAINTS:
                            switch (c.getChangeType()) {
                                case ADD:
                                    l.addedSyncBeforeConstraints(c.getTransientEntity());
                                    break;
                                case UPDATE:
                                    l.updatedSyncBeforeConstraints(c.getSnaphotEntity(), c.getTransientEntity());
                                    break;
                                case REMOVE:
                                    l.removedSyncBeforeConstraints(c.getSnaphotEntity());
                                    break;
                                default:
                                    throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
                            }
                            break;
                        case SYNC_BEFORE_FLUSH:
                            switch (c.getChangeType()) {
                                case ADD:
                                    l.addedSyncBeforeFlush(c.getTransientEntity());
                                    break;
                                case UPDATE:
                                    l.updatedSyncBeforeFlush(c.getSnaphotEntity(), c.getTransientEntity());
                                    break;
                                case REMOVE:
                                    l.removedSyncBeforeFlush(c.getSnaphotEntity());
                                    break;
                                default:
                                    throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
                            }
                            break;
                        case SYNC_AFTER_FLUSH:
                            switch (c.getChangeType()) {
                                case ADD:
                                    l.addedSync(c.getTransientEntity());
                                    break;
                                case UPDATE:
                                    l.updatedSync(c.getSnaphotEntity(), c.getTransientEntity());
                                    break;
                                case REMOVE:
                                    l.removedSync(c.getSnaphotEntity());
                                    break;
                                default:
                                    throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
                            }
                            break;
                        case ASYNC_AFTER_FLUSH:
                            switch (c.getChangeType()) {
                                case ADD:
                                    l.addedAsync(c.getTransientEntity());
                                    break;
                                case UPDATE:
                                    l.updatedAsync(c.getSnaphotEntity(), c.getTransientEntity());
                                    break;
                                case REMOVE:
                                    l.removedAsync(c.getSnaphotEntity());
                                    break;
                                default:
                                    throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
                            }
                            break;
                        default:
                            throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
                    }
                } catch (Exception e) {
                    // rethrow exception only for beforeFlush listeners
                    if (where == Where.SYNC_BEFORE_CONSTRAINTS) {
                        if (e instanceof RuntimeException) {
                            throw (RuntimeException) e;
                        }
                        throw new RuntimeException(e);
                    } else {
                        if (logger.isErrorEnabled()) {
                            logger.error("Exception while notifying entity listener.", e);
                        }
                    }
                }
            }
        }
    }

    @Nullable
    public JobProcessor getAsyncJobProcessor() {
        return this.eventsMultiplexerJobProcessor;
    }

    private static class JobImpl extends Job {
        @NotNull
        private TransientEntityStore store;
        private Set<TransientEntityChange> changes;
        private TransientChangesTracker changesTracker;
        private EventsMultiplexer eventsMultiplexer;

        JobImpl(@NotNull TransientEntityStore store, EventsMultiplexer eventsMultiplexer, Set<TransientEntityChange> changes, TransientChangesTracker changesTracker) {
            this.eventsMultiplexer = eventsMultiplexer;
            this.changes = changes;
            this.changesTracker = changesTracker;
            this.store = store;
        }

        public void execute() throws Throwable {
            try {
                EntityStoreExtensions.run(store, new Runnable() {
                    @Override
                    public void run() {
                        JobImpl.this.eventsMultiplexer.fire(store, Where.ASYNC_AFTER_FLUSH, JobImpl.this.changes);
                    }
                });
            } finally {
                changesTracker.dispose();
            }
        }

        @Override
        public String getName() {
            return "Async events from EventMultiplexer";
        }

        @Override
        public String getGroup() {
            return changesTracker.getSnapshot().getStore().getLocation();
        }
    }

    private class FullEntityId {
        private final int storeHashCode;
        private final int entityTypeId;
        private final long entityLocalId;

        private FullEntityId(final EntityStore store, final EntityId id) {
            storeHashCode = System.identityHashCode(store);
            entityTypeId = id.getTypeId();
            entityLocalId = id.getLocalId();
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            EventsMultiplexer.FullEntityId that = (EventsMultiplexer.FullEntityId) object;
            if (storeHashCode != that.storeHashCode) {
                return false;
            }
            if (entityLocalId != that.entityLocalId) {
                return false;
            }
            return entityTypeId == that.entityTypeId;
        }

        @Override
        public int hashCode() {
            int result = storeHashCode;
            result = 31 * result + entityTypeId;
            result = 31 * result + (int) (entityLocalId ^ (entityLocalId >> 32));
            return result;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder(10);
            toString(builder);
            return builder.toString();
        }

        public void toString(final StringBuilder builder) {
            builder.append(entityTypeId);
            builder.append('-');
            builder.append(entityLocalId);
            builder.append('@');
            builder.append(storeHashCode);
        }
    }
}
