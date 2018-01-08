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

import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.entitystore.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class DirectedAssociationSemantics {

    /**
     * user.role = role
     * user.role = null
     */
    public static void setToOne(@Nullable Entity source, @NotNull String linkName, @Nullable Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;

        ((TransientEntity) source).setToOne(linkName, TransientStoreUtil.reattach((TransientEntity) target));
    }

    /**
     * project.users.add(user)
     */
    public static void createToMany(@Nullable Entity source, @NotNull String linkName, @Nullable Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;
        target = TransientStoreUtil.reattach((TransientEntity) target);
        if (target == null) return;

        source.addLink(linkName, target);
    }

    /**
     * project.users.remove(user)
     */
    public static void removeToMany(@Nullable Entity source, @NotNull String linkName, @Nullable Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;
        target = TransientStoreUtil.reattach((TransientEntity) target);
        if (target == null) return;

        source.deleteLink(linkName, target);
    }

    /**
     * project.users.clear
     */
    public static void clearToMany(@Nullable Entity source, @NotNull String linkName) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;

        source.deleteLinks(linkName);
    }

}
