package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashSet;
import com.jetbrains.teamsys.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

// TODO: move this class to the associations semantics package

public class EntityOperations {

    private static final Log log = LogFactory.getLog(EntityOperations.class);

    private EntityOperations() {
    }

    public static void remove(final Entity e) {
        /* two-phase remove:
           1. call destructors
           2. remove links and entities
        */

        remove(e, true, new HashSet<Entity>());
        remove(e, false, null);
    }

    static void remove(final Entity e, boolean callDestructorPhase, Set<Entity> destructorCalled) {
        if (e == null || ((TransientEntity) e).isRemoved()) return;
        TransientEntity reattached = TransientStoreUtil.reattach((TransientEntity) e);
        if (reattached == null) return;

        if (callDestructorPhase && destructorCalled.contains(e)) return;

        TransientEntityStore store = (TransientEntityStore) reattached.getStore();

        ModelMetaData md = store.getModelMetaData();
        if (md != null) {
            // cascade delete
            EntityMetaData emd = md.getEntityMetaData(reattached.getType());
            if (emd != null) {
                if (callDestructorPhase) {
                    emd.getInstance(reattached).destructor(reattached);
                    destructorCalled.add(reattached);
                }
                // remove associations and cascade delete
                TransientStoreSession storeSession = (TransientStoreSession) store.getThreadSession();
                if (storeSession == null) {
                    throw new IllegalStateException("No current transient session!");
                }
                ConstraintsUtil.processOnDeleteConstraints(storeSession, reattached, emd, md, callDestructorPhase, destructorCalled);
            }
        }

        if (!callDestructorPhase) {
            // delete itself; the check is performed, because onDelete constraints could already delete entity 'e'
            if (!reattached.isRemoved()) reattached.delete();
        }
    }


    public static List<Entity> getHistory(@NotNull Entity e) {
        final Entity entity = TransientStoreUtil.reattach((TransientEntity) e);

        return entity == null ? Collections.<Entity>emptyList() : entity.getHistory();
    }

    @SuppressWarnings({"ConstantConditions"})
    public static boolean isRemoved(@NotNull final Entity e) {
        return e == null || ((TransientEntity) e).isRemoved() || TransientStoreUtil.reattach((TransientEntity) e) == null;
    }

    public static boolean isNew(@Nullable Entity e) {
        if (e == null) return false;
        e = TransientStoreUtil.reattach((TransientEntity) e);
        return e != null && ((TransientEntity) e).isNew();
    }

    public static int getVersion(@NotNull Entity e) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        return e == null ? -1 : e.getVersion();
    }

    public static Entity getPreviousVersion(@NotNull Entity e) {
        final Entity entity = TransientStoreUtil.reattach((TransientEntity) e);

        return entity == null ? null : entity.getPreviousVersion();
    }

    public static Entity getNextVersion(@NotNull Entity e) {
        final Entity entity = TransientStoreUtil.reattach((TransientEntity) e);

        return entity == null ? null : entity.getNextVersion();
    }

    public static boolean equals(Entity e1, Object e2) {
        if (e1 == e2) {
            return true;
        }

        if (e1 != null && e2 != null && !(e1 instanceof TransientEntity) && !(e2 instanceof TransientEntity)) {
            return false;
        }

        // null == removed || removed == null
        if ((e1 == null && EntityOperations.isRemoved((TransientEntity) e2)) ||
                (e1 != null && EntityOperations.isRemoved(e1) && e2 == null)) {
            return true;
        }

        //no need to reattach - it's ok to compare entities from different sessions, Entity.equals should handle this situation itself
        //e1 = TransientStoreUtil.reattach((TransientEntity) e1);
        //e2 = TransientStoreUtil.reattach((TransientEntity) e2);

        return e1 != null && e1.equals(e2);
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
        if (log.isWarnEnabled()) {
            log.warn("Slow method getElementOfMultiple() was called!");
        }

        if (entities instanceof EntityIterable) {
            final EntityIterator it = ((EntityIterable) entities).skip(i).iterator();
            if (it.hasNext()) {
                return it.next();
            }
        } else {
            int j = 0;
            for (Entity e : entities) {
                if (i == j++) {
                    return e;
                }
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

        if (entity == null) {
            return false;
        } else {
            Map<String, LinkChange> changesLinks = entity.getTransientStoreSession().getTransientChangesTracker().getChangedLinksDetailed(entity);
            Map<String, PropertyChange> changesProperties = entity.getTransientStoreSession().getTransientChangesTracker().getChangedPropertiesDetailed(entity);

            int found = 0;
            int changed;
            if (changesLinks == null && changesProperties == null) {
                return false;
            } else {
                for (String property : properties) {
                    // all properties have to be changed
                    if (entity.hasChanges(property)) found++;
                }
                if (changesLinks == null) {
                    changed = changesProperties.size();
                } else if (changesProperties == null) {
                    changed = changesLinks.size();
                } else {
                    changed = changesLinks.size() + changesProperties.size();
                }
                return changed - found > 0;
            }
        }
    }
}
