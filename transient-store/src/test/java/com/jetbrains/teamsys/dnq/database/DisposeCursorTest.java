package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.dnq.association.DirectedAssociationSemantics;
import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics;
import com.jetbrains.teamsys.dnq.database.testing.TestBase;
import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.dnq.util.TestUserService;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import org.junit.Test;

/**
 * Date: 14.12.2006
 * Time: 14:26:16
 *
 * @author Vadim.Gurov
 */
public class DisposeCursorTest extends TestBase {

    @Test
    public void testDisposeCursor() throws Exception {
        // 1

        createUsers();

        _login_LoginForm_handler();
    }

    @Test
    public void testDisposeOnLastElement() {
        createUsers();

        findUser("1", "1");
    }

    public void _login_LoginForm_handler() {
        TransientStoreSession session = store.beginSession();
        try {
            TestUserService.findUser(store, "vadim", "vadim");
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, session);
            throw new RuntimeException();
        } finally {
            TransientStoreUtil.commit(session);
        }
    }

    public void createUsers() {
        TransientStoreSession session = store.beginSession();
        try {

            if (store.getThreadSession().getAll("User").size() > 0) {
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

    public void findUser(String username, String password) {
        TransientStoreSession session = store.beginSession();
        try {
            Iterable<Entity> users = store.getQueryEngine().intersect(
                    session.find("User", "login", username), session.find("User", "password", password));
            if (!(((EntityIterable) users).isEmpty())) {
                System.out.println("found");
            }
        } catch (Throwable e) {
            TransientStoreUtil.abort(e, session);
            throw new RuntimeException();
        } finally {
            TransientStoreUtil.commit(session);
        }

    }

}
