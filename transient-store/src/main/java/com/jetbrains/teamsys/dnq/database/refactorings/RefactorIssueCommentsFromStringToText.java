package com.jetbrains.teamsys.dnq.database.refactorings;

import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.StoreTransaction;
import jetbrains.exodus.ExodusException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

public class RefactorIssueCommentsFromStringToText implements Runnable {

    private static final Log log = LogFactory.getLog(RefactorIssueCommentsFromStringToText.class);

    @NotNull
    private final PersistentEntityStore store;

    public RefactorIssueCommentsFromStringToText(@NotNull final PersistentEntityStore store) {
        this.store = store;
    }

    public void run() {
        final StoreTransaction txn = store.getCurrentTransaction();
        for (final Entity comment : txn.getAll("IssueComment")) {
            log.debug("Refactoring " + comment);
            try {
                final String text = (String) comment.getProperty("text");
                if (text != null && text.length() > 0) {
                    comment.setBlobString("text", text);
                }
                comment.deleteProperty("text");
            } catch (Throwable e) {
                txn.abort();
                throw ExodusException.toExodusException(e);
            }
            txn.flush();
        }
    }
}
