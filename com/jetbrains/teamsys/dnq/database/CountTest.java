package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import junit.framework.Assert;
import jetbrains.mps.internal.collections.runtime.ListSequence;
import jetbrains.mps.internal.collections.runtime.Sequence;

public class CountTest extends AbstractEntityStoreAwareTestCase {

  public void testCount1() {
    this.createData();
    this.checkCount1();
    this.checkCount2();
  }
  public void createData() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession();
    try {
      Entity p = ((TransientStoreSession) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().getCurrentTransaction()).addSessionLocalEntity("p", (TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Project")));

      Entity i = ((TransientStoreSession) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().getCurrentTransaction()).addSessionLocalEntity("i", (TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue")));

      AggregationAssociationSemantics.createOneToMany(p, "issue", "project", i);

      assertEquals(p, AssociationSemantics.getToOne(i, "project"));

      i = TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue");

      AggregationAssociationSemantics.createOneToMany(p, "issue", "project", i);

      assertEquals(p, AssociationSemantics.getToOne(i, "project"));
      
    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }
  public void checkCount2() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession();
    try {
      Assert.assertEquals(2, AssociationSemantics.getToManySize(ListSequence.fromIterable(TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().getAll("Project")).first(), "issue"));
    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }
  public void checkCount1() {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession();
    try {
      int i = 0;
      for (Object o : Sequence.fromIterable(AssociationSemantics.getToMany(Sequence.fromIterable(TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().getAll("Project")).first(), "issue"))) {
        i ++;
      }
      Assert.assertEquals(2, i);
    } catch (Throwable e) {
      TransientStoreUtil.abort(e, transientSession);
      throw new RuntimeException("Should never be thrown.");
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }
}
