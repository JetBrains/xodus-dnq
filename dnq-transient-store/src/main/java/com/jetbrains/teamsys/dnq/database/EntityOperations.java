/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.query.metadata.EntityMetaData;
import jetbrains.exodus.query.metadata.ModelMetaData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

// TODO: move this class to the associations semantics package

public class EntityOperations {

    private static final Logger logger = LoggerFactory.getLogger(EntityOperations.class);

    private EntityOperations() {
    }

    public static void remove(final Entity e) {
        /* two-phase remove:
           1. call destructors
           2. remove links and entities
        */

        remove(e, true, new HashSet<Entity>());
        remove(e, false, new HashSet<Entity>());
    }

    static void remove(final Entity e, boolean callDestructorPhase, Set<Entity> processed) {
        if (e == null || ((TransientEntity) e).isRemoved()) return;
        TransientEntity reattached = TransientStoreUtil.reattach((TransientEntity) e);

        if (processed.contains(reattached)) return;

        TransientEntityStore store = (TransientEntityStore) reattached.getStore();

        ModelMetaData md = store.getModelMetaData();
        if (md != null) {
            // cascade delete
            EntityMetaData emd = md.getEntityMetaData(reattached.getType());
            if (emd != null) {
                if (callDestructorPhase) {
                    TransientStoreUtil.getPersistentClassInstance(reattached).destructor(reattached);
                }
                processed.add(reattached);
                // remove associations and cascade delete
                TransientStoreSession storeSession = (TransientStoreSession) store.getThreadSession();
                if (storeSession == null) {
                    throw new IllegalStateException("No current transient session!");
                }
                ConstraintsUtil.processOnDeleteConstraints(storeSession, reattached, emd, md, callDestructorPhase, processed);
            }
        }

        if (!callDestructorPhase) {
            // delete itself; the check is performed, because onDelete constraints could already delete entity 'e'
            if (!reattached.isRemoved()) reattached.delete();
        }
    }

    /**
     * Checks if entity e was removed
     *
     * @param e entity to check
     * @return true if e was removed, false if it wasn't removed at all
     */
    @SuppressWarnings({"ConstantConditions"})
    public static boolean isRemoved(@Nullable final Entity e) {
        if (e == null) {
            return true;
        }
        return TransientStoreUtil.isRemoved(e);
    }

    public static boolean isNew(@Nullable Entity e) {
        if (e == null) return false;
        e = TransientStoreUtil.reattach((TransientEntity) e);
        return e != null && ((TransientEntity) e).isNew();
    }

    public static boolean equals(Entity e1, Object e2) {
        if (e1 == e2) {
            return true;
        }

        return e1 != null && e1 instanceof TransientEntity && e1.equals(e2);
    }

    /**
     * Slow method! Use with care.
     *
     * @param entities iterable to index
     * @param i        queried element index
     * @return element at position i in entities iterable
     * @deprecated slow method. for testcases only.
     */
    public static Entity getElement(@NotNull Iterable<Entity> entities, int i) {
        if (logger.isWarnEnabled()) {
            logger.warn("Slow method getElementOfMultiple() was called!");
        }

        int j = 0;
        for (Entity e : entities) {
            if (i == j++) {
                return e;
            }
        }

        throw new IllegalArgumentException("Out of bounds: " + i);
    }

    public static boolean hasChanges(@NotNull TransientEntity e) {
        final TransientEntity entity = TransientStoreUtil.reattach(e);

        return entity != null && entity.hasChanges();
    }

    public static boolean hasChanges(@NotNull TransientEntity e, String property) {
        final TransientEntity entity = TransientStoreUtil.reattach(e);

        return entity != null && entity.hasChanges(property);
    }

    public static boolean hasChanges(@NotNull TransientEntity e, String[] properties) {
        final TransientEntity entity = TransientStoreUtil.reattach(e);

        if (entity != null) {
            for (String property : properties) {
                if (entity.hasChanges(property)) return true;
            }
        }
        return false;
    }

    public static boolean hasChangesExcepting(@NotNull TransientEntity e, String[] properties) {
        final TransientEntity entity = TransientStoreUtil.reattach(e);

        return entity != null && entity.hasChangesExcepting(properties);
    }
}
