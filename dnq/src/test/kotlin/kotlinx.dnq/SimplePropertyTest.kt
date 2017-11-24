/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq;

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import com.jetbrains.teamsys.dnq.database.EntityOperations;
import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientEntity;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.dnq.util.Util;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SimplePropertyTest extends TestBase {

    @Test
    public void SetString_Null_Empty_Null() {
        checkStringValue1Value2Value1MakesNoChanges(null, "");
    }

    @Test
    public void SetString_Empty_NotEmpty_Empty() {
        checkStringValue1Value2Value1MakesNoChanges("", "test");
    }

    @Test
    public void SetString_Empty_Empty_Empty() {
        checkStringValue1Value2Value1MakesNoChanges("", "");
    }

    @Test
    public void SetString_Empty_Null_Empty() {
        checkStringValue1Value2Value1MakesNoChanges("", null);
    }

    @Test
    public void SetString_Null_NotEmpty_Null() {
        checkStringValue1Value2Value1MakesNoChanges(null, "test1");
    }

    @Test
    public void SetString_Null_Null_Null() {
        checkStringValue1Value2Value1MakesNoChanges(null, null);
    }

    @Test
    public void SetString_NotEmpty_Null_NotEmpty() {
        checkStringValue1Value2Value1MakesNoChanges("test", null);
    }

    @Test
    public void SetString_NotEmpty_Empty_NotEmpty() {
        checkStringValue1Value2Value1MakesNoChanges("test", "");
    }

    @Test
    public void SetString_NotEmpty_NotEmpty_NotEmpty() {
        checkStringValue1Value2Value1MakesNoChanges("test", "test1");
    }

    // text

    @Test
    public void SetText_Null_Empty_Null() {
        checkTextValue1Value2Value1MakesNoChanges(null, "");
    }

    @Test
    public void SetText_Empty_NotEmpty_Empty() {
        checkTextValue1Value2Value1MakesNoChanges("", "test");
    }

    @Test
    public void SetText_Empty_Empty_Empty() {
        checkTextValue1Value2Value1MakesNoChanges("", "");
    }

    @Test
    public void SetText_Empty_Null_Empty() {
        checkTextValue1Value2Value1MakesNoChanges("", null);
    }

    @Test
    public void SetText_Null_NotEmpty_Null() {
        checkTextValue1Value2Value1MakesNoChanges(null, "test1");
    }

    @Test
    public void SetText_Null_Null_Null() {
        checkTextValue1Value2Value1MakesNoChanges(null, null);
    }

    @Test
    public void SetText_NotEmpty_Null_NotEmpty() {
        checkTextValue1Value2Value1MakesNoChanges("test", null);
    }

    @Test
    public void SetText_NotEmpty_Empty_NotEmpty() {
        checkTextValue1Value2Value1MakesNoChanges("test", "");
    }

    @Test
    public void SetText_NotEmpty_NotEmpty_NotEmpty() {
        checkTextValue1Value2Value1MakesNoChanges("test", "test1");
    }

    @Test
    public void ChangeBooleanInTwoTransactions_JT_10878() {
        // set boolean to true in one transaction, then do the same in another transaction
        TransientEntity e = null;
        TransientStoreSession t = store.beginSession();
        e = (TransientEntity) store.getThreadSession().newEntity("Issue");
        t.commit();

        // cache FlagOfSchepotiev null value in transient level
        TransientStoreSession t1 = store.beginSession();
        assertEquals(null, PrimitiveAssociationSemantics.get(e, "FlagOfSchepotiev", null));

        final TransientEntity _e = e;
        Util.runTranAsyncAndJoin(store, new Runnable() {
            @Override
            public void run() {
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
        TransientStoreSession transientSession = store.beginSession();
        TransientEntity e = null;
        try {
            e = (TransientEntity) store.getThreadSession().newEntity("Issue");
            PrimitiveAssociationSemantics.set(e, "summary", value1, String.class);
            transientSession.flush();

            assertEquals(value1, PrimitiveAssociationSemantics.get(e, "summary", String.class, null));

            PrimitiveAssociationSemantics.set(e, "summary", value2, String.class);
            PrimitiveAssociationSemantics.set(e, "summary", value1, String.class);
            PrimitiveAssociationSemantics.set(e, "summary", value2, String.class);
            PrimitiveAssociationSemantics.set(e, "summary", value1, String.class);

            //Assert.assertEquals(0, transientSession.getTransientChangesTracker().getChanges().size());
            Assert.assertEquals(false, EntityOperations.hasChanges((TransientEntity) e));
            assertEquals(false, EntityOperations.hasChanges((TransientEntity) e, "summary"));

            transientSession.flush();
            assertEquals(value1, PrimitiveAssociationSemantics.get(e, "summary", String.class, null));
        } catch (Throwable ex) {
            TransientStoreUtil.abort(ex, transientSession);
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }

    private void checkTextValue1Value2Value1MakesNoChanges(String value1, String value2) {
        TransientStoreSession transientSession = store.beginSession();
        TransientEntity e;
        try {
            e = store.getThreadSession().newEntity("Issue");
            PrimitiveAssociationSemantics.setBlob(e, "description", value1);
            transientSession.flush();

            assertEquals(value1, PrimitiveAssociationSemantics.getBlobAsString(e, "description"));

            PrimitiveAssociationSemantics.setBlob(e, "description", value2);
            PrimitiveAssociationSemantics.setBlob(e, "description", value1);
            PrimitiveAssociationSemantics.setBlob(e, "description", value2);
            PrimitiveAssociationSemantics.setBlob(e, "description", value1);

            // changes size is not relates to atual changes that were made
            //Assert.assertEquals(0, transientSession.getTransientChangesTracker().getChanges().size());
            assertEquals(false, EntityOperations.hasChanges((TransientEntity) e));
            assertEquals(false, EntityOperations.hasChanges((TransientEntity) e, "description"));

            transientSession.flush();
            assertEquals(value1, PrimitiveAssociationSemantics.getBlobAsString(e, "description"));
        } catch (Throwable ex) {
            TransientStoreUtil.abort(ex, transientSession);
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }
}
