package com.jetbrains.teamsys.dnq.database.refactorings;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.StoreTransaction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RefactorIssueDescriptionNewlines implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RefactorIssueDescriptionNewlines.class);

    @NotNull
    private final PersistentEntityStore store;

    public RefactorIssueDescriptionNewlines(@NotNull final PersistentEntityStore store) {
        this.store = store;
    }

    public void run() {
        final StoreTransaction txn = store.getCurrentTransaction();
        for (final Entity issue : txn.getAll("Issue")) {
            logger.debug("Refactoring " + issue);
            final String text = issue.getBlobString("description");
            if (text != null && text.length() > 0 && text.indexOf('\r') >= 0) {
                try {
                    issue.setBlobString("text", text.replace("\r", ""));
                } catch (Throwable e) {
                    txn.abort();
                    throw ExodusException.toExodusException(e);
                }
                txn.flush();
            }
        }
    }
}
