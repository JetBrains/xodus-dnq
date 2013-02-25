package com.jetbrains.teamsys.dnq.association;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntity;
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
        //one.oneToManyLinkName.remove(many)
        DirectedAssociationSemantics.removeToMany(one, oneToManyLinkName, many);
        DirectedAssociationSemantics.setToOne(many, manyToOneLinkName, null);
    }

    /**
     * one.oneToManyLinkName.clear
     *
     * @param one
     * @param oneToManyLinkName
     * @param manyToOneLinkName
     */
    public static void clearOneToMany(@NotNull Entity one, @NotNull String oneToManyLinkName, @NotNull String manyToOneLinkName) {
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
        ((TransientEntity) e1).clearManyToMany(e1Toe2LinkName, e2Toe1LinkName);
    }

}
