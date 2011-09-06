package com.jetbrains.teamsys.dnq.database.refactorings;

import com.jetbrains.teamsys.database.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

public class RefactorIssueDescriptionNewlines implements Runnable {

  private static final Log log = LogFactory.getLog(RefactorIssueDescriptionNewlines.class);

  @NotNull
  private final PersistentEntityStore _store;

  public RefactorIssueDescriptionNewlines(@NotNull final PersistentEntityStore store) {
    _store = store;
  }

  public void run() {
    final StoreSession session = _store.getAndCheckThreadSession();
    for (final Entity issue : session.getAll("Issue")) {
      log.debug("Refactoring " + issue);
      final String text = issue.getBlobString("description");
      if (text != null && text.length() > 0 && text.indexOf('\r') >= 0) {
        final StoreTransaction txn = session.beginTransaction();
        try {
          issue.setBlobString("text", text.replace("\r", ""));
        }
        catch (Throwable e) {
            txn.abort();
            throw new RuntimeException(e);
        }
        txn.commit();
      }
    }
  }
}
