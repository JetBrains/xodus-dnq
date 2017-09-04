package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.EntityRemovedInDatabaseException;
import org.junit.Test;

import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertNotNull;

public class ToIdFromIdTest extends TestBase {

    @Test
    public void testPersistentEntity() {
        createData();
        String id = getSomeEntityId();
        tryToRestoreById(id);
    }

    @Test
    public void testFindByIncorrectId() {
        createData();
        tryToRestoreById("0-0"); //ok
        findByIncorrectId("0-1"); // bad id!
    }

    private void findByIncorrectId(String id) {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {

            transientStoreSession.getEntity(transientStoreSession.toEntityId(id));

            fail();

        } catch (EntityRemovedInDatabaseException ignored) {
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }
    }

    private void tryToRestoreById(String id) {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {

            Entity user = transientStoreSession.getEntity(transientStoreSession.toEntityId(id));

            assertNotNull(user);

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }
    }

    private String getSomeEntityId() {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {

            EntityIterable users = transientStoreSession.find("User", "login", "user");

            assertTrue(users.size() == 1);

            Entity user = users.iterator().next();

            assertNotNull(user);

            return user.getId().toString();

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }
    }

    private void createData() {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {

            Entity user = transientStoreSession.newEntity("User");
            user.setProperty("login", "user");
            user.setProperty("password", "user");
/*
            user = transientStoreSession.newEntity("User");
            user.setProperty("login", "user1");
            user.setProperty("password", "user1");
*/
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }

    }

}
