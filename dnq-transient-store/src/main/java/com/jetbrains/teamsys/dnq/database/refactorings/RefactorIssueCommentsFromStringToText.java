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
package com.jetbrains.teamsys.dnq.database.refactorings;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.StoreTransaction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RefactorIssueCommentsFromStringToText implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RefactorIssueCommentsFromStringToText.class);

    @NotNull
    private final PersistentEntityStore store;

    public RefactorIssueCommentsFromStringToText(@NotNull final PersistentEntityStore store) {
        this.store = store;
    }

    public void run() {
        final StoreTransaction txn = store.getCurrentTransaction();
        for (final Entity comment : txn.getAll("IssueComment")) {
            logger.debug("Refactoring " + comment);
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
