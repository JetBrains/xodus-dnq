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

import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import org.junit.Test;

/**
 * Date: 14.12.2006
 * Time: 12:14:50
 *
 * @author Vadim.Gurov
 */
public class StoreStartupTest extends TestBase {

    @Test
    public void testGetAllWithtransient() throws Exception {
        createUsers();

        reinit();

        createUsers();
    }

    public void createUsers() {
        TransientStoreSession session = store.beginSession();
        try {

            if ((store.getThreadSession().getAll("User")).size() > 0) {
                return;
            }

            Entity u = store.getThreadSession().newEntity("User");
            PrimitiveAssociationSemantics.set(u, "username", (Comparable) "vadim");
            PrimitiveAssociationSemantics.set(u, "password", (Comparable) "vadim");
            Entity i = store.getThreadSession().newEntity("Issue");
            DirectedAssociationSemantics.setToOne(i, "reporter", (Entity) u);
            PrimitiveAssociationSemantics.set(i, "summary", (Comparable) "test issue");

        } catch (Throwable e) {
            TransientStoreUtil.abort(e, session);
            throw new RuntimeException();
        } finally {
            TransientStoreUtil.commit(session);
        }
    }

}
