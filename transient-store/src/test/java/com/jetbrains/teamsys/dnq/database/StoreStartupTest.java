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
