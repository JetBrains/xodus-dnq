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

/**
 * Implements undirected assocations management.<p>
 * 1-1: project.[1]leader <-> user.[1]leaderInProject <p>
 * 1-n: project[0..n].issues <-> issue[1].project <p>
 * n-n: project[0..n].assignees <-> user[0..n].assigneeInProjects <p>
 */
public class UndirectedAssociationSemantics {

    /**
     * 1. e1.e1Toe2LinkName = e2 <==> e2.e2Toe1LinkName = e1;
     * 2. e2.e2Toe1LinkName = null <==> e1.e1Toe1LinkName = null
     *
     * @param e1
     * @param e1Toe2LinkName
     * @param e2Toe1LinkName
     * @param e2
     */
    public static void setOneToOne(@Nullable Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @Nullable Entity e2) {
        e1 = TransientStoreUtil.reattach((TransientEntity) e1);
        e2 = TransientStoreUtil.reattach((TransientEntity) e2);

        if (e1 == null && e2 == null) {
            throw new IllegalArgumentException("Both entities can't be null.");
        }
        if (e1 == null) {
            ((TransientEntity) e2).setOneToOne(e2Toe1LinkName, e1Toe2LinkName, e1);
        } else {
            ((TransientEntity) e1).setOneToOne(e1Toe2LinkName, e2Toe1LinkName, e2);
        }
    }

    /**
     * one.oneToManyLinkName.add(many)
     *
     * @param one
     * @param many
     * @param oneToManyLinkName
     * @param manyToOneLinkName
     */
    public static void createOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName, @NotNull Entity many) {
        one = TransientStoreUtil.reattach((TransientEntity) one);
        many = TransientStoreUtil.reattach((TransientEntity) many);

        ((TransientEntity) many).setManyToOne(manyToOneLinkName, oneToManyLinkName, one);
    }

    /**
     * one.oneToManyLinkName.remove(many)
     *
     * @param one
     * @param many
     * @param oneToManyLinkName
     * @param manyToOneLinkName
     */
    public static void removeOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName, @NotNull Entity many) {
        one = TransientStoreUtil.reattach((TransientEntity) one);
        many = TransientStoreUtil.reattach((TransientEntity) many);

        ((TransientEntity) one).removeOneToMany(manyToOneLinkName, oneToManyLinkName, many);
    }

    /**
     * one.oneToManyLinkName.clear
     *
     * @param one
     * @param oneToManyLinkName
     * @param manyToOneLinkName
     */
    public static void clearOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName) {
        one = TransientStoreUtil.reattach((TransientEntity) one);

        //one.oneToManyLinkName.removeAll
        ((TransientEntity) one).clearOneToMany(manyToOneLinkName, oneToManyLinkName);
    }

    /**
     * many.manyToOneLinkName = one
     * many.manyToOneLinkName = null
     *
     * @param one
     * @param oneToManyLinkName
     * @param manyToOneLinkName
     * @param many
     */
    public static void setManyToOne(@Nullable Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName, @NotNull Entity many) {
        one = TransientStoreUtil.reattach((TransientEntity) one);
        many = TransientStoreUtil.reattach((TransientEntity) many);

        ((TransientEntity) many).setManyToOne(manyToOneLinkName, oneToManyLinkName, one);
    }

    /**
     * e1.e1Toe2LinkName.add(e2) <==> e2.e2Toe1LinkName.add(e1)
     *
     * @param e1
     * @param e2
     * @param e1Toe2LinkName
     * @param e2Toe1LinkName
     */
    public static void createManyToMany(@NotNull Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @NotNull Entity e2) {
        e1 = TransientStoreUtil.reattach((TransientEntity) e1);
        e2 = TransientStoreUtil.reattach((TransientEntity) e2);

        ((TransientEntity) e1).createManyToMany(e1Toe2LinkName, e2Toe1LinkName, e2);
    }

    /**
     * e1.e1Toe2LinkName.remove(e2) <==> e2.e2Toe1LinkName.remove(e1)
     *
     * @param e1
     * @param e2
     * @param e1Toe2LinkName
     * @param e2Toe1LinkName
     */
    public static void removeManyToMany(@NotNull Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName, @NotNull Entity e2) {
        // reattach is inside of removeToMany
        DirectedAssociationSemantics.removeToMany(e1, e1Toe2LinkName, e2);
        DirectedAssociationSemantics.removeToMany(e2, e2Toe1LinkName, e1);
    }

    /**
     * e1.e1Toe2LinkName.clear <==> e2.e2Toe1LinkName.clear
     *
     * @param e1
     * @param e1Toe2LinkName
     * @param e2Toe1LinkName
     */
    public static void clearManyToMany(@NotNull Entity e1, @NotNull String e1Toe2LinkName, @NotNull String e2Toe1LinkName) {
        e1 = TransientStoreUtil.reattach((TransientEntity) e1);

        ((TransientEntity) e1).clearManyToMany(e1Toe2LinkName, e2Toe1LinkName);
    }

}
