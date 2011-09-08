package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates error message by incoming Entity and EntityIterable
 */
public interface MessageBuildser {

    @NotNull
    String build(@Nullable Iterable<Entity> enitites, @Nullable Entity _this);
}
