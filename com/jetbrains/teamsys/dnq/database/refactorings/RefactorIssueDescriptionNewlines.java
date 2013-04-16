package com.jetbrains.teamsys.dnq.database.refactorings;

import jetbrains.exodus.database.Entity;
import jetbrains.exodus.database.PersistentEntityStore;
import jetbrains.exodus.database.StoreTransaction;
import jetbrains.exodus.exceptions.ExodusException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

public class RefactorIssueDescriptionNewlines implements Runnable {

    private static final Log log = LogFactory.getLog(RefactorIssueDescriptionNewlines.class);

    @NotNull
    private final PersistentEntityStore store;

    public RefactorIssueDescriptionNewlines(@NotNull final PersistentEntityStore store) {
        this.store = store;
    }

    public void run() {
        final StoreTransaction txn = store.getCurrentTransaction();
        for (final Entity issue : txn.getAll("Issue")) {
            log.debug("Refactoring " + issue);
            final String text = issue.getBlobString("description");
            if (text != null && text.length() > 0 && text.indexOf('\r') >= 0) {
                try {
                    issue.setBlobString("text", text.replace("\r", ""));
                } catch (Throwable e) {
                    txn.abort();
                    throw ExodusException.toRuntime(e);
                }
                txn.flush();
            }
        }
    }
}
