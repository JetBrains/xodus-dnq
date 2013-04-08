package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.mps.internal.collections.runtime.ListSequence;
import jetbrains.teamsys.dnq.runtime.events.EventsMultiplexerJobProcessor;

/**
 * Date: 14.12.2006
 * Time: 12:14:50
 *
 * @author Vadim.Gurov
 */
public class StoreStartupTest extends AbstractEntityStoreAwareTestCase {

  public void testGetAllWithtransient() throws Exception {
    // 1

    createUsers();

    //
    setRemoveStoreOnTearsDown(false);
    EventsMultiplexerJobProcessor.getInstance().waitForJobs(100);
    tearDown();
    setRemoveStoreOnTearsDown(true);
    setUp();
    //

    createUsers();

    // 2
  }

  public void createUsers() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.beginSession();
    try {

      if(ListSequence.fromIterable(TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().getAll("User")).size() > 0) {
        return;
      }

      Entity u = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("User");
      PrimitiveAssociationSemantics.set(u, "username", (Comparable)"vadim");
      PrimitiveAssociationSemantics.set(u, "password", (Comparable)"vadim");
      Entity i = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue");
      DirectedAssociationSemantics.setToOne(i, "reporter", (Entity)u);
      PrimitiveAssociationSemantics.set(i, "summary", (Comparable)"test issue");

    } catch (Throwable e) {
      TransientStoreUtil.abort(e, session);
      throw new RuntimeException();
    } finally {
      TransientStoreUtil.commit(session);
    }
  }

}
