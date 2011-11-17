package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;

/**
 * Date: 07.12.2006
 * Time: 14:08:07
 *
 * @author Vadim.Gurov
 */
public class TransientDatabaseTest extends AbstractEntityStoreAwareTestCase {

  public long transacationA_part1_start() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.beginSession(null);
    try {

      System.out.println("A1");
      return session.getId();

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, session);
      throw new RuntimeException();
    } finally {
      session.suspend();
    }
  }

  public void transacationA_part2_middle(long id) {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.resumeSession(id);
    try {

      System.out.println("A2");

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, session);
      throw new RuntimeException();
    } finally {
      session.suspend();
    }
  }

  public void transacationA_part3_end(long id) {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.resumeSession(id);
    try {

      System.out.println("A3");

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, session);
      throw new RuntimeException();
    } finally {
      TransientStoreUtil.commit(session);
    }
  }

  public void testLongTransaction() throws Exception {
    try {
      long id = transacationA_part1_start();
      transacationA_part2_middle(id);
      transacationA_part3_end(id);
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
  }

}
