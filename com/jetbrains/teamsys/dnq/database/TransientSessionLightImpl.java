package com.jetbrains.teamsys.dnq.database;

/**
 * TODO: implement.
 * requirements - store less cached data in memory
 */
public class TransientSessionLightImpl extends TransientSessionImpl {

  public TransientSessionLightImpl(TransientEntityStoreImpl store, String name, Object id) {
    super(store, name, id);
  }
  
}
