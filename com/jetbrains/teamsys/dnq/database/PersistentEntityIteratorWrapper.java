package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityIterator;
import com.jetbrains.teamsys.database.TransientStoreSession;
import com.jetbrains.teamsys.database.EntityId;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;

class PersistentEntityIteratorWrapper implements EntityIterator {

  @NotNull
  protected final EntityIterator source;
  private TransientStoreSession session;

  PersistentEntityIteratorWrapper(@NotNull final EntityIterator source, final TransientStoreSession session) {
    this.source = source;
    this.session = session;
  }

  public boolean hasNext() {
    return source.hasNext();
  }

  public Entity next() {
    //TODO: do not save in session?
    final Entity persistentEntity = source.next();
    return (persistentEntity != null) ? session.newEntity(persistentEntity) : null;
  }

  public void remove() {
    source.remove();
  }

  public EntityId nextId() {
    return source.nextId();
  }

  public boolean skip(int number) {
    return source.skip(number);
  }

}
