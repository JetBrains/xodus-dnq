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
package com.jetbrains.teamsys.dnq.database.testing;

import com.jetbrains.teamsys.dnq.database.TransientEntityStoreImpl;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.query.QueryEngine;
import jetbrains.exodus.query.metadata.ModelMetaDataImpl;
import jetbrains.exodus.util.IOUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public abstract class TestBase {
    private static final Logger logger = LoggerFactory.getLogger(TestBase.class);
    private static final int TEMP_DIR_ATTEMPTS = 10000;
    private static final String TEMP_FOLDER = createTempDir().getAbsolutePath();

    private PersistentEntityStoreImpl persistentStore;
    protected TransientEntityStoreImpl store;

    protected boolean isPartiallyTornDown;
    protected boolean shouldCleanopOnTearDown;
    private String databaseFolder;

    @Before
    public void setUp() throws Exception {
        isPartiallyTornDown = false;
        shouldCleanopOnTearDown = true;
        openStores();
    }

    protected void openStores() throws Exception {
        persistentStore = createStoreInternal(getDatabaseFolder());
        store = new TransientEntityStoreImpl();
        store.setPersistentStore(persistentStore);
        QueryEngine queryEngine = new QueryEngine(new ModelMetaDataImpl(), persistentStore);
        store.setQueryEngine(queryEngine);
    }

    protected String getDatabaseFolder() {
        if (databaseFolder == null) {
            databaseFolder = initTempFolder();
        }
        return databaseFolder;
    }

    protected PersistentEntityStoreImpl createStoreInternal(String dbTempFolder) throws Exception {
        return createStore(dbTempFolder);
    }

    public static PersistentEntityStoreImpl createStore(String dbTempFolder) throws Exception {
        return PersistentEntityStores.newInstance(dbTempFolder);
    }

    @After
    public void tearDown() throws Exception {
        if (!isPartiallyTornDown) {
            persistentStore.close();
        }
        if (shouldCleanopOnTearDown) {
            cleanUp(persistentStore.getLocation());
            databaseFolder = null;
        }
    }

    public static String initTempFolder() {
        // Configure temp folder for database
        final String location = randomTempFolder();
        final File tempFolder = new File(location);
        if (!tempFolder.mkdirs()) {
            Assert.fail("Can't create directory at " + location);
        }
        if (logger.isInfoEnabled()) {
            logger.info("Temporary data folder created: " + location);
        }
        return tempFolder.getAbsolutePath();
    }

    public static void cleanUp(String location) {
        final File tempFolder = new File(location);
        if (logger.isInfoEnabled()) {
            logger.info("Cleaning data folder: " + location);
        }
        IOUtil.deleteRecursively(tempFolder);
        IOUtil.deleteFile(tempFolder);
    }

    public void transactional(@NotNull final PersistentStoreTransactionalExecutable executable) {
        persistentStore.executeInTransaction(wrap(executable));
    }

    public void transactionalReadonly(@NotNull final PersistentStoreTransactionalExecutable executable) {
        persistentStore.executeInReadonlyTransaction(wrap(executable));
    }

    protected final PersistentEntityStoreImpl getPersistentStore() {
        return persistentStore;
    }

    public static String randomTempFolder() {
        return TEMP_FOLDER + Math.random();
    }

    protected void reinit() throws Exception {
        shouldCleanopOnTearDown = false;
        tearDown();
        setUp();
    }

    @NotNull
    private static StoreTransactionalExecutable wrap(@NotNull final PersistentStoreTransactionalExecutable executable) {
        return new StoreTransactionalExecutable() {
            @Override
            public void execute(@NotNull StoreTransaction txn) {
                executable.execute((PersistentStoreTransaction) txn);
            }
        };
    }

    public interface PersistentStoreTransactionalExecutable {

        void execute(@NotNull final PersistentStoreTransaction txn);
    }

    // from Guava code
    public static File createTempDir() {
        File baseDir = new File(System.getProperty("java.io.tmpdir"));
        String baseName = System.currentTimeMillis() + "-";

        for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException("Failed to create directory within "
                + TEMP_DIR_ATTEMPTS + " attempts (tried "
                + baseName + "0 to " + baseName + (TEMP_DIR_ATTEMPTS - 1) + ')');
    }
}
