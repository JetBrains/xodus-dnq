package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientChangesTracker;
import jetbrains.exodus.database.TransientEntityChange;
import jetbrains.exodus.database.TransientEntityStore;

import java.util.Set;

public interface IEventsMultiplexer {
    void flushed(TransientChangesTracker oldChangesTracker, Set<TransientEntityChange> changesDescription);

    void onClose(TransientEntityStore transientEntityStore);
}
