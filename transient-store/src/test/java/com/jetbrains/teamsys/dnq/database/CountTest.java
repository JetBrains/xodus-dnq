package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.AggregationAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.dnq.util.Util;
import jetbrains.exodus.entitystore.Entity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CountTest extends TestBase {

    @Test
    public void testCount1() {
        this.createData();
        this.checkCount1();
        this.checkCount2();
    }

    public void createData() {
        TransientStoreSession transientSession = store.beginSession();
        try {
            Entity p = (store.getThreadSession().newEntity("Project"));

            Entity i = (store.getThreadSession().newEntity("Issue"));

            AggregationAssociationSemantics.createOneToMany(p, "issue", "project", i);

            assertEquals(p, AssociationSemantics.getToOne(i, "project"));

            i = store.getThreadSession().newEntity("Issue");

            AggregationAssociationSemantics.createOneToMany(p, "issue", "project", i);

            assertEquals(p, AssociationSemantics.getToOne(i, "project"));

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }

    @Test
    public void checkCount2() {
        TransientStoreSession transientSession = store.beginSession();
        try {
            assertEquals(2, AssociationSemantics.getToManySize(Util.toList(store.getThreadSession().getAll("Project")).get(0), "issue"));
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }

    @Test
    public void checkCount1() {
        TransientStoreSession transientSession = store.beginSession();
        try {
            int i = 0;
            for (Object o : AssociationSemantics.getToMany(store.getThreadSession().getAll("Project").iterator().next(), "issue")) {
                i++;
            }
            assertEquals(2, i);
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }
}
