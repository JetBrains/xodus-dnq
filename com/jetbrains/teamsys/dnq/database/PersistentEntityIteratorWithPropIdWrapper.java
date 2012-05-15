package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.EntityId;
import jetbrains.exodus.database.EntityIterator;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.database.impl.iterate.EntityIteratorWithPropId;
import org.jetbrains.annotations.NotNull;

class PersistentEntityIteratorWithPropIdWrapper implements EntityIteratorWithPropId {

    @NotNull
    protected final EntityIteratorWithPropId source;
    private final TransientStoreSession session;

    PersistentEntityIteratorWithPropIdWrapper(@NotNull final EntityIteratorWithPropId source, final TransientStoreSession session) {
        this.source = source;
        this.session = session;
    }

    public boolean hasNext() {
        return source.hasNext();
    }

    public Entity next() {
        //TODO: do not save in session?
        final Entity persistentEntity = source.next();
        if (persistentEntity == null) {
            return null;
        }
        final int version = source.getCurrentVersion();
        return version < 0 ? session.newEntity(persistentEntity) : session.newEntity(persistentEntity, version);
    }

    public String currentLinkName() {
        return source.currentLinkName();
    }

    public void remove() {
        source.remove();
    }

    public EntityId nextId() {
        return source.nextId();
    }

    public boolean dispose() {
        return source.dispose();
    }

    @Override
    public int getCurrentVersion() {
        return source.getCurrentVersion();
    }

    public boolean skip(int number) {
        return source.skip(number);
    }

    public boolean shouldBeDisposed() {
        return source.shouldBeDisposed();  //TODO: revisit EntityIterator interface and remove these stub method
    }
}
