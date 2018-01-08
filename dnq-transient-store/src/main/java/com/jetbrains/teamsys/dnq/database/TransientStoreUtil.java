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

import jetbrains.exodus.core.dataStructures.hash.LongHashSet;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.PersistentEntity;
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl;
import jetbrains.exodus.entitystore.iterate.EntityIterableBase;
import jetbrains.exodus.query.StaticTypedEntityIterable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Date: 18.12.2006
 * Time: 13:43:10
 *
 * @author Vadim.Gurov
 */
public class TransientStoreUtil {

    private static final Logger logger = LoggerFactory.getLogger(TransientStoreUtil.class);
    private static final LongHashSet POSTPONE_UNIQUE_INDICES = new LongHashSet(10);

    @Nullable
    public static TransientStoreSession getCurrentSession(@NotNull TransientEntity e) {
        return (TransientStoreSession) e.getStore().getCurrentTransaction();
    }

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
     */
    @Nullable
    @Deprecated
    public static TransientEntity reattach(@Nullable TransientEntity entity) {
        if (entity == null) {
            return null;
        }

        /*if (store == null) {
            throw new IllegalStateException("There's no current session entity store.");
        }*/

        TransientStoreSession s = entity.getStore().getThreadSession();

        if (s == null) {
            throw new IllegalStateException("There's no current session to attach transient entity to.");
        }


        return s.newLocalCopy(entity);
    }

    /**
     * Checks if entity entity was removed
     *
     * @return true if e was removed, false if it wasn't removed at all
     */
    public static boolean isRemoved(@NotNull Entity entity) {
        if (entity instanceof PersistentEntity) {
            return ((PersistentEntityStoreImpl) entity.getStore()).getLastVersion(entity.getId()) < 0;
        }
        return ((TransientEntityImpl) entity).getAndCheckThreadStoreSession().isRemoved(entity);
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

    public static void abort(@Nullable TransientStoreSession session) {
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

    @Nullable
    public static BasePersistentClassImpl getPersistentClassInstance(@NotNull final Entity entity) {
        return ((TransientEntityStoreImpl) entity.getStore()).getCachedPersistentClassInstance(entity.getType());
    }

    public static int getSize(@Nullable Iterable<Entity> it) {
        if (it == null) {
            return 0;
        }
        if (it instanceof StaticTypedEntityIterable) {
            it = ((StaticTypedEntityIterable) it).instantiate();
        }
        if (it == EntityIterableBase.EMPTY) {
            return 0;
        }
        if (it instanceof EntityIterable) {
            return (int) ((EntityIterable) it).size();
        }
        if (it instanceof Collection) {
            return ((Collection) it).size();
        }
        int result = 0;
        for (Entity ignored : it) {
            result++;
        }
        return result;
    }

    static String toString(@Nullable Set<String> strings) {
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

    static String toString(@Nullable Map map) {
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
