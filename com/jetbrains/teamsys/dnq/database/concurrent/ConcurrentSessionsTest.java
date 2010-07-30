package com.jetbrains.teamsys.dnq.database.concurrent;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.TransientEntityStore;
import com.jetbrains.teamsys.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Date: 13.02.2007
 * Time: 9:58:19
 *
 * @author Vadim.Gurov
 */
public class ConcurrentSessionsTest extends AbstractEntityStoreAwareTestCase {

  private static Log log = LogFactory.getLog(ConcurrentSessionsTest.class);

  private int threads = 10;

  public void testConcurrentSessions1() throws InterruptedException {
    Thread[] t = new Thread[threads];

    for (int i = 0; i < threads; i++) {
      t[i] = new Cycle1("mythread " + i);
      t[i].start();
    }

    for (int i = 0; i < threads; i++) {
      t[i].join();
    }

  }

  private class Cycle1 extends Thread {

    private int cycles = 10;

    public Cycle1(String name) {
      super(name);
    }
    
    public void run() {
      log.debug(getName() + " start");
      for (int i = 0; i < cycles; i++) {

        log.debug(getName() + " cycle " + i + " start");

        this.startEditIssue();
        this.continueEditIssue1();
        this.continueEditIssue2();
        this.continueEditIssue3();

        log.debug(getName() + " cycle " + i + " end");

      }
      log.debug(getName() + " end");
    }

    public void startEditIssue() {
      TransientEntityStore store = ((TransientEntityStore)ServiceLocator.getBean("transientEntityStore"));
      TransientStoreSession transientSession = store.beginSession("startEditIssue", getName());
      try {
        Entity i = ((TransientStoreSession)((TransientEntityStore)ServiceLocator.getBean("transientEntityStore")).getThreadSession()).addSessionLocalEntity("i", (MyIssueImpl.constructor("s1")));
      } catch (Throwable e) {
        TransientStoreUtil.abort(e, transientSession);
        throw new RuntimeException("Should never be thrown.");
      } finally {
        TransientStoreUtil.suspend(transientSession);
      }
    }
    public void continueEditIssue1() {
      TransientEntityStore store = ((TransientEntityStore)ServiceLocator.getBean("transientEntityStore"));
      TransientStoreSession transientSession = store.resumeSession(getName());
      Entity i = transientSession.getSessionLocalEntity("i");
      try {
        PrimitiveAssociationSemantics.set(i, "summary", "s2");
      } catch (Throwable e) {
        TransientStoreUtil.abort(e, transientSession);
        throw new RuntimeException("Should never be thrown.");
      } finally {
        TransientStoreUtil.suspend(transientSession);
      }
    }
    public void continueEditIssue2() {
      TransientEntityStore store = ((TransientEntityStore)ServiceLocator.getBean("transientEntityStore"));
      TransientStoreSession transientSession = store.resumeSession(getName());
      Entity i = transientSession.getSessionLocalEntity("i");
      try {
        PrimitiveAssociationSemantics.set(i, "summary", "s3");
      } catch (Throwable e) {
        TransientStoreUtil.abort(e, transientSession);
        throw new RuntimeException("Should never be thrown.");
      } finally {
        TransientStoreUtil.suspend(transientSession);
      }
    }
    public void continueEditIssue3() {
      TransientEntityStore store = ((TransientEntityStore)ServiceLocator.getBean("transientEntityStore"));
      TransientStoreSession transientSession = store.resumeSession(getName());
      Entity i = transientSession.getSessionLocalEntity("i");
      try {
        PrimitiveAssociationSemantics.set(i, "summary", "s4");
        ((TransientStoreSession)((TransientEntityStore)ServiceLocator.getBean("transientEntityStore")).getThreadSession()).commit();
      } catch (Throwable e) {
        TransientStoreUtil.abort(e, transientSession);
        throw new RuntimeException("Should never be thrown.");
      } finally {
        TransientStoreUtil.suspend(transientSession);
      }
    }

  }

}
