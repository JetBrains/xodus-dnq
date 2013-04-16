package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.CantRemoveEntityException;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import jetbrains.teamsys.dnq.runtime.queries.QueryOperations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class BasePersistentClassImpl implements Runnable {
    protected Map<String, Iterable<PropertyConstraint>> propertyConstraints;

    protected BasePersistentClassImpl() {
    }

    protected Entity _constructor(final String _entityType_) {
        return ((TransientStoreSession) ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession()).newEntity(_entityType_);
    }

    public boolean isPropertyRequired(String name, Entity entity) {
        return false;
    }

    @NotNull
    public Map<String, Iterable<PropertyConstraint>> getPropertyConstraints() {
        return propertyConstraints != null ? propertyConstraints : Collections.<String, Iterable<PropertyConstraint>>emptyMap();
    }

    public void destructor(Entity entity) {
    }

    public void executeBeforeFlushTrigger(Entity entity) {
    }

    public boolean evaluateSaveHistoryCondition(Entity entity) {
        return false;
    }

    public void saveHistoryCallback(Entity entity) {
    }

    public DataIntegrityViolationException createIncomingLinksException(List<IncomingLinkViolation> linkViolations, final Entity entity) {
        List<Collection<String>> linkDescriptions = new ArrayList<Collection<String>>();
        for (IncomingLinkViolation violation : linkViolations) {
            linkDescriptions.add(violation.getDescription());
        }
        final String displayName = getDisplayName(entity);
        final String header = "Could not delete " + displayName + ", because it is referenced as: ";
        return new CantRemoveEntityException(entity, header, displayName, linkDescriptions);
    }

    public IncomingLinkViolation createIncomingLinkViolation(String linkName) {
        return new IncomingLinkViolation(linkName);
    }

    public String getDisplayName(final Entity entity) {
        return toString(entity);
    }

    public String toString(Entity entity) {
        if (entity instanceof TransientEntity) return ((TransientEntity) entity).getDebugPresentation();
        return entity.toString();
    }

    protected static QueryingEntityCreator getEntityCreator(final String entityType, final Set<String> queriedParams) {
        return new QueryingEntityCreator(entityType) {
            @Override
            public Iterable<Entity> query() {
                return QueryOperations.queryGetAll(entityType);
            }

            @Override
            public void created(@NotNull Entity entity) {
            }
        };
    }

    public static <T> Set<T> buildSet(final T[] data) {
        final Set<T> result = new HashSet<T>(data.length);
        for (final T t : data) {
            result.add(t);
        }
        return result;
    }

    protected static abstract class QueryingEntityCreator extends EntityCreator {

        protected QueryingEntityCreator(@NotNull String type) {
            super(type);
        }

        @Nullable
        @Override
        public Entity find() {
            return QueryOperations.getFirst(query());
        }

        public abstract Iterable<Entity> query();

        public Entity create() {
            return ((TransientStoreSession) ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession()).newEntity(this);
        }
    }

}
