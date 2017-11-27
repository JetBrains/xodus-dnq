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

import com.jetbrains.teamsys.dnq.database.TransientStoreUtil;
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
