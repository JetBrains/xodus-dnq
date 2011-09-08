package com.jetbrains.teamsys.dnq.association;

import jetbrains.exodus.database.Entity;
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

        if (source == null) {
            return;
        }

        target = TransientStoreUtil.reattach((TransientEntity) target);

        // find old target
        Entity oldTarget = source.getLink(linkName);

        // compare new and old targets
        if (oldTarget == null && target == null) {
            return;
        }
        if (oldTarget != null && oldTarget.equals(target)) {
            return;
        }

        // set new target
        if (target == null) {
            source.deleteLinks(linkName);
        } else {
            ((TransientEntity) source).setLink(linkName, target);
        }
    }

    /**
     * project.users.add(user)
     *
     * @param source
     * @param linkName
     * @param target
     */
    public static void createToMany(@NotNull Entity source, @NotNull String linkName, @NotNull Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);

        if (source == null) {
            return;
        }

        target = TransientStoreUtil.reattach((TransientEntity) target);

        if (target == null) {
            return;
        }

        source.addLink(linkName, target);
    }

    /**
     * project.users.remove(user)
     *
     * @param source
     * @param linkName
     * @param target
     */
    public static void removeToMany(@NotNull Entity source, @NotNull String linkName, @NotNull Entity target) {
        source = TransientStoreUtil.reattach((TransientEntity) source);

        if (source == null) {
            return;
        }

        target = TransientStoreUtil.reattach((TransientEntity) target);

        if (target == null) {
            return;
        }

        source.deleteLink(linkName, target);
    }

    /**
     * project.users.clear
     *
     * @param source
     * @param linkName
     */
    public static void clearToMany(@NotNull Entity source, @NotNull String linkName) {
        source = TransientStoreUtil.reattach((TransientEntity) source);

        if (source == null) {
            return;
        }

        for (Entity target : AssociationSemantics.getToManyList(source, linkName)) {
            source.deleteLink(linkName, target);
        }
    }

}
