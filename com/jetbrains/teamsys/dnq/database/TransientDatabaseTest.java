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

  public void transacationA_part1_start() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.beginSession(null, 1);
    try {

      System.out.println("A1");

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, session);
      throw new RuntimeException();
    } finally {
      session.suspend();
    }
  }

  public void transacationA_part2_middle() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.resumeSession(1);
    try {

      System.out.println("A2");

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, session);
      throw new RuntimeException();
    } finally {
      session.suspend();
    }
  }

  public void transacationA_part3_end() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.resumeSession(1);
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
      transacationA_part1_start();
      transacationA_part2_middle();
      transacationA_part3_end();
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
  }

}
