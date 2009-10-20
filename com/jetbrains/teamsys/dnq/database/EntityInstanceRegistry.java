package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.database.Entity;

import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: morj
 * Date: Oct 20, 2009
 * Time: 1:14:52 PM
 * To change this template use File | Settings | File Templates.
 */
public class EntityInstanceRegistry {
  private static Map<String, Object> entityInstances = new HashMap<String, Object>();

  public static void setEntityInstance(String entityType, Object instance) {
    entityInstances.put(entityType, instance);
  }

  public static Object getEntityInstance(String entityType) {
    return entityInstances.get(entityType);
  }

  public static Object getEntityInstance(Entity e, String entityType) {
    return getEntityInstance((e == null ?
      entityType :
      e.getType()
    ));
  }
}
