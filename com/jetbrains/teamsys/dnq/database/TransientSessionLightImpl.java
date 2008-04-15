package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.TransientStoreSession;

/**
 * TODO: implement.
 * requirements - store less cached data in memory
 */
public class TransientSessionLightImpl extends TransientSessionImpl {

  public TransientSessionLightImpl(TransientEntityStoreImpl store, String name, Object id, boolean checkEntityVersionOnCommit) {
    super(store, name, id, checkEntityVersionOnCommit);
  }
  
}
