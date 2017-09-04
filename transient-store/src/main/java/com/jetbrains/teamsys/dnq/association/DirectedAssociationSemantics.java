package com.jetbrains.teamsys.dnq.association;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.database.TransientEntity;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


/**
 * 1:
 * n:
 */
public class DirectedAssociationSemantics {

    /**
     * user.role = role
     * user.role = null
     *
     * @param source
     * @param linkName
     * @param target
     */
    public static void setToOne(Entity source, @NotNull String linkName, @Nullable Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;

        ((TransientEntity) source).setToOne(linkName, TransientStoreUtil.reattach((TransientEntity) target));
    }

    /**
     * project.users.add(user)
     *
     * @param source
     * @param linkName
     * @param target
     */
    public static void createToMany(Entity source, @NotNull String linkName, Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;
        target = TransientStoreUtil.reattach((TransientEntity) target);
        if (target == null) return;

        source.addLink(linkName, target);
    }

    /**
     * project.users.remove(user)
     *
     * @param source
     * @param linkName
     * @param target
     */
    public static void removeToMany(Entity source, @NotNull String linkName, Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;
        target = TransientStoreUtil.reattach((TransientEntity) target);
        if (target == null) return;

        source.deleteLink(linkName, target);
    }

    /**
     * project.users.clear
     *
     * @param source
     * @param linkName
     */
    public static void clearToMany(Entity source, @NotNull String linkName) {
        source = TransientStoreUtil.reattach((TransientEntity) source);
        if (source == null) return;

        source.deleteLinks(linkName);
    }

}
