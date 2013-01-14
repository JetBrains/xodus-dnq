package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.mps.dnq.common.tests.AbstractEntityStoreAwareTestCase;
import com.jetbrains.mps.dnq.common.tests.TestOnlyServiceLocator;
import com.jetbrains.mps.dnq.concurrency.transactions.SomethingImpl;
import com.jetbrains.mps.dnq.concurrency.transactions.Util;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.database.TransientStoreSession;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import jetbrains.mps.baseLanguage.closures.runtime._FunctionTypes;
import junit.framework.Assert;

public class SimplePropertyTest extends AbstractEntityStoreAwareTestCase {

  // string

  public void testSetString_Null_Empty_Null() {
    checkStringValue1Value2Value1MakesNoChanges(null, "");
  }

  public void testSetString_Empty_NotEmpty_Empty() {
    checkStringValue1Value2Value1MakesNoChanges("", "test");
  }

  public void testSetString_Empty_Empty_Empty() {
    checkStringValue1Value2Value1MakesNoChanges("", "");
  }

  public void testSetString_Empty_Null_Empty() {
    checkStringValue1Value2Value1MakesNoChanges("", null);
  }

  public void testSetString_Null_NotEmpty_Null() {
    checkStringValue1Value2Value1MakesNoChanges(null, "test1");
  }

  public void testSetString_Null_Null_Null() {
    checkStringValue1Value2Value1MakesNoChanges(null, null);
  }

  public void testSetString_NotEmpty_Null_NotEmpty() {
    checkStringValue1Value2Value1MakesNoChanges("test", null);
  }

  public void testSetString_NotEmpty_Empty_NotEmpty() {
    checkStringValue1Value2Value1MakesNoChanges("test", "");
  }

  public void testSetString_NotEmpty_NotEmpty_NotEmpty() {
    checkStringValue1Value2Value1MakesNoChanges("test", "test1");
  }

  // text
  
  public void testSetText_Null_Empty_Null() {
    checkTextValue1Value2Value1MakesNoChanges(null, "");
  }

  public void testSetText_Empty_NotEmpty_Empty() {
    checkTextValue1Value2Value1MakesNoChanges("", "test");
  }

  public void testSetText_Empty_Empty_Empty() {
    checkTextValue1Value2Value1MakesNoChanges("", "");
  }

  public void testSetText_Empty_Null_Empty() {
    checkTextValue1Value2Value1MakesNoChanges("", null);
  }

  public void testSetText_Null_NotEmpty_Null() {
    checkTextValue1Value2Value1MakesNoChanges(null, "test1");
  }

  public void testSetText_Null_Null_Null() {
    checkTextValue1Value2Value1MakesNoChanges(null, null);
  }

  public void testSetText_NotEmpty_Null_NotEmpty() {
    checkTextValue1Value2Value1MakesNoChanges("test", null);
  }

  public void testSetText_NotEmpty_Empty_NotEmpty() {
    checkTextValue1Value2Value1MakesNoChanges("test", "");
  }

  public void testSetText_NotEmpty_NotEmpty_NotEmpty() {
    checkTextValue1Value2Value1MakesNoChanges("test", "test1");
  }

  public void testChangeBooleanInTwoTransactions_JT_10878() {
    // set boolean to true in one transaction, then do the same in another transaction
      final TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();

      TransientEntity e = null;
      TransientStoreSession t = store.beginSession();
      e = (TransientEntity) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue");
      t.commit();

      // cache FlagOfSchepotiev null value in transient level
      TransientStoreSession t1 = store.beginSession();
      assertEquals(null, PrimitiveAssociationSemantics.get(e, "FlagOfSchepotiev", null));
      t1.getId();

      final TransientEntity _e = e;
      Util.runTranAsyncAndJoin(new _FunctionTypes._void_P0_E0() {
          public void invoke() {
              // set flag to false and save to database
              TransientStoreSession t2 = store.beginSession();
              assertEquals(null, PrimitiveAssociationSemantics.get(_e, "FlagOfSchepotiev", null));
              PrimitiveAssociationSemantics.set(_e, "FlagOfSchepotiev", false, Boolean.class);
              t2.commit();
          }
      });


      PrimitiveAssociationSemantics.set(e, "FlagOfSchepotiev", false, Boolean.class);
      t1.commit();
  }

  private void checkStringValue1Value2Value1MakesNoChanges(String value1, String value2) {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession();
    TransientEntity e = null;
    try {
      e = (TransientEntity) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue");
      PrimitiveAssociationSemantics.set(e, "summary", value1, String.class);
      transientSession.intermediateCommit();

      Assert.assertEquals(value1, PrimitiveAssociationSemantics.get(e, "summary", String.class, null));

      PrimitiveAssociationSemantics.set(e, "summary", value2, String.class);
      PrimitiveAssociationSemantics.set(e, "summary", value1, String.class);
      PrimitiveAssociationSemantics.set(e, "summary", value2, String.class);
      PrimitiveAssociationSemantics.set(e, "summary", value1, String.class);

      Assert.assertEquals(0, transientSession.getTransientChangesTracker().getChanges().size());
      Assert.assertEquals(false, EntityOperations.hasChanges((TransientEntity) e));
      Assert.assertEquals(false, EntityOperations.hasChanges((TransientEntity) e, "summary"));

      transientSession.intermediateCommit();
      Assert.assertEquals(value1, PrimitiveAssociationSemantics.get(e, "summary", String.class, null));
    } catch (Throwable ex) {
      TransientStoreUtil.abort(ex, transientSession);
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }

  private void checkTextValue1Value2Value1MakesNoChanges(String value1, String value2) {
    TransientEntityStore store = TestOnlyServiceLocator.getTransientEntityStore();
    TransientStoreSession transientSession = store.beginSession();
    TransientEntity e = null;
    try {
      e = (TransientEntity) TestOnlyServiceLocator.getTransientEntityStore().getThreadSession().newEntity("Issue");
      PrimitiveAssociationSemantics.setBlob(e, "description", value1);
      transientSession.intermediateCommit();

      Assert.assertEquals(value1, PrimitiveAssociationSemantics.getBlobAsString(e, "description"));

      PrimitiveAssociationSemantics.setBlob(e, "description", value2);
      PrimitiveAssociationSemantics.setBlob(e, "description", value1);
      PrimitiveAssociationSemantics.setBlob(e, "description", value2);
      PrimitiveAssociationSemantics.setBlob(e, "description", value1);

      Assert.assertEquals(0, transientSession.getTransientChangesTracker().getChanges().size());
      Assert.assertEquals(false, EntityOperations.hasChanges((TransientEntity) e));
      Assert.assertEquals(false, EntityOperations.hasChanges((TransientEntity) e, "description"));

      transientSession.intermediateCommit();
      Assert.assertEquals(value1, PrimitiveAssociationSemantics.getBlobAsString(e, "description"));
    } catch (Throwable ex) {
      TransientStoreUtil.abort(ex, transientSession);
    } finally {
      TransientStoreUtil.commit(transientSession);
    }
  }

}
