package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.core.dataStructures.hash.HashMap;
import com.jetbrains.teamsys.database.BasePersistentClass;
import com.jetbrains.teamsys.database.Entity;

import java.util.Map;

public class EntityInstanceRegistry {
  private static final Map<String, BasePersistentClass> entityInstances = new HashMap<String, BasePersistentClass>();

  public static void setEntityInstance(String entityType, BasePersistentClass instance) {
    entityInstances.put(entityType, instance);
  }

  public static BasePersistentClass getEntityInstance(Entity e, String entityType) {
    return entityInstances.get((e == null ?
      entityType :
      e.getType()
    ));
  }
}
