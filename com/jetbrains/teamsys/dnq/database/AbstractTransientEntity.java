package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.database.exceptions.EntityRemovedException;
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
  private String realType; // real type for entities within hierarchy
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

  /**
   * It's allowed to get persistent entity in state Open-Removed.
   *
   * @return
   */
  @NotNull
  public Entity getPersistentEntity() {
    return (Entity) new StandartEventHandler() {
      Object processOpenSaved() {
        return persistentEntity;
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }
    }.handle();
  }

  Entity getPersistentEntityInternal() {
    return persistentEntity;
  }

  protected void setPersistentEntityInternal(Entity persistentEntity) {
    this.persistentEntity = persistentEntity;
    this.version = persistentEntity.getVersion();
    this.type = persistentEntity.getType();
  }

  @NotNull
  public EntityStore getStore() {
    return session.getStore();
  }

  @NotNull
  TransientStoreSession getTransientStoreSession() {
    return session;
  }

  protected void setTransientStoreSession(TransientStoreSession session) {
    this.session = session;
  }

  public boolean isNew() {
    return state == State.New;
  }

  public boolean isSaved() {
    return state == State.Saved || state == State.SavedNew;
  }

  public boolean isRemoved() {
    return state == State.RemovedNew || state == State.RemovedSaved;
  }

  public boolean isTemporary() {
    return state == State.Temporary;
  }

  State getState() {
    return state;
  }

  protected void setState(State state) {
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
    return (String) new StandartEventHandler() {

      Object processOpenSaved() {
        return AbstractTransientEntity.this.type;
      }

      Object processOpenNew() {
        return AbstractTransientEntity.this.type;
      }

      Object processTemporary() {
        return AbstractTransientEntity.this.type;
      }

      Object processCommittedSaved() {
        return AbstractTransientEntity.this.type;
      }

    }.handle();
  }

  protected void setType(String type) {
    this.type = type;
  }

  /**
   * Allows getting id for Commited-Saved, Aborted-Saved and Open-Removed
   *
   * @return
   */
  @NotNull
  public EntityId getId() {
    return (EntityId) new StandartEventHandler() {

      Object processOpenFromAnotherSessionSaved() {
        return processOpenSaved();
      }

      Object processOpenSaved() {
        return getPersistentEntityInternal().getId();
      }

      Object processOpenNew() {
        return id;
      }

      Object processTemporary() {
        return id;
      }

      Object processOpenRemoved() {
        switch (state) {
          case RemovedNew:
            return id;
          case RemovedSaved:
            return getPersistentEntityInternal().getId();
        }

        throw new IllegalStateException();
      }

      Object processSuspendedRemoved() {
        switch (state) {
          case RemovedNew:
            return super.processSuspendedRemoved();
          case RemovedSaved:
            return getPersistentEntityInternal().getId();
        }

        throw new IllegalStateException();
      }

      Object processCommittedSaved() {
        return getPersistentEntityInternal().getId();
      }

      Object processAbortedSaved() {
        return getPersistentEntityInternal().getId();
      }

      Object processSuspendedSaved() {
        return getPersistentEntityInternal().getId();
      }

    }.handle();
  }

  @NotNull
  public String toIdString() {
    return (String) new StandartEventHandler() {

      String processOpenFromAnotherSessionSaved() {
        return processOpenSaved();
      }

      String processOpenSaved() {
        return getPersistentEntityInternal().toIdString();
      }

      String processOpenNew() {
        return id.toString();
      }

      Object processTemporary() {
        return id.toString();
      }

      String processOpenRemoved() {
        switch (state) {
          case RemovedNew:
            return id.toString();
          case RemovedSaved:
            return getPersistentEntityInternal().toIdString();
        }

        throw new IllegalStateException();
      }

      String processSuspendedRemoved() {
        switch (state) {
          case RemovedNew:
            super.processSuspendedRemoved();
          case RemovedSaved:
            return getPersistentEntityInternal().toIdString();
        }

        throw new IllegalStateException();
      }

      String processCommittedSaved() {
        return getPersistentEntityInternal().toIdString();
      }

      String processAbortedSaved() {
        return getPersistentEntityInternal().toIdString();
      }

      String processSuspendedSaved() {
        return getPersistentEntityInternal().toIdString();
      }

    }.handle();
  }

  protected void setId(TransientEntityIdImpl id) {
    this.id = id;
  }

  void setPersistentEntity(@NotNull final Entity persistentEntity) {
    if (persistentEntity instanceof TransientEntity) {
      throw new IllegalArgumentException("Can't create transient entity as wrapper for another transient entity. " + AbstractTransientEntity.this);
    }

    new StandartEventHandler() {
      Object processOpenSaved() {
        throw new IllegalStateException("Transient entity already associated with persistent entity. " + AbstractTransientEntity.this);
      }

      Object processOpenNew() {
        setPersistentEntityInternal(persistentEntity);
        state = State.SavedNew;
        return null;
      }

      Object processTemporary() {
        throw new IllegalStateException("Can't set persistent entity for a temporary transient one. " + AbstractTransientEntity.this);
      }

    }.handle();
  }

  void clearPersistentEntity() {
    new StandartEventHandler() {
      Object processOpenSaved() {
        setPersistentEntityInternal(null);
        state = State.New;
        return null;
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }
    }.handle();
  }

  void updateVersion() {
    new StandartEventHandler() {

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }

      Object processOpenSaved() {
        version = getPersistentEntityInternal().getVersion();
        return null;
      }
    }.handle();
  }

  @NotNull
  public List<String> getPropertyNames() {
    return (List<String>) new StandartEventHandler() {
      Object processOpenSaved() {
        return getPersistentEntityInternal().getPropertyNames();
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }

    }.handle();
  }

  @NotNull
  public List<String> getBlobNames() {
    return (List<String>) new StandartEventHandler() {
      Object processOpenSaved() {
        return getPersistentEntityInternal().getBlobNames();
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }

    }.handle();
  }

  @NotNull
  public List<String> getLinkNames() {
    return (List<String>) new StandartEventHandler() {
      Object processOpenSaved() {
        return getPersistentEntityInternal().getLinkNames();
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }

    }.handle();
  }

  public int getVersion() {
    return (Integer) new StandartEventHandler() {
      Object processOpenSaved() {
        return version;
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }
    }.handle();
  }

  int getVersionInternal() {
    return version;
  }

  @Nullable
  public Entity getUpToDateVersion() {
    return (Entity) new StandartEventHandler() {
      Object processOpenSaved() {
        Entity e = getPersistentEntityInternal().getUpToDateVersion();
        return e == null ? null : session.newEntity(e);
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }
    }.handle();
  }

  @NotNull
  public List<Entity> getHistory() {
    return (List<Entity>) new StandartEventHandler() {
      Object processOpenSaved() {
        final List<Entity> history = getPersistentEntityInternal().getHistory();
        final List<Entity> result = new ArrayList<Entity>(history.size());
        final TransientStoreSession session = getTransientStoreSession();
        for (final Entity entity : history) {
          result.add(session.newEntity(entity));
        }
        return result;
      }

      Object processOpenNew() {
        // new transient entity has no history
        return Collections.EMPTY_LIST;
      }

      Object processTemporary() {
        // temporary transient entity has no history
        return Collections.EMPTY_LIST;
      }

    }.handle();
  }

  @Nullable
  public Entity getNextVersion() {
    return (Entity) new StandartEventHandler() {
      Object processOpenSaved() {
        final Entity e = getPersistentEntityInternal().getNextVersion();
        return e == null ? null : session.newEntity(e);
      }

      Object processOpenNew() {
        return null;
      }

      Object processTemporary() {
        return null;
      }
    }.handle();
  }

  @Nullable
  public Entity getPreviousVersion() {
    return (Entity) new StandartEventHandler() {
      Object processOpenSaved() {
        final Entity e = getPersistentEntityInternal().getPreviousVersion();
        return e == null ? null : session.newEntity(e);
      }

      Object processOpenNew() {
        return null;
      }

      Object processTemporary() {
        return null;
      }
    }.handle();
  }

  public int compareTo(final Entity e) {
    return (Integer) new StandartEventHandler() {
      Object processOpenSaved() {
        return getPersistentEntityInternal().compareTo(e);
      }

      Object processOpenNew() {
        return throwNoPersistentEntity();
      }

      Object processTemporary() {
        return throwNoPersistentEntity();
      }
    }.handle();
  }

  public String toString() {
    final Entity pe = getPersistentEntityInternal();

    final StringBuilder sb = new StringBuilder();
    if (pe != null) {
      sb.append(pe);
    } else {
      sb.append(type);
    }

    sb.append(" (");
    sb.append(realType);
    sb.append(":");
    sb.append(state);

    if (entityCreationPosition != null) {
      sb.append(": ").append(entityCreationPosition.getClassName()).append(".").
              append(entityCreationPosition.getMethodName()).append(":").append(entityCreationPosition.getLineNumber());
    }

    sb.append(")");

    return sb.toString();
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof AbstractTransientEntity)) {
      return false;
    }

    // equals may be called

    final AbstractTransientEntity that = (AbstractTransientEntity) obj;

    return (Boolean) new StandartEventHandler() {

      Object processOpenFromAnotherSessionSaved() {
        return processOpenSaved();
      }

      Object processOpenSaved() {
        return that.isSaved() && getPersistentEntityInternal().equals(that.getPersistentEntityInternal());
      }

      Object processCommittedSaved() {
        return processOpenSaved();
      }

      Object processOpenNew() {
        return AbstractTransientEntity.this == that;
      }

      Object processTemporary() {
        return AbstractTransientEntity.this == that;
      }

      Object processOpenRemoved() {
        switch (state) {
          case RemovedNew:
            return AbstractTransientEntity.this == that;
          case RemovedSaved:
            return that.isRemoved() && !that.wasNew() && getPersistentEntityInternal().equals(that.getPersistentEntityInternal());
        }

        return false;
      }

      Object processSuspendedSaved() {
        return that.isSaved() && getPersistentEntityInternal().equals(that.getPersistentEntityInternal());
      }

    }.handle();
  }

  public int hashCode() {
    return (Integer) new StandartEventHandler() {

      Object processOpenFromAnotherSessionSaved() {
        return getPersistentEntityInternal().hashCode();
      }

      Object processOpenSaved() {
        // to sutisfy hashCode contract, return old hashCode for saved entities that was new, later, in this session
        if (state == State.SavedNew) {
          // return hasCode for own session
          return System.identityHashCode(AbstractTransientEntity.this);
        } else if (state == State.Saved) {
          return getPersistentEntityInternal().hashCode();
        } else {
          throw new IllegalStateException("Unknown state [" + state + "]");
        }
      }

      Object processOpenNew() {
        return System.identityHashCode(AbstractTransientEntity.this);
      }

      Object processTemporary() {
        return System.identityHashCode(AbstractTransientEntity.this);
      }

      Object processOpenRemoved() {
        switch (state) {
          case RemovedNew:
            return System.identityHashCode(AbstractTransientEntity.this);
          case RemovedSaved:
            return getPersistentEntityInternal().hashCode();
          default:
            throw new IllegalArgumentException();
        }
      }

      Object processCommittedSaved() {
        return getPersistentEntityInternal().hashCode();
      }

      Object processSuspendedSaved() {
        return getPersistentEntityInternal().hashCode();
      }

    }.handle();
  }

  @NotNull
  public String getRealType() {
    if (realType != null) {
      return realType;
    }

    ModelMetaData md = ((TransientEntityStore) getStore()).getModelMetaData();

    // TODO: to support extensibility - every entity should have TYPE and DISCRIMINATOR
    final EntityMetaData emd;
    if (md == null || (emd = md.getEntityMetaData(type)) == null || !emd.getWithinHierarchy()) {
      realType = type;
    } else {
      realType = getProperty(__TYPE__);

      if (realType == null) {
        throw new IllegalArgumentException("Can't determine real type of entity within hierarchy [" + type + "]");
      }
    }

    return realType;
  }

  private Object throwNoPersistentEntity() {
    throw new IllegalStateException("Transient entity has no associated persistent entity. " + this);
  }

  protected abstract class StandartEventHandler {

    Object handle() {
      if (session.isOpened()) {
        // check that entity is accessed in the same thread as session
        final TransientStoreSession storeSession = (TransientStoreSession) getStore().getThreadSession();
        if (session != storeSession) {
          switch (state) {
            case New:
              return processOpenFromAnotherSessionNew();

            case Saved:
            case SavedNew:
              return processOpenFromAnotherSessionSaved();

            case RemovedNew:
            case RemovedSaved:
              return processOpenFromAnotherSessionRemoved();
            case Temporary:
              return processTemporary();
          }
        }

        switch (state) {
          case New:
            return processOpenNew();

          case Saved:
          case SavedNew:
            return processOpenSaved();

          case RemovedNew:
          case RemovedSaved:
            return processOpenRemoved();
          case Temporary:
            return processTemporary();
        }

      } else if (session.isSuspended()) {
        switch (state) {
          case New:
            throw new IllegalStateException("Can't access new transient entity while its session is suspended. " + AbstractTransientEntity.this);

          case Saved:
          case SavedNew:
            return processSuspendedSaved();

          case RemovedNew:
          case RemovedSaved:
            return processSuspendedRemoved();
          case Temporary:
            return processTemporary();
        }

      } else if (session.isAborted()) {
        switch (state) {
          case New:
            throw new IllegalStateException("Can't access new transient entity from aborted session. " + AbstractTransientEntity.this);

          case Saved:
          case SavedNew:
            return processAbortedSaved();

          case RemovedNew:
          case RemovedSaved:
            throw new EntityRemovedException(AbstractTransientEntity.this);
          case Temporary:
            return processTemporary();
        }

      } else if (session.isCommitted()) {
        switch (state) {
          case New:
            throw new IllegalStateException("Illegal comination of session and transient entity states (Commited, New). Possible bug. " + AbstractTransientEntity.this);

          case Saved:
          case SavedNew:
            return processCommittedSaved();

          case RemovedNew:
          case RemovedSaved:
            throw new EntityRemovedException(AbstractTransientEntity.this);
          case Temporary:
            return processTemporary();
        }
      }

      throw new IllegalStateException("Unknown session state. " + AbstractTransientEntity.this);
    }

    Object processOpenFromAnotherSessionNew() {
      throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + AbstractTransientEntity.this);
    }

    Object processOpenFromAnotherSessionSaved() {
      throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + AbstractTransientEntity.this);
    }

    Object processOpenFromAnotherSessionRemoved() {
      throw new IllegalStateException("It's not allowed to access entity from another thread while its session is open. " + AbstractTransientEntity.this);
    }

    Object processSuspendedSaved() {
      throw new IllegalStateException("Can't access transient saved entity while it's session is suspended. Only getId is permitted. " + AbstractTransientEntity.this);
    }

    abstract Object processOpenSaved();

    abstract Object processOpenNew();

    abstract Object processTemporary();

    Object processOpenRemoved() {
      throw new EntityRemovedException(AbstractTransientEntity.this);
    }

    Object processSuspendedRemoved() {
      throw new EntityRemovedException(AbstractTransientEntity.this);
    }

    Object processCommittedSaved() {
      throw new IllegalStateException("Can't access committed saved entity. Only getId is permitted. " + AbstractTransientEntity.this);
    }

    Object processAbortedSaved() {
      throw new IllegalStateException("Can't access saved entity from aborted transaction. Only getId is permitted. " + AbstractTransientEntity.this);
    }
  }
}
