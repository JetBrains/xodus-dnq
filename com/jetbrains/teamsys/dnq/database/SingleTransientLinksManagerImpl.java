package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.NanoSet;
import jetbrains.exodus.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Manages single link of TransinetEntity.
 */
class SingleTransientLinksManagerImpl implements TransientLinksManager {

    private static Log log = LogFactory.getLog(SingleTransientLinksManagerImpl.class);

    private final String linkName;
    private final TransientEntityImpl owner;
    private TransientEntity addedTarget = null;
    private TransientEntity removedTarget = null;
    private TransientEntity currentTarget = null;
    private TransientEntity loadedTarget = null;
    private State state;

    SingleTransientLinksManagerImpl(@NotNull String linkName, TransientEntityImpl owner) {
        this.linkName = linkName;
        this.owner = owner;
        switch (owner.getState()) {
            case Temporary:
            case New:
                this.state = State.LinksLoaded;
                break;
            default:
                this.state = State.LinksNotLoaded;

        }
    }

    public void setLink(@NotNull TransientEntity target) {
        if (state == State.LinksNotLoaded) loadLink();

        currentTarget = target;

        if (!target.equals(loadedTarget)) {
            removedTarget = loadedTarget;
            addedTarget = target;
        } else {
            removedTarget = null;
            addedTarget = null;
        }

        if (owner.getState() == AbstractTransientEntity.State.Temporary) return;

        TransientChangesTracker tracker = owner.getTransientStoreSession().getTransientChangesTracker();
        tracker.linkSet(owner, linkName, target);
        tracker.registerLinkChanges(owner, linkName, getAdded(), getRemoved());
    }

    public Set<TransientEntity> getAdded() {
        return addedTarget == null ? null : new NanoSet<TransientEntity>(addedTarget);
    }

    public Set<TransientEntity> getRemoved() {
        return removedTarget == null ? null : new NanoSet<TransientEntity>(removedTarget);
    }

    @Nullable
    public Entity getLink() {
        switch (owner.getState()) {
            case New:
            case Temporary:
            case Saved:
            case SavedNew:
                if (state == State.LinksNotLoaded) loadLink();
                return currentTarget;
        }

        throw new IllegalStateException();
    }

    public void deleteLink(@NotNull TransientEntity entity) {
        deleteLinks();
    }

    public void deleteLinks() {
        if (state == State.LinksNotLoaded) loadLink();

        removedTarget = loadedTarget;
        addedTarget = null;
        currentTarget = null;

        if (owner.getState() == AbstractTransientEntity.State.Temporary) return;

        TransientChangesTracker tracker = owner.getTransientStoreSession().getTransientChangesTracker();
        tracker.linksDeleted(owner, linkName);
        tracker.registerLinkChanges(owner, linkName, getAdded(), getRemoved());
    }

    public long getLinksSize() {
        if (state == State.LinksNotLoaded) loadLink();
        return currentTarget == null ? 0 : 1;
    }

    public void flushed() {
        if (this.state == State.LinksNotLoaded) loadLink();
        loadedTarget = currentTarget;
        addedTarget = null;
        removedTarget = null;
    }

    @NotNull
    public EntityIterable getLinks() {
        throw new IllegalStateException("getLinks is not supported for single link");
    }

    public void addLink(@NotNull TransientEntity entity) {
        throw new IllegalStateException("addLink is not supported for single link");
    }

    private void loadLink() {
        Entity e = owner.getPersistentEntity().getLink(linkName);
        if(e == null) {
            loadedTarget = null;
        } else {
            loadedTarget = e.getVersion() < 0 ? null : owner.getTransientStoreSession().newEntity(e);
        }
        currentTarget = loadedTarget;
        this.state = State.LinksLoaded;
    }
}
