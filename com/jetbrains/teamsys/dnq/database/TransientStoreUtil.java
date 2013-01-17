package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.hash.LongHashSet;
import jetbrains.exodus.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Date: 18.12.2006
 * Time: 13:43:10
 *
 * @author Vadim.Gurov
 */
public class TransientStoreUtil {

    private static final Log log = LogFactory.getLog(TransientStoreUtil.class);
    private static final LongHashSet POSTPONE_UNIQUE_INDICES = new LongHashSet(10);

    public static boolean isPostponeUniqueIndexes() {
        final long id = Thread.currentThread().getId();
        synchronized (POSTPONE_UNIQUE_INDICES) {
            return POSTPONE_UNIQUE_INDICES.contains(id);
        }
    }

    public static void setPostponeUniqueIndexes(boolean postponeUniqueIndexes) {
        final long id = Thread.currentThread().getId();
        if (postponeUniqueIndexes) {
            synchronized (POSTPONE_UNIQUE_INDICES) {
                POSTPONE_UNIQUE_INDICES.add(id);
            }
        } else {
            synchronized (POSTPONE_UNIQUE_INDICES) {
                POSTPONE_UNIQUE_INDICES.remove(id);
            }
        }
    }

    /**
     * Attach entity to current session if possible.
     *
     * @param entity
     * @return
     */
    @Nullable
    public static TransientEntity reattach(@Nullable TransientEntity entity) {
        if (entity == null) {
            return null;
        }

        /*if (store == null) {
            throw new IllegalStateException("There's no current session entity store.");
        }*/

        TransientStoreSession s = (TransientStoreSession) ((TransientEntityStore)entity.getStore()).getThreadSession();

        if (s == null) {
            throw new IllegalStateException("There's no current session to attach transient entity to.");
        }


        return s.newLocalCopy(entity);
    }

    /**
     * Checks if entity entity was removed
     *
     * @param entity
     * @return true if e was removed, false if it wasn't removed at all
     */
    public static boolean isRemoved(@NotNull Entity entity) {
        if (entity instanceof PersistentEntity) {
            return ((PersistentEntityStore) entity.getStore()).getLastVersion(entity.getId()) < 0;
        }

        TransientStoreSession s = (TransientStoreSession) ((TransientEntityStore)entity.getStore()).getThreadSession();

        if (s == null) {
            throw new IllegalStateException("There's no current session to attach transient entity to.");
        }

        return s.isRemoved(entity);
    }

    /**
     * Attach entity to current session if possible.
     *
     * @return
     */
    public static TransientEntity readonlyCopy(@NotNull TransientEntityChange change) {
        /*if (store == null) {
            throw new IllegalStateException("There's no current session entity store.");
        }*/

        TransientStoreSession s = (TransientStoreSession) ((TransientEntityStore)change.getTransientEntity().getStore()).getThreadSession();

        if (s == null) {
            throw new IllegalStateException("There's no current session to attach transient entity to.");
        }


        return s.newReadonlyLocalCopy(change);
    }

    public static void commit(@Nullable TransientStoreSession s) {
        if (s != null && s.isOpened()) {
            try {
                s.commit();
            } catch (Throwable e) {
                abort(e, s);
            }
        }
    }

    //remove me!!!
    public static void suspend(@Nullable TransientStoreSession s) {

    }

    public static void abort(TransientStoreSession session) {
        if (session != null && session.isOpened()) {
            session.abort();
        }
    }

    public static void abort(@NotNull Throwable e, @Nullable TransientStoreSession s) {
        abort(s);

        if (e instanceof Error) {
            throw (Error) e;
        }

        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }

        throw new RuntimeException(e);
    }

    public static BasePersistentClassImpl getPersistentClassInstance(@Nullable final Entity entity, final String defaultType) {
        final String entityType = entity == null ? defaultType : entity.getType();
        return ((TransientEntityStoreImpl) entity.getStore()).getCachedPersistentClassInstance(entityType);
    }

    public static BasePersistentClassImpl getPersistentClassInstance(@Nullable final Entity entity, @NotNull final EntityMetaData entityMetaData) {
        final String entityType = entity == null ? entityMetaData.getType() : entity.getType();
        return ((TransientEntityStoreImpl) entity.getStore()).getCachedPersistentClassInstance(entityType);
    }

    static String toString(Set<String> strings) {
        if (strings == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : strings) {
            if (!first) {
                sb.append(",");
            }

            sb.append(s);

            first = false;
        }

        return sb.toString();
    }

    static String toString(Map map) {
        if (map == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object s : map.keySet()) {
            if (!first) {
                sb.append(",");
            }

            sb.append(s).append(":").append(map.get(s));

            first = false;
        }

        return sb.toString();
    }

}
