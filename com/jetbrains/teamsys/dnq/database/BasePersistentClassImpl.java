package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.database.exceptions.CantRemoveEntityException;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public abstract class BasePersistentClassImpl implements Runnable {

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
        return Collections.emptyMap();
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
        //final String header = "Could not delete " + getDisplayName(entity.getHistory().get(0)) + ", because it is referenced as:";
        final String header = "Could not delete " + getDisplayName(entity) + ", because it is referenced as: ";
        return new CantRemoveEntityException(entity, header, linkDescriptions);
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
}
