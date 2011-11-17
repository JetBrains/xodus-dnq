package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.StoreSession;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import jetbrains.mps.internal.collections.runtime.ListSequence;

/**
 * @author Maxim.Mazin at date: 11.01.2007 time: 11:29:28
 */
public class SearchByPropertyTest extends AbstractEntityStoreAwareTestCase {

  public void testSearchByProperty() {
    createGuest();
    assertEquals("Unexpected number of guests", 1, numberOfGuests());
    assertTrue("Guest doesn't exist", guestExists());
  }

  public void createGuest() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession("createData");
    try {
      StoreSession storeSession = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession();
      assert storeSession != null;
      TransientStoreSession session = ((TransientStoreSession) storeSession.getCurrentTransaction());
      storeSession = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession();
      assert session != null && storeSession != null;
      Entity u = session.addSessionLocalEntity("u", (storeSession.newEntity("User")));
      PrimitiveAssociationSemantics.set(u, "login", "guest");
      PrimitiveAssociationSemantics.set(u, "password", "guest");
    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }

  public boolean guestExists() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession("createData");
    try {
      StoreSession session = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession();
      assert session != null;
      return !ListSequence.fromIterable(session.find("User", "login", "guest")).isEmpty();
    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }

  public long numberOfGuests() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession("createData");
    try {
      StoreSession session = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession();
      assert session != null;
      return session.getAll("User").size();
    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }
}
