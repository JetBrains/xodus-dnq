package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.EntityIterable;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.database.exceptions.EntityRemovedInDatabaseException;
import org.junit.Assert;

public class ToIdFromIdTest extends AbstractEntityStoreAwareTestCase {

  public void testPersistentEntity() {
    createData();
    String id = getSomeEntityId();
    tryToRestoreById(id);
  }

  public void testFindByIncorrectId() {
    createData();
    tryToRestoreById("0-0-0"); //ok
    findByIncorrectId("0-1-0"); // bad id!
  }

  private void findByIncorrectId(String id) {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {

      Entity user = transientStoreSession.getEntity(transientStoreSession.toEntityId(id));

      fail();

    } catch (EntityRemovedInDatabaseException e) {

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }
  }

  private void tryToRestoreById(String id) {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {
                             
      Entity user = transientStoreSession.getEntity(transientStoreSession.toEntityId(id));

      assertNotNull(user);

   } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }
  }

  private String getSomeEntityId() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {

      EntityIterable users = transientStoreSession.find("User", "login", "user");

      assertTrue(users.size() == 1);

      Entity user = users.iterator().next();

      assertNotNull(user);

      return user.getId().toString();

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }
  }

  private void createData() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {

      Entity user = transientStoreSession.newEntity("User");
      user.setProperty("login", "user");
      user.setProperty("password", "user");

/*
      user = transientStoreSession.newEntity("User");
      user.setProperty("login", "user1");
      user.setProperty("password", "user1");
*/

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }

  }
  
}
