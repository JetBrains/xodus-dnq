package com.jetbrains.teamsys.dnq.database.refactorings;

import com.jetbrains.teamsys.database.*;
import org.jetbrains.annotations.NotNull;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class RefactorIssueCommentsFromStringToText implements Runnable {

  private static final Log log = LogFactory.getLog(RefactorIssueCommentsFromStringToText.class);

  @NotNull
  private final PersistentEntityStore _store;

  public RefactorIssueCommentsFromStringToText(@NotNull final PersistentEntityStore store) {
    _store = store;
  }

  public void run() {
    final StoreSession session = _store.getAndCheckThreadSession();
    for (final Entity comment : session.getAll("IssueComment")) {
      log.debug("Refactoring " + comment);
      final StoreTransaction txn = session.beginTransaction();
      try {
        final String text = (String) comment.getProperty("text");
        if (text != null && text.length() > 0) {
          comment.setBlobString("text", text);
        }
        comment.deleteProperty("text");
      } catch (Throwable e) {
          txn.abort();
          throw new RuntimeException(e);
      }
      txn.commit();
    }
  }
}
