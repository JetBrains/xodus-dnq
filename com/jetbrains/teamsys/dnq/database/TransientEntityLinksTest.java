package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import java.util.Iterator;

/**
 * Date: 28.12.2006
 * Time: 12:56:41
 *
 * @author Vadim.Gurov
 */
public class TransientEntityLinksTest extends AbstractEntityStoreAwareTestCase {

  public void testTransientGetLinks() {
    createData();
    checkTransientGetLinks();
    checkTransientGetLinks2();
  }

  private void createData() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {

      Entity user = transientStoreSession.newEntity("User");
      user.setProperty("login", "user");
      user.setProperty("password", "user");

      user = transientStoreSession.newEntity("User");
      user.setProperty("login", "user1");
      user.setProperty("password", "user1");

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }

  }

  private void checkTransientGetLinks() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {
      Entity user = getFirst(transientStoreSession.find("User", "login", "user"));

      assertNotNull(user);

      Entity issue = transientStoreSession.newEntity("Issue");

      DirectedAssociationSemantics.setToOne(issue, "reporter", user);

      assertEquals(user, AssociationSemantics.getToOne(issue, "reporter"));

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }
  }

  private void checkTransientGetLinks2() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientStoreSession = store.beginSession("");

    try {
      Entity user1 = getFirst(transientStoreSession.find("User", "login", "user1"));

      assertNotNull(user1);

      Entity issue = getFirst(transientStoreSession.getAll("Issue"));

      assertNotNull(issue);

      DirectedAssociationSemantics.setToOne(issue, "reporter", user1);

      assertEquals(user1, AssociationSemantics.getToOne(issue, "reporter"));

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientStoreSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientStoreSession);
    }
  }

  private static Entity getFirst(Iterable<Entity> input) {
    Iterator<Entity> iterator = input.iterator();
    if (iterator.hasNext()) {
      return iterator.next();
    }

    return null;
  }

}
