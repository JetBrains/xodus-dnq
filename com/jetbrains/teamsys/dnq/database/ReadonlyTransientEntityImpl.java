package com.jetbrains.teamsys.dnq.database;

import org.jetbrains.annotations.NotNull;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.TransientStoreSession;

/**
 * Created by IntelliJ IDEA.
 * User: vadim
 * Date: Nov 11, 2008
 * Time: 1:02:51 PM
 * To change this template use File | Settings | File Templates.
 */
public class ReadonlyTransientEntityImpl extends TransientEntityImpl {

    ReadonlyTransientEntityImpl(@NotNull Entity persistentEntity, @NotNull TransientStoreSession session) {
        super(persistentEntity, session);
    }
}
