package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.TransientEntityStore;
import com.jetbrains.teamsys.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import jetbrains.mps.internal.collections.runtime.ListSequence;

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
    tearDown();
    setRemoveStoreOnTearsDown(true);
    setUp();
    //

    createUsers();

    // 2
  }

  public void createUsers() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession session = store.beginSession("_login", 1);
    try {

      if(ListSequence.fromIterable(TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().getAll("User")).size() > 0) {
        return;
      }

      Entity u = ((TransientStoreSession) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession()).addSessionLocalEntity("u", TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("User"));
      PrimitiveAssociationSemantics.set(u, "username", (Comparable)"vadim");
      PrimitiveAssociationSemantics.set(u, "password", (Comparable)"vadim");
      Entity i = ((TransientStoreSession) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession()).addSessionLocalEntity("i", TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue"));
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
