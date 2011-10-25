package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.database.MessageBuildser;
import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.CantRemoveEntityException;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public abstract class BasePersistentClassImpl implements Runnable {
    protected static final int MAX_LINKED_ENTITIES_TO_SHOW = 10;

    protected BasePersistentClassImpl() {
    }

    protected Entity _constructor(final String _entityType_) {
        return ((TransientStoreSession) ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession()).newEntity(_entityType_);
    }

    public boolean isPropertyRequired(String name, Entity entity) {
        return false;
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

    protected List<String> onTargetDeleteErrors(EntityIterable linkedEntities, String linkName, Entity target) {
        List<String> res = new ArrayList<String>();
        for (Entity linkedEntity : linkedEntities.take(MAX_LINKED_ENTITIES_TO_SHOW)) {
            res.add(getDisplayName(linkedEntity) + "." + linkName);
        }
        long leftMore = linkedEntities.size() - MAX_LINKED_ENTITIES_TO_SHOW;
        if (leftMore > 0) res.add("And " + leftMore + " more...");
        return res;
    }

    public DataIntegrityViolationException createIncomingLinksException(List<Pair<String, EntityIterable>> linkName2Iterable, @NotNull ModelMetaData modelMetaData, final Entity entity) {
        List<String> linkDescriptions = new ArrayList<String>();
        for (Pair<String, EntityIterable> pair : linkName2Iterable) {
            EntityIterable linkedEntities = pair.getSecond();
            //All entities should be of same type so get first
            Entity firstLinkedEntity = entity.getStore().getThreadSession().getFirst(linkedEntities);
            linkDescriptions.addAll(TransientStoreUtil.getPersistentClassInstance(firstLinkedEntity, firstLinkedEntity.getType()).onTargetDeleteErrors(linkedEntities, pair.getFirst(), entity));
        }

        //final String header = "Could not delete " + getDisplayName(entity.getHistory().get(0)) + ", because it is referenced as:";
        final String header = "Could not delete " + getDisplayName(entity) + ", because it is referenced as: ";
        return new CantRemoveEntityException(entity, header, linkDescriptions);
    }

    public String getDisplayName(final Entity entity) {
        return toString(entity);
    }

    public String toString(Entity entity) {
        if (entity instanceof TransientEntity) return ((TransientEntity) entity).getDebugPresentation();
        return entity.toString();
    }

    protected List<String> createPerInstanceErrorMessage(MessageBuildser messageBuilder, EntityIterable linkedEntities) {
        List<String> res = new ArrayList<String>();
        for (Entity entity : linkedEntities.take(MAX_LINKED_ENTITIES_TO_SHOW)) {
            res.add(messageBuilder.build(null, entity));
        }
        long leftMore = linkedEntities.size() - MAX_LINKED_ENTITIES_TO_SHOW;
        if (leftMore > 0) {
            res.add("And " + leftMore + " more...");
        }
        return res;
    }

    protected String createPerTypeErrorMessage(MessageBuildser messageBuilder, EntityIterable linkedEntities) {
        return messageBuilder.build(linkedEntities, null);
    }
}
