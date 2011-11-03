package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates error message by incoming Entity and EntityIterable
 * @param linkedEntities - entities which have outcoming link with target
 * @param target - entity we are trying to delete
 */
public interface MessageBuilder {

    @NotNull
    String build(@Nullable Iterable<Entity> linkedEntities, @Nullable Entity _this, boolean hasMore);
}
