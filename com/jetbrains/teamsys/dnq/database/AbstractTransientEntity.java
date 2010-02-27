package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.EntityRemovedException;
import jetbrains.teamsys.dnq.runtime.util.DnqUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

abstract class AbstractTransientEntity implements TransientEntity {

    protected static final Log log = LogFactory.getLog(TransientEntity.class);

    enum State {
        New("new"),
        Saved("saved"),
        SavedNew("savedNew"),
        RemovedSaved("removedSaved"),
        RemovedNew("removedNew"),
        Temporary("temporary");

        private String name;

        State(String name) {
            this.name = name;
        }
    }

    private Entity persistentEntity;
    private int version;
    private String type;
    private State state;
    private TransientStoreSession session;
    private TransientEntityIdImpl id;
    protected StackTraceElement entityCreationPosition = null;

    protected void trackEntityCreation(TransientStoreSession session) {
        if (((TransientEntityStore) session.getStore()).isTrackEntityCreation()) {
            try {
                throw new Exception();
            } catch (Exception e) {
                for (StackTraceElement ste : e.getStackTrace()) {
                    //TODO: change prefix after refactoring
                    if (!ste.getClassName().startsWith("com.jetbrains.teamsys") && !ste.getMethodName().equals("constructor")) {
                        entityCreationPosition = ste;
                        break;
                    }
                }
            }
        }
    }

    public StackTraceElement getEntityCreationPosition() {
        return entityCreationPosition;
    }

    private static final StandartEventHandler getPersistentEntityEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.persistentEntity;
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity; // there is no persistent entity for the temporary one
        }
    };

    /**
     * It's allowed to get persistent entity in state Open-Removed.
     *
     * @return
     */
    @NotNull
    public Entity getPersistentEntity() {
        return (Entity) getPersistentEntityEventHandler.handle(this, null, null);
    }

    public void deleteInternal() {
        persistentEntity.delete();
    }

    Entity getPersistentEntityInternal() {
        return persistentEntity;
    }

    protected void setPersistentEntityInternal(Entity persistentEntity) {
        this.persistentEntity = persistentEntity;
        if (persistentEntity != null) {
            this.version = persistentEntity.getVersion();
            this.type = persistentEntity.getType();
        }
    }

    @NotNull
    public EntityStore getStore() {
        return session.getStore();
    }

    @NotNull
    public TransientStoreSession getTransientStoreSession() {
        return session;
    }

    protected void setTransientStoreSession(TransientStoreSession session) {
        this.session = session;
    }

    public boolean isNew() {
        return state == State.New;
    }

    public boolean isNewOrTemporary() {
        return isNew() || isTemporary();
    }

    public boolean isSaved() {
        return state == State.Saved || state == State.SavedNew;
    }

    public boolean isRemoved() {
        return state == State.RemovedNew || state == State.RemovedSaved;
    }

    public boolean isRemovedOrTemporary() {
        return isRemoved() || isTemporary();
    }

    public boolean isTemporary() {
        return state == State.Temporary;
    }

    public boolean isReadonly() {
        return false;
    }

    State getState() {
        return state;
    }

    protected void setState(State state) {
        if (this.state == State.Temporary && state != State.Temporary) {
            throw new IllegalStateException("Can't change Temporary state of entity.");
        }
        this.state = state;
    }

    public boolean wasNew() {
        switch (state) {
            case RemovedNew:
            case SavedNew:
                return true;

            case RemovedSaved:
            case Saved:
                return false;

            default:
                throw new IllegalStateException("Entity is not in removed or saved state.");
        }
    }

    @NotNull
    public String getType() {
        return type;
    }

    protected void setType(String type) {
        this.type = type;
    }

    private static final StandartEventHandler getIdEventHandler = new StandartEventHandler() {

        Object processOpenFromAnotherSessionSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return processOpenSaved(entity, param1, param2);
        }

        @Override
        Object processOpenFromAnotherSessionRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            return processOpenRemoved(entity, param1, param2);
        }

        @Override
        protected Object processClosedRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return super.processClosedRemoved(entity, param1, param2);
                case RemovedSaved:
                    return entity.getPersistentEntityInternal().getId();
            }

            throw new IllegalStateException();
        }

        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getId();
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.id;
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.id;
        }

        Object processOpenRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return entity.id;
                case RemovedSaved:
                    return entity.getPersistentEntityInternal().getId();
            }

            throw new IllegalStateException();
        }

        Object processSuspendedRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return super.processSuspendedRemoved(entity, param1, param2);
                case RemovedSaved:
                    return entity.getPersistentEntityInternal().getId();
            }

            throw new IllegalStateException();
        }

        Object processClosedSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getId();
        }

        Object processSuspendedSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getId();
        }

    };


    /**
     * Allows getting id for Commited-Saved, Aborted-Saved and Open-Removed
     *
     * @return
     */
    @NotNull
    public EntityId getId() {
        return (EntityId) getIdEventHandler.handle(this, null, null);
    }

    private final static StandartEventHandler toIdStringEventHandler = new StandartEventHandler() {

        String processOpenFromAnotherSessionSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return processOpenSaved(entity, param1, param2);
        }

        String processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().toIdString();
        }

        String processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.id.toString();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.id.toString();
        }

        String processOpenRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return entity.id.toString();
                case RemovedSaved:
                    return entity.getPersistentEntityInternal().toIdString();
            }

            throw new IllegalStateException();
        }

        String processSuspendedRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    super.processSuspendedRemoved(entity, param1, param2);
                case RemovedSaved:
                    return entity.getPersistentEntityInternal().toIdString();
            }

            throw new IllegalStateException();
        }

        String processClosedSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().toIdString();
        }

        String processSuspendedSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().toIdString();
        }

    };


    @NotNull
    public String toIdString() {
        return (String) toIdStringEventHandler.handle(this, null, null);
    }

    protected void setId(TransientEntityIdImpl id) {
        this.id = id;
    }

    private final static StandartEventHandler<Entity, Object> setPersistentEntityEventHandler = new StandartEventHandler<Entity, Object>() {

        Object processOpenSaved(AbstractTransientEntity entity, Entity param1, Object param2) {
            throw new IllegalStateException("Transient entity already associated with persistent entity. " + entity);
        }

        Object processOpenNew(AbstractTransientEntity entity, Entity param1, Object param2) {
            entity.setPersistentEntityInternal(param1);
            entity.state = State.SavedNew;
            return null;
        }

        Object processTemporary(AbstractTransientEntity entity, Entity param1, Object param2) {
            throw new IllegalStateException("Can't set persistent entity for a temporary transient one. " + entity);
        }

    };

    void setPersistentEntity(@NotNull final Entity persistentEntity) {
        if (persistentEntity instanceof TransientEntity) {
            throw new IllegalArgumentException("Can't create transient entity as wrapper for another transient entity. " + AbstractTransientEntity.this);
        }

        setPersistentEntityEventHandler.handle(this, persistentEntity, null);
    }

    private final static StandartEventHandler clearPersistentEntityEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            entity.setPersistentEntityInternal(null);
            entity.state = State.New;
            return null;
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }
    };


    void clearPersistentEntity() {
        clearPersistentEntityEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler updateVersionEventHandler = new StandartEventHandler() {

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            entity.version = entity.getPersistentEntityInternal().getVersion();
            return null;
        }
    };


    void updateVersion() {
        updateVersionEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler getPropertyNamesEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getPropertyNames();
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

    };

    @NotNull
    public List<String> getPropertyNames() {
        return (List<String>) getPropertyNamesEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler getBlobNamesEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getBlobNames();
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

    };


    @NotNull
    public List<String> getBlobNames() {
        return (List<String>) getBlobNamesEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler getLinkNamesEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().getLinkNames();
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

    };

    @NotNull
    public List<String> getLinkNames() {
        return (List<String>) getLinkNamesEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler getVersionEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.version;
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }
    };


    public int getVersion() {
        return (Integer) getVersionEventHandler.handle(this, null, null);
    }

    int getVersionInternal() {
        return version;
    }

    private static final StandartEventHandler getUpToDateVersionEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            Entity e = entity.getPersistentEntityInternal().getUpToDateVersion();
            return e == null ? null : entity.session.newEntity(e);
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.throwNoPersistentEntity();
        }
    };


    @Nullable
    public Entity getUpToDateVersion() {
        return (Entity) getUpToDateVersionEventHandler.handle(this, null, null);
    }

    public boolean isUpToDate() {
        return getPersistentEntity().isUpToDate();
    }

    private static final StandartEventHandler getHistoryEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            final List<Entity> history = entity.getPersistentEntityInternal().getHistory();
            final List<Entity> result = new ArrayList<Entity>(history.size());
            final TransientStoreSession session = entity.getTransientStoreSession();
            for (final Entity _entity : history) {
                result.add(session.newEntity(_entity));
            }
            return result;
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            // new transient entity has no history
            return Collections.EMPTY_LIST;
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            // temporary transient entity has no history
            return Collections.EMPTY_LIST;
        }

    };


    @NotNull
    public List<Entity> getHistory() {
        return (List<Entity>) getHistoryEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler getNextVersionEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            final Entity e = entity.getPersistentEntityInternal().getNextVersion();
            return e == null ? null : entity.session.newEntity(e);
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return null;
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return null;
        }
    };


    @Nullable
    public Entity getNextVersion() {
        return (Entity) getNextVersionEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler getPreviousVersionEventHandler = new StandartEventHandler() {
        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            final Entity e = entity.getPersistentEntityInternal().getPreviousVersion();
            return e == null ? null : entity.session.newEntity(e);
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return null;
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return null;
        }
    };

    @Nullable
    public Entity getPreviousVersion() {
        return (Entity) getPreviousVersionEventHandler.handle(this, null, null);
    }

    private static final StandartEventHandler<Entity, Object> compareToEventHandler = new StandartEventHandler<Entity, Object>() {
        Object processOpenSaved(AbstractTransientEntity entity, Entity e, Object param2) {
            return entity.getPersistentEntityInternal().compareTo(e);
        }

        Object processOpenNew(AbstractTransientEntity entity, Entity param, Object param2) {
            return entity.throwNoPersistentEntity();
        }

        Object processTemporary(AbstractTransientEntity entity, Entity param, Object param2) {
            return entity.throwNoPersistentEntity();
        }
    };


    public int compareTo(final Entity e) {
        return (Integer) compareToEventHandler.handle(this, e, null);
    }

    /**
     * Called by BasePersistentClass by default
     * @return
     */
    public String getDebugPresentation() {
        final Entity pe = getPersistentEntityInternal();

        final StringBuilder sb = new StringBuilder();
        if (pe != null) {
            sb.append(pe);
        } else {
            sb.append(type);
        }

        sb.append(" (");
        sb.append(state);

        if (entityCreationPosition != null) {
            sb.append(": ").append(entityCreationPosition.getClassName()).append(".").
                    append(entityCreationPosition.getMethodName()).append(":").append(entityCreationPosition.getLineNumber());
        }

        sb.append(")");

        return sb.toString();
    }

    public String toString() {
        // delegate to Persistent Class implementation
        return ((BasePersistentClass)DnqUtils.getPersistentClassInstance(this, this.getType())).toString(this);
    }

    private static final StandartEventHandler<AbstractTransientEntity, Object> equalsEventHandler = new StandartEventHandler<AbstractTransientEntity, Object>() {

        Object processOpenFromAnotherSessionSaved(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return processOpenSaved(entity, that, param2);
        }

        Object processOpenSaved(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return (that.isSaved() || (that.isRemoved() && !that.wasNew())) &&
                    entity.getPersistentEntityInternal().equals(that.getPersistentEntityInternal());
        }

        Object processClosedSaved(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return processOpenSaved(entity, that, param2);
        }

        Object processOpenNew(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return entity == that;
        }

        Object processTemporary(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return entity == that;
        }

        @Override
        protected Object processClosedRemoved(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return processOpenRemoved(entity, that, param2);
        }

        Object processOpenRemoved(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return entity == that;
                case RemovedSaved:
                    return (that.isSaved() || (that.isRemoved() && !that.wasNew())) && 
                            entity.getPersistentEntityInternal().equals(that.getPersistentEntityInternal());
            }

            return false;
        }

        Object processSuspendedSaved(AbstractTransientEntity entity, AbstractTransientEntity that, Object param2) {
            return that.isSaved() && entity.getPersistentEntityInternal().equals(that.getPersistentEntityInternal());
        }

    };


    public boolean equals(Object obj) {
        if (!(obj instanceof AbstractTransientEntity)) {
            return false;
        }

        return obj == this || (Boolean) equalsEventHandler.handle(this, (AbstractTransientEntity) obj, null);
    }

    private static final StandartEventHandler hashCodeEventHandler = new StandartEventHandler() {

        Object processOpenFromAnotherSessionSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().hashCode();
        }

        Object processOpenSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            // to sutisfy hashCode contract, return old hashCode for saved entities that was new, later, in this session
            if (entity.state == State.SavedNew) {
                // return hasCode for own session
                return System.identityHashCode(entity);
            } else if (entity.state == State.Saved) {
                return entity.getPersistentEntityInternal().hashCode();
            } else {
                throw new IllegalStateException("Unknown state [" + entity.state + "]");
            }
        }

        Object processOpenNew(AbstractTransientEntity entity, Object param1, Object param2) {
            return System.identityHashCode(entity);
        }

        Object processTemporary(AbstractTransientEntity entity, Object param1, Object param2) {
            return System.identityHashCode(entity);
        }

        Object processOpenRemoved(AbstractTransientEntity entity, Object param1, Object param2) {
            switch (entity.state) {
                case RemovedNew:
                    return System.identityHashCode(entity);
                case RemovedSaved:
                    return entity.getPersistentEntityInternal().hashCode();
                default:
                    throw new IllegalArgumentException();
            }
        }

        Object processClosedSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().hashCode();
        }

        Object processSuspendedSaved(AbstractTransientEntity entity, Object param1, Object param2) {
            return entity.getPersistentEntityInternal().hashCode();
        }

    };


    public int hashCode() {
        return (Integer) hashCodeEventHandler.handle(this, null, null);
    }

    private Object throwNoPersistentEntity() {
        throw new IllegalStateException("Transient entity has no associated persistent entity. " + this);
    }

    protected static abstract class StandartEventHandler<P1, P2> {

        protected StandartEventHandler() {
        }

        Object handle(@NotNull AbstractTransientEntity entity, @Nullable P1 param1, @Nullable P2 param2) {
            do {
                if (entity.session.isOpened()) {
// check that entity is accessed in the same thread as session
                    final TransientStoreSession storeSession = (TransientStoreSession) entity.getStore().getThreadSession();
                    if (entity.session != storeSession) {
                        switch (entity.state) {
                            case New:
                                return processOpenFromAnotherSessionNew(entity, param1, param2);

                            case Saved:
                            case SavedNew:
                                return processOpenFromAnotherSessionSaved(entity, param1, param2);

                            case RemovedNew:
                            case RemovedSaved:
                                return processOpenFromAnotherSessionRemoved(entity, param1, param2);
                            case Temporary:
                                return processTemporary(entity, param1, param2);
                        }
                    }

                    switch (entity.state) {
                        case New:
                            return processOpenNew(entity, param1, param2);

                        case Saved:
                        case SavedNew:
                            return processOpenSaved(entity, param1, param2);

                        case RemovedNew:
                        case RemovedSaved:
                            return processOpenRemoved(entity, param1, param2);
                        case Temporary:
                            return processTemporary(entity, param1, param2);
                    }

                } else if (entity.session.isSuspended()) {
                    switch (entity.state) {
                        case New:
                            throw new IllegalStateException("Can't access new transient entity while its session is suspended. " + entity);

                        case Saved:
                        case SavedNew:
                            return processSuspendedSaved(entity, param1, param2);

                        case RemovedNew:
                        case RemovedSaved:
                            return processSuspendedRemoved(entity, param1, param2);
                        case Temporary:
                            return processTemporary(entity, param1, param2);
                    }
                } else if (entity.session.isAborted() || entity.session.isCommitted()) {
                    switch (entity.state) {
                        case New:
                            throw new IllegalStateException("Illegal comination of session and transient entity states (Commited or Aborted, New). Possible bug. " + entity);

                        case Saved:
                        case SavedNew:
                            return processClosedSaved(entity, param1, param2);

                        case RemovedNew:
                        case RemovedSaved:
                            return processClosedRemoved(entity, param1, param2);

                        case Temporary:
                            return processTemporary(entity, param1, param2);
                    }
                }

            } while (true);
            //throw new IllegalStateException("Unknown session state. " + entity);
        }

        protected Object processClosedRemoved(AbstractTransientEntity entity, P1 paraP1, P2 param2) {
            throw new EntityRemovedException(entity);
        }

        Object processOpenFromAnotherSessionNew(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + entity);
        }

        Object processOpenFromAnotherSessionSaved(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + entity);
        }

        Object processOpenFromAnotherSessionRemoved(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + entity);
        }

        Object processSuspendedSaved(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new IllegalStateException("Can't access transient saved entity while it's session is suspended. Only getId is permitted. " + entity);
        }

        abstract Object processOpenSaved(AbstractTransientEntity entity, P1 param1, P2 param2);

        abstract Object processOpenNew(AbstractTransientEntity entity, P1 param1, P2 param2);

        Object processTemporary(AbstractTransientEntity entity, P1 param1, P2 param2) {
            return processOpenSaved(entity, param1, param2);
        }

        Object processOpenRemoved(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new EntityRemovedException(entity);
        }

        Object processSuspendedRemoved(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new EntityRemovedException(entity);
        }

        Object processClosedSaved(AbstractTransientEntity entity, P1 param1, P2 param2) {
            throw new IllegalStateException("Can't access committed saved entity. Only getId is permitted. " + entity);
        }

    }

}
