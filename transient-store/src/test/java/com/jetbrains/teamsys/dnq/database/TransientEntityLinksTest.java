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
package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import org.junit.Test;

import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Date: 28.12.2006
 * Time: 12:56:41
 *
 * @author Vadim.Gurov
 */
public class TransientEntityLinksTest extends TestBase {

    @Test
    public void testTransientGetLinks() {
        createData();
        checkTransientGetLinks();
        checkTransientGetLinks2();
    }

    private void createData() {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {

            Entity user = transientStoreSession.newEntity("User");
            user.setProperty("login", "user");
            user.setProperty("password", "user");

            user = transientStoreSession.newEntity("User");
            user.setProperty("login", "user1");
            user.setProperty("password", "user1");

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }

    }

    private void checkTransientGetLinks() {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {
            Entity user = getFirst(transientStoreSession.find("User", "login", "user"));

            assertNotNull(user);

            Entity issue = transientStoreSession.newEntity("Issue");

            DirectedAssociationSemantics.setToOne(issue, "reporter", user);

            assertEquals(user, AssociationSemantics.getToOne(issue, "reporter"));

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }
    }

    private void checkTransientGetLinks2() {
        TransientStoreSession transientStoreSession = store.beginSession();

        try {
            Entity user1 = getFirst(transientStoreSession.find("User", "login", "user1"));

            assertNotNull(user1);

            Entity issue = getFirst(transientStoreSession.getAll("Issue"));

            assertNotNull(issue);

            DirectedAssociationSemantics.setToOne(issue, "reporter", user1);

            assertEquals(user1, AssociationSemantics.getToOne(issue, "reporter"));

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, transientStoreSession);
            throw new RuntimeException("Should never be thrown.");
        } finally {
            TransientStoreUtil.commit(transientStoreSession);
        }
    }

    private static Entity getFirst(Iterable<Entity> input) {
        Iterator<Entity> iterator = input.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }

        return null;
    }
}
