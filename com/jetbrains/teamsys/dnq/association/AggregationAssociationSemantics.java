package com.jetbrains.teamsys.dnq.association;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Implements aggregation assocations management.<p>
 * 1-1: project.[1]leader <-> user.[1]leaderInProject <p>
 * 1-n: project[0..n].issues <-> issue[1].project <p>
 * n-n: project[0..n].assignees <-> user[0..n].assigneeInProjects <p>
 */
public class AggregationAssociationSemantics {

    /**
     * 1. parent.parentToChild = child <==> child.childToParent = parent
     * 2. parent.parentToChild = null <==> child.childToParent = null
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     * @param childToParentLinkName child to parent link name
     * @param child                 child
     */
    public static void setOneToOne(@Nullable Entity parent, @NotNull String parentToChildLinkName, @NotNull String childToParentLinkName, @Nullable Entity child) {
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
        ((TransientEntity) parent).addChild(parentToChildLinkName, childToParentLinkName, child);
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
        ((TransientEntity) child).removeFromParent(parentToChildLinkName, childToParentLinkName);
    }

    /**
     * parent.parentToChild.clear
     *
     * @param parent                parent
     * @param parentToChildLinkName parent to child link name
     */
    public static void clearOneToMany(@NotNull Entity parent, @NotNull String parentToChildLinkName) {
        //parent.parentToChild.clear
        ((TransientEntity) parent).clearChildren(parentToChildLinkName);
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
        if (parent == null) {
            ((TransientEntity) child).removeFromParent(parentToChildLinkName, childToParentLinkName);
        } else {
            // child.childToParent = parent
            ((TransientEntity) parent).addChild(parentToChildLinkName, childToParentLinkName, child);
        }
    }

    @Nullable
    public static Entity getParent(@NotNull Entity child) {
        return  ((TransientEntity)child).getParent();
    }

}
