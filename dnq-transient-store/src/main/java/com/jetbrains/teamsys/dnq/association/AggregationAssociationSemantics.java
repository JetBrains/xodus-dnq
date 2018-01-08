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
 * Implements aggregation assocations management.&lt;p&gt;
 * 1-1: project.[1]leader &lt;-&gt; user.[1]leaderInProject &lt;p&gt;
 * 1-n: project[0..n].issues &lt;-&gt; issue[1].project &lt;p&gt;
 * n-n: project[0..n].assignees &lt;-&gt; user[0..n].assigneeInProjects &lt;p&gt;
 */
public class AggregationAssociationSemantics {

    /**
     * 1. parent.parentToChild = child &lt;==&gt; child.childToParent = parent
     * 2. parent.parentToChild = null &lt;==&gt; child.childToParent = null
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    public static void setOneToOne(@Nullable Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @Nullable Entity child) {
        parent = TransientStoreUtil.reattach((TransientEntity) parent);
        child = TransientStoreUtil.reattach((TransientEntity) child);

        if (child == null && parent == null) {
            throw new IllegalArgumentException("Both entities can't be null.");
        }
        if (parent == null) {
            ((TransientEntity) child).removeFromParent(parentToChildLinkName, childToParentLinkName);
        } else if (/* parent != null && */ child == null) {
            ((TransientEntity) parent).removeChild(parentToChildLinkName, childToParentLinkName);
        } else { /* parent != null && child != null */
            ((TransientEntity) parent).setChild(parentToChildLinkName, childToParentLinkName, child);
        }
    }

    /**
     * parent.parentToChild.add(child)
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    public static void createOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
        parent = TransientStoreUtil.reattach((TransientEntity) parent);
        child = TransientStoreUtil.reattach((TransientEntity) child);

        if (parent != null && child != null) {
            ((TransientEntity) parent).addChild(parentToChildLinkName, childToParentLinkName, child);
        }
    }

    /**
     * parent.parentToChild.remove(child)
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    public static void removeOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
        parent = TransientStoreUtil.reattach((TransientEntity) parent);
        child = TransientStoreUtil.reattach((TransientEntity) child);
        if (child != null) {
            ((TransientEntity) child).removeFromParent(parentToChildLinkName, childToParentLinkName);
        }
    }

    /**
     * parent.parentToChild.clear
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     */
    public static void clearOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName) {
        parent = TransientStoreUtil.reattach((TransientEntity) parent);

        //parent.parentToChild.clear
        if (parent != null) {
            ((TransientEntity) parent).clearChildren(parentToChildLinkName);
        }
    }

    /**
     * child.childToParent = parent
     * child.childToParent = null
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    public static void setManyToOne(@Nullable Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @NotNull Entity child) {
        parent = TransientStoreUtil.reattach((TransientEntity) parent);
        child = TransientStoreUtil.reattach((TransientEntity) child);

        if (child != null) {
            if (parent == null) {
                ((TransientEntity) child).removeFromParent(parentToChildLinkName, childToParentLinkName);
            } else {
                // child.childToParent = parent
                ((TransientEntity) parent).addChild(parentToChildLinkName, childToParentLinkName, child);
            }
        }
    }

    @Nullable
    public static Entity getParent(@NotNull Entity child) {
        child = TransientStoreUtil.reattach((TransientEntity) child);

        if (child != null) {
            return ((TransientEntity) child).getParent();
        } else {
            return null;
        }
    }

}
