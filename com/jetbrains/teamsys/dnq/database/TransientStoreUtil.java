package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.StoreTransaction;
import com.jetbrains.teamsys.database.TransientEntity;
import com.jetbrains.teamsys.database.TransientStoreSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.Set;
/**
 * Date: 18.12.2006
 * Time: 13:43:10
 *
 * @author Vadim.Gurov
 */
public class TransientStoreUtil {

  private static final Log log = LogFactory.getLog(TransientStoreUtil.class);

  /**
   * Attach entity to current session if possible.
   * @param entity
   * @return
   */
  public static TransientEntity reattach(@Nullable TransientEntity entity) {
    if (entity == null) {
      return null;
    }

    // todo: rewrite as MPS code
    // todo: get store from service locator, because given entity may be attached to another closed store
    TransientStoreSession s = (TransientStoreSession)entity.getStore().getThreadSession();

    if (s == null) {
      throw new IllegalStateException("There's no current session to attach transient entity to.");
    } else {
      return s.newLocalCopy(entity);
    }
  }

  public static void commit(@Nullable TransientStoreSession s) {
    if (s != null && s.isOpened()) {
      try {
        s.commit();
      } catch (Throwable e) {
        abort(e, s);
      }
    }
  }

  public static void suspend(@Nullable TransientStoreSession s) {
    if (s != null && s.isOpened()) {
      try {
        s.suspend();
      } catch (Throwable e) {
        abort(e, s);
      }
    }
  }

  public static void abort(@NotNull Throwable e, @Nullable TransientStoreSession s) {
    if (s != null && s.isOpened()) {
      s.abort();
    }

    if (e instanceof Error) {
      throw (Error)e;
    }

    if (e instanceof RuntimeException) {
      throw (RuntimeException)e;
    }

    throw new RuntimeException(e);
  }

  public static void abortIfOpened(TransientStoreSession session) {
    if (session != null && session.isOpened()) {
      session.abort();
    }
  }

  public static void commitIfOpen(TransientStoreSession session) {
    if (session != null && session.isOpened()) {
      session.commit();
    }
  }

  public static void abort(@NotNull Throwable e, @Nullable StoreTransaction t) {
    if (log.isDebugEnabled()) {
      log.error("Abort persistent transaction.", e);
    }

    if (t != null) {
      t.abort();
    }

    if (e instanceof Error) {
      throw (Error)e;
    }

    if (e instanceof RuntimeException) {
      throw (RuntimeException)e;
    }

    throw new RuntimeException(e);
  }

  static String toString(Set<String> strings) {
    if (strings == null) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String s: strings) {
      if (!first) {
        sb.append(",");        
      }

      sb.append(s);

      first = false;
    }

    return sb.toString();
  }

}
