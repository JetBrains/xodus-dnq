package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.dnq.util.Util;
import jetbrains.exodus.entitystore.Entity;
import org.junit.Test;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * @author Maxim.Mazin at date: 11.01.2007 time: 11:29:28
 */
public class SearchByPropertyTest extends TestBase {

    @Test
    public void testSearchByProperty() {
        createGuest();
        assertEquals("Unexpected number of guests", 1, numberOfGuests());
        assertTrue("Guest doesn't exist", guestExists());
    }

    @Test
    public void createGuest() {
        TransientStoreSession transientSession = store.beginSession();
        try {
            TransientStoreSession session = store.getThreadSession();
            assert session != null;
            Entity u = (session.newEntity("User"));
            PrimitiveAssociationSemantics.set(u, "login", "guest");
            PrimitiveAssociationSemantics.set(u, "password", "guest");
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }

    private boolean guestExists() {
        TransientStoreSession transientSession = store.beginSession();
        try {
            TransientStoreSession session = store.getThreadSession();
            assert session != null;
            return !Util.toList(session.find("User", "login", "guest")).isEmpty();
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientSession);
        }
    }

    private long numberOfGuests() {
        TransientStoreSession transientSession = store.beginSession();
        try {
            TransientStoreSession session = store.getThreadSession();
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
