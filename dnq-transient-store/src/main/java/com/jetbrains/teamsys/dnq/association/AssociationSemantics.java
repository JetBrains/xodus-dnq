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
package com.jetbrains.teamsys.dnq.association;

import com.jetbrains.teamsys.dnq.database.EntityOperations;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import com.jetbrains.teamsys.dnq.database.UniversalEmptyEntityIterable;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.EntityIterator;
import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.iterate.EntityIterableBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 */
public class AssociationSemantics {

    /**
     * To one association end getter.
     * Supports nullable objects - input entity may be null
     */
    @Nullable
    public static Entity getToOne(@Nullable Entity e, @NotNull String linkName) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        // nullable objects support
        if (e == null) {
            return null;
        }

        return e.getLink(linkName);
    }

    @NotNull
    public static Iterable<Entity> getToMany(@Nullable Entity e, @NotNull String linkName) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (e == null) {
            return EntityIterableBase.EMPTY;
        }

        return e.getLinks(linkName);
    }

    @NotNull
    public static Iterable<Entity> getToMany(@Nullable Entity e, @NotNull Set<String> linkNames) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (e == null) {
            return UniversalEmptyEntityIterable.INSTANCE;
        }

        return e.getLinks(linkNames);
    }

    /**
     * Returns copy of {@link #getToMany(Entity, String)} iterable
     */
    @NotNull
    public static List<Entity> getToManyList(@NotNull Entity e, @NotNull String linkName) {
        List<Entity> res = new ArrayList<Entity>();

        for (Entity entity : getToMany(e, linkName)) {
            res.add(entity);
        }

        return res;
    }

    /**
     * Returns persistent iterable if possible
     */
    @NotNull
    public static Iterable<Entity> getToManyPersistentIterable(@NotNull Entity e, @NotNull String linkName) {
        e = TransientStoreUtil.reattach((TransientEntity) e);

        if (e == null) {
            return EntityIterableBase.EMPTY;
        }

        // can't return persistent iterable for new transient entity
        if (((TransientEntity) e).isNew()) {
            //throw new IllegalStateException("1111");
            return e.getLinks(linkName);
        }

        return ((TransientEntity) e).getPersistentEntity().getLinks(linkName);
    }

    /**
     * Returns links size
     */
    public static long getToManySize(@NotNull Entity e, @NotNull String linkName) {
        if (e instanceof TransientEntity) {
            e = TransientStoreUtil.reattach((TransientEntity) e);

            if (e == null) {
                return 0;
            }

            return ((TransientEntity) e).getLinksSize(linkName);
        }

        return TransientStoreUtil.getSize(e.getLinks(linkName));
    }

    /**
     * Returns added links
     */
    @NotNull
    public static EntityIterable getAddedLinks(@NotNull TransientEntity e, @NotNull String name) {
        e = TransientStoreUtil.reattach(e);
        if (e != null) {
            return e.getAddedLinks(name);
        } else {
            return EntityIterableBase.EMPTY;
        }
    }

    /**
     * Returns removed links
     */
    public static EntityIterable getRemovedLinks(@NotNull TransientEntity e, @NotNull String name) {
        e = TransientStoreUtil.reattach(e);
        if (e != null) {
            return e.getRemovedLinks(name);
        } else {
            return EntityIterableBase.EMPTY;
        }
    }

    public static EntityIterable getAddedLinks(@NotNull TransientEntity e, @NotNull Set<String> linkNames) {
        e = TransientStoreUtil.reattach(e);
        if (e != null) {
            return e.getAddedLinks(linkNames);
        } else {
            return EntityIterableBase.EMPTY;
        }
    }

    public static EntityIterable getRemovedLinks(@NotNull TransientEntity e, @NotNull Set<String> linkNames) {
        e = TransientStoreUtil.reattach(e);
        if (e != null) {
            return e.getRemovedLinks(linkNames);
        } else {
            return EntityIterableBase.EMPTY;
        }
    }

    /**
     * Returns previous link value
     */
    @Nullable
    public static Entity getOldValue(@NotNull TransientEntity e, @NotNull String name) {
        if (EntityOperations.isRemoved(e)) {
            final TransientEntityStore transientStore = e.getStore();
            final Entity result = ((PersistentEntityStore) transientStore.getPersistentStore()).getEntity(e.getId()).getLink(name);
            if (result == null) {
                return null;
            }
            final TransientStoreSession session = transientStore.getThreadSession();
            if (session == null) {
                throw new IllegalStateException("No current transient session!");
            }
            return session.newEntity(result);
        }
        final EntityIterator itr = getRemovedLinks(e, name).iterator();
        if (itr.hasNext()) {
            return itr.next();
        }
        return null;
    }

}
