package com.jetbrains.teamsys.dnq.database.concurrent;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.TransientEntityStore;
import com.jetbrains.teamsys.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import jetbrains.springframework.configuration.runtime.ServiceLocator;

public class MyIssueImpl {

  public static Entity constructor(String summary) {
    Entity entity = ((TransientStoreSession)((TransientEntityStore) ServiceLocator.getBean("transientEntityStore")).getThreadSession()).newEntity("MyIssue");
    PrimitiveAssociationSemantics.set(entity, "summary", summary);
    return entity;
  }
  public static Entity constructor() {
    Entity entity = ((TransientStoreSession)((TransientEntityStore)ServiceLocator.getBean("transientEntityStore")).getThreadSession()).newEntity("MyIssue");
    return entity;
  }
}
