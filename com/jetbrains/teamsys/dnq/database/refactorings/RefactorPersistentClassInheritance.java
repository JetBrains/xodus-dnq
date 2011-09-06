package com.jetbrains.teamsys.dnq.database.refactorings;

import com.jetbrains.teamsys.core.dataStructures.Pair;
import com.jetbrains.teamsys.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.util.*;

public class RefactorPersistentClassInheritance implements Runnable {

    private static final Log log = LogFactory.getLog(RefactorPersistentClassInheritance.class);

    private static final String DISCRIMINATOR_PROPERTY = "__DISCRIMINATOR__";
    private static final String TYPE_PROPERTY = "__TYPE__";

    @NotNull
    private final PersistentEntityStore _store;

    public RefactorPersistentClassInheritance(@NotNull final PersistentEntityStore store) {
        _store = store;
    }

    public void run() {
        final StoreSession session = _store.getAndCheckThreadSession();
        final List<String> allEntityTypes = _store.getEntityTypes();
        final Set<String> allEntityTypesSet = new HashSet<String>(allEntityTypes);
        final List<String> linkNames = _store.getAllLinkNames();
        for (final String entityType : allEntityTypes) {
            final List<Entity> entities = new ArrayList<Entity>();
            for (final Entity entity : session.getAll(entityType)) {
                entities.add(entity);
            }
            int i = 0;
            for (final Entity entity : entities) {
                final String actualType = (String) entity.getProperty(TYPE_PROPERTY);
                if (actualType != null) {
                    log.info("Refactoring: " + entity.toString() + " " + (++i) + " of " + entities.size() + " " + entityType + "s. " + actualType + " to be created.");
                }
                final StoreTransaction txn = session.beginTransaction();
                try {
                    if (actualType == null || entityType.equals(actualType)) {
                        entity.deleteProperty(DISCRIMINATOR_PROPERTY);
                        entity.deleteProperty(TYPE_PROPERTY);
                    } else {
                        final Entity newEntity = session.newEntity(actualType);
                        allEntityTypesSet.add(actualType);
                        // copy properties
                        for (final String propName : _store.getProperties(entity)) {
                            if (!DISCRIMINATOR_PROPERTY.equals(propName) && !TYPE_PROPERTY.equals(propName)) {
                                final Comparable value = _store.getProperty(entity, propName);
                                if (value != null) {
                                    _store.setProperty(newEntity, propName, value);
                                }
                            }
                        }
                        // copy blobs
                        for (final String blobName : _store.getBlobProperties(entity)) {
                            final InputStream blob = _store.getBlob(entity, blobName);
                            if (blob != null) {
                                _store.setBlob(newEntity, blobName, blob);
                                _store.deleteBlob(entity, blobName);
                            }
                        }
                        // copy outgoing links
                        for (final String linkName : _store.getLinkNames(entity)) {
                            for (final Entity target : entity.getLinks(linkName)) {
                                newEntity.addLink(linkName, target);
                            }
                        }
                        // look for incoming links and move them to the new entity
                        for (final String type : allEntityTypesSet) {
                            for (final String linkName : linkNames) {
                                for (final Entity source : session.findLinks(type, entity, linkName)) {
                                    source.deleteLink(linkName, entity);
                                    source.addLink(linkName, newEntity);
                                }
                            }
                        }
                        // delete origin entity
                        boolean canDelete = false;
                        if (entity instanceof PersistentEntity) {
                            final Map<String, EntityId> map = ((PersistentEntityStoreImpl) _store).tryDeleteEntity((PersistentEntity) entity);
                            canDelete = map.size() == 0;
                            map.clear();
                        } else if (entity instanceof TransientEntity) {
                            List<Pair<String,EntityIterable>> incomingLinks = ((TransientEntity) entity).getIncomingLinks();
                            canDelete = incomingLinks.size() == 0;
                            incomingLinks.clear();
                        } else {
                            throw new RuntimeException("Unknown entity type " + entity);
                        }

                        if(canDelete) entity.delete();
                    }
                }
                catch (Throwable e) {
                    txn.abort();
                    throw new RuntimeException(e);
                }
                txn.commit();
            }
        }
    }
}
