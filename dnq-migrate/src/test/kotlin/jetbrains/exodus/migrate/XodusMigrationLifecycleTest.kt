/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.migrate

import YTDBDatabaseProviderFactory
import YouTrackDBFactory
import com.jetbrains.youtrackdb.api.DatabaseType
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryProxy
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseParams
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.shaded.exodus.entitystore.PersistentEntityStores
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import kotlin.test.*

class XodusMigrationLifecycleTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `all releases run and later failures are suppressed on the primary`() {
        for (workFails in listOf(false, true)) {
            val primary = IllegalStateException("copy or validation failed")
            val storeFailure = IllegalStateException("store close failed")
            val envFailure = IllegalStateException("environment close failed")
            val targetFailure = IllegalStateException("provider close failed")
            val order = mutableListOf<String>()
            val thrown = assertFailsWith<IllegalStateException> {
                runMigrationLifecycle(
                    closeTarget = { order += "provider"; throw targetFailure },
                    cleanup = { fail("Release failure must block cleanup") }
                ) {
                    own("environment") { order += it; throw envFailure }
                    own("store") { order += it; throw storeFailure }
                    own("target wrapper") { order += it }
                    if (workFails) throw primary
                    true
                }
            }
            assertEquals(listOf("target wrapper", "store", "environment", "provider"), order)
            assertSame(if (workFails) primary else storeFailure, thrown)
            assertEquals(
                if (workFails) listOf(storeFailure, envFailure, targetFailure) else listOf(envFailure, targetFailure),
                thrown.suppressed.toList()
            )
        }
    }

    @Test
    fun `construction failure releases exactly the resources already acquired`() {
        for (acquired in 0..3) {
            val released = mutableListOf<Int>()
            val primary = AssertionError("construction failed")
            val thrown = assertFailsWith<AssertionError> {
                runMigrationLifecycle(
                    closeTarget = { released += -1 },
                    cleanup = { fail("Construction failure must block cleanup") }
                ) {
                    repeat(acquired) { own(it) { released += it } }
                    throw primary
                }
            }
            assertSame(primary, thrown)
            assertEquals((0 until acquired).reversed() + listOf(-1), released)
        }
    }

    @Test
    fun `no-op releases resources without cleanup`() {
        var closed = false
        runMigrationLifecycle({ closed = true }, { fail("No-op must not clean up") }) { false }
        assertTrue(closed)
    }

    @Test
    fun `copy failure after committed data preserves source and durable target`() {
        Fixture().use { fixture ->
            val failure = IllegalStateException("interrupted after committed data")
            val provider = fixture.afterCommittedData { throw failure }
            val thrown = assertFailsWith<IllegalStateException> {
                fixture.launcher(provider, validate = true, close = true).migrate {
                    fail("Copy failure must not clean up")
                }
            }
            assertSame(failure, thrown)
            assertEquals(1, fixture.providerCloses)
            fixture.assertSource()
            fixture.reopenTarget()
            fixture.assertTarget("original")
        }
    }

    @Test
    fun `requested validation mismatch propagates and preserves source`() {
        Fixture().use { fixture ->
            val provider = fixture.afterCommittedData {
                fixture.provider.withSession { session ->
                    session.transaction { tx -> tx.command("update type1 set value = 'altered'") }
                }
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                fixture.launcher(provider, validate = true, close = false).migrate {
                    fail("Validation mismatch must not clean up")
                }
            }
            assertTrue(failure.message.orEmpty().contains("value"), failure.toString())
            assertEquals(0, fixture.providerCloses)
            assertTrue(fixture.provider.isOpen)
            fixture.assertSource()
            fixture.reopenTarget()
            fixture.assertTarget("altered")
        }
    }

    @Test
    fun `successful copy releases source before configured cleanup for both options`() {
        for ((delete, expectedReport) in listOf(
            false to "Xodus -> OrientDB copy completed, validation skipped",
            true to "Xodus -> OrientDB copy and requested validation completed"
        )) {
            Fixture().use { fixture ->
                var cleanups = 0
                val reports = captureLauncherReports {
                    fixture.launcher(fixture.afterCommittedData {}, validate = delete, close = delete, delete = delete)
                        .migrate { observedDelete ->
                            assertEquals(delete, observedDelete)
                            fixture.assertSource()
                            cleanups++
                        }
                }
                assertEquals(listOf(expectedReport), reports)
                assertEquals(1, cleanups)
                assertEquals(if (delete) 1 else 0, fixture.providerCloses)
                fixture.reopenTarget()
                fixture.assertTarget("original")
            }
        }
    }

    @Test
    fun `both entries persist to the inspected target and permit manually validated base records`() {
        for (direct in listOf(false, true)) Fixture().use { f ->
            f.provider.withSession { s ->
                s.transaction { tx -> tx.command("insert into O set sentinel = 'kept'") }
            }
            f.reopenTarget()
            f.runEntry(direct)
            assertTrue(f.provider.isOpen)
            f.assertSource()
            f.reopenTarget()
            f.assertTarget("original")
            f.provider.withSession { s ->
                s.beginReadOnly()
                try {
                    s.query("select sentinel from O where sentinel = 'kept'").use {
                        assertEquals("kept", it.next().getProperty<String>("sentinel"))
                        assertFalse(it.hasNext())
                    }
                } finally { s.rollback() }
            }
        }
    }

    @Test
    fun `both entries refuse extra empty classes even above the former launcher threshold`() {
        for (direct in listOf(false, true)) Fixture().use { f ->
            f.provider.withSession { s -> repeat(12) { s.createClass("Extra$it") } }
            f.reopenTarget()
            val before = f.snapshot()
            assertEquals(12, before.keys.count { it.startsWith("Extra") })
            val failure = f.refused(direct)
            assertTrue(failure.message.orEmpty().startsWith("Target contains nonstandard class Extra"))
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `both entries reload persisted sequences from an empty stale cache`() {
        for (direct in listOf(false, true)) Fixture().use { f ->
            f.provider.withSession { it.execute("create sequence allocated type ordered").close() }
            f.reopenTarget()
            val before = f.snapshot()
            f.provider.withSession { s ->
                s.beginReadOnly()
                try { assertEquals(1L, s.countClass("OSequence")) } finally { s.rollback() }
                (s.metadata.sequenceLibrary as SequenceLibraryProxy).delegate.close()
                assertTrue(s.metadata.sequenceLibrary.sequenceNames.isEmpty())
            }
            assertEquals("Target sequence library is not empty", f.refused(direct).message)
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `both entries refuse reopened partial and completed migration residue on retry`() {
        for (direct in listOf(false, true)) for (completed in listOf(false, true)) Fixture().use { f ->
            if (completed) f.runEntry(direct)
            else {
                val interrupted = IllegalStateException("committed partial migration")
                val provider = f.afterCommittedData { throw interrupted }
                val thrown = assertFailsWith<IllegalStateException> { f.runEntry(direct, provider) }
                assertSame(interrupted, thrown)
            }
            f.assertSource()
            f.reopenTarget()
            f.assertTarget("original")
            val before = f.snapshot()
            assertEquals("Target contains nonstandard class type1", f.refused(direct).message)
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `no source returns without accessing a nonstandard target session or cleaning up`() {
        for (kind in listOf("missing", "empty", "unrelated")) Fixture().use { f ->
            f.provider.withSession { it.createClass("Existing") }
            val before = f.snapshot()
            val directory = if (kind == "missing") File(temporary.root, "missing") else temporary.newFolder()
            if (kind == "unrelated") File(directory, "readme.txt").writeText("not Xodus")
            val sourceBefore = directory.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            val trap = object : YTDBDatabaseProvider by f.provider {
                override fun <R> withSession(block: (DatabaseSessionEmbedded) -> R): R =
                    fail("No-source path must not access a target session")
            }
            f.launcher(trap, validate = true, close = false, directory = directory).migrate {
                fail("No-source path must not clean up")
            }
            assertEquals(sourceBefore, directory.walkTopDown().filter { it.isFile }.map { it.name }.toList())
            assertEquals(before, f.snapshot())
            assertTrue(f.provider.isOpen)
        }
    }

    @Test
    fun `launcher refuses target before opening an unusable Xodus source`() {
        Fixture().use { f ->
            f.provider.withSession { it.createClass("Existing") }
            val before = f.snapshot()
            val directory = temporary.newFolder()
            val unusable = File(directory, "00000000000.xd").apply { writeText("not a Xodus log") }
            val bytes = unusable.readBytes()
            val failure = assertFailsWith<XodusMigrationFreshnessRefusal> {
                f.launcher(f.provider, validate = true, close = false, directory = directory).migrate {
                    fail("Refusal must not clean up")
                }
            }
            assertEquals("Target contains nonstandard class Existing", failure.message)
            assertContentEquals(bytes, unusable.readBytes())
            assertEquals(listOf(unusable.name), directory.list()!!.toList())
            assertEquals(before, f.snapshot())
            f.assertSource()
            assertTrue(f.provider.isOpen)
        }
    }

    @Test
    fun `both entries propagate native schema observation failure without writes or cleanup`() {
        for (direct in listOf(false, true)) Fixture().use { f ->
            f.provider.withSession { s ->
                s.transaction { tx -> tx.command("revoke read on database.schema from reader") }
            }
            f.reopenTarget()
            val before = f.snapshot()
            f.manager.open("target", "observer", "readerPassword").use { observer ->
                val target = object : YTDBDatabaseProvider by f.provider {
                    override fun <R> withSession(block: (DatabaseSessionEmbedded) -> R): R = block(observer)
                }
                val failure = f.refused(direct, target)
                assertEquals("Cannot reliably observe target class names or sequence view", failure.message)
                assertIs<SecurityAccessException>(failure.cause)
                assertFalse(observer.isClosed)
                assertFalse(observer.isTxActive)
            }
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `direct entry cannot route writes to a separate memory engine with the same location`() {
        Fixture().use { f ->
            val params = YTDBDatabaseParams.builder().withDatabasePath("").withDatabaseName("same")
                .withDatabaseType(DatabaseType.MEMORY).withAppUser("admin", "password")
                .withCloseDatabaseInDbProvider(false).build()
            // The public factory caches by path. Construct independent native memory engines.
            val first = YouTrackDBImpl(YouTrackDBInternal.embedded("", params.youTrackDBConfig, false))
            val second = YouTrackDBImpl(YouTrackDBInternal.embedded("", params.youTrackDBConfig, false))
            assertNotSame(first.internal, second.internal)
            val a = YTDBDatabaseProviderFactory.createProvider(params, first)
            val b = YTDBDatabaseProviderFactory.createProvider(params, second)
            try {
                assertEquals(a.databaseLocation, b.databaseLocation)
                b.withSession { it.createClass("Uninspected") }
                a.withSession { s ->
                    s.beginReadOnly()
                    try { assertFalse(s.schema.existsClass("Uninspected")) } finally { s.rollback() }
                }
                f.runEntry(direct = true, target = a)
                a.withSession { s ->
                    s.beginReadOnly()
                    try { assertEquals(2L, s.countClass("type1")) } finally { s.rollback() }
                }
                b.withSession { s ->
                    s.beginReadOnly()
                    try {
                        assertFalse(s.schema.existsClass("type1"))
                        assertTrue(s.schema.existsClass("Uninspected"))
                        assertEquals(0L, s.countClass("OSequence"))
                    } finally { s.rollback() }
                }
                assertTrue(a.isOpen)
                assertTrue(b.isOpen)
                f.assertSource()
            } finally {
                a.close()
                b.close()
                // These native engines are not registered with the public path cache.
                try { first.internal.close() } finally { second.internal.close() }
            }
        }
    }

    private fun captureLauncherReports(action: () -> Unit): List<String> {
        val loggerName = XodusToOrientDataMigratorLauncher::class.java.name
        val logger = LoggerFactory.getLogger(loggerName)
        assertEquals("org.slf4j.simple.SimpleLogger", logger.javaClass.name)
        assertTrue(logger.isInfoEnabled, "Launcher completion reports must be enabled")
        val originalErr = System.err
        val caller = Thread.currentThread()
        val output = ByteArrayOutputStream()
        // The existing Simple backend writes to the current System.err. Keep other logs visible.
        PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                originalErr.write(b)
                if (Thread.currentThread() === caller) output.write(b)
            }
        }, true, Charsets.UTF_8).use { capture ->
            try {
                System.setErr(capture)
                action()
            } finally {
                System.setErr(originalErr)
            }
        }
        return output.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            line.substringAfter(" INFO $loggerName - ", "")
                .takeIf { it.startsWith("Xodus -> OrientDB copy") }
        }.toList()
    }

    private inner class Fixture : AutoCloseable {
        private val source: File = temporary.newFolder()
        val params = YTDBDatabaseParams.builder()
            .withDatabasePath(temporary.newFolder().absolutePath)
            .withDatabaseName("target")
            .withDatabaseType(DatabaseType.DISK)
            .withAppUser("admin", "password")
            .addReaderUser("observer", "readerPassword")
            .build()
        var manager = YouTrackDBFactory.createEmbedded(params) as YouTrackDBImpl
            private set
        var provider: YTDBDatabaseProvider = YTDBDatabaseProviderFactory.createProvider(params, manager)
            private set
        var providerCloses = 0
            private set
        private val storeName: String

        init {
            val store = PersistentEntityStores.newInstance(source)
            try {
                storeName = store.name
                store.executeInTransaction { tx ->
                    val first = tx.newEntity("type1")
                    val second = tx.newEntity("type1")
                    first.setProperty("value", "original")
                    second.setProperty("value", "original")
                    first.setBlobString("payload", "source blob")
                    first.addLink("next", second)
                    tx.getSequence("savedCounter").set(71)
                }
            } finally {
                store.close()
            }
        }

        fun runEntry(direct: Boolean, target: YTDBDatabaseProvider = provider) {
            if (direct) {
                val store = PersistentEntityStores.newInstance(source)
                try {
                    val before = sourceFiles()
                    val stats = migrateDataFromXodusToOrientDb(store, target, entitiesPerTransaction = 1)
                    assertEquals(2L, stats.entities)
                    store.withReadonlyTx { assertEquals(2L, it.getAll("type1").size()) }
                    assertEquals(before, sourceFiles(), "Copy does not change post-open source files")
                } finally { store.close() }
            } else launcher(target, validate = true, close = false).migrate { assertSource() }
        }

        fun refused(direct: Boolean, target: YTDBDatabaseProvider = provider): XodusMigrationFreshnessRefusal {
            val failure = if (direct) {
                val store = PersistentEntityStores.newInstance(source)
                try {
                    val before = sourceFiles()
                    val result = assertFailsWith<XodusMigrationFreshnessRefusal> {
                        migrateDataFromXodusToOrientDb(store, target)
                    }
                    store.withReadonlyTx { assertEquals(2L, it.getAll("type1").size()) }
                    assertEquals(before, sourceFiles())
                    result
                } finally { store.close() }
            } else {
                val before = sourceFiles()
                val result = assertFailsWith<XodusMigrationFreshnessRefusal> {
                    launcher(target, validate = true, close = false).migrate { fail("Refusal must not clean up") }
                }
                assertEquals(before, sourceFiles(), "Guard refuses before source startup")
                result
            }
            assertTrue(provider.isOpen)
            assertSource()
            return failure
        }

        private fun sourceFiles() = source.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(source).path to java.security.MessageDigest.getInstance("SHA-256")
                .digest(it.readBytes()).toList()
        }

        // Independent SQL oracle includes standard records and sequence records, not the inspector.
        fun snapshot(): Map<String, List<String>> = provider.withSession { s ->
            s.beginReadOnly()
            try {
                s.schema.classes.associate { clazz ->
                    val records = s.query("select @rid as rid, @version as version from `${clazz.name}`").use { rows ->
                        rows.toList().map { "${it.getProperty<Any>("rid")}@${it.getProperty<Any>("version")}" }.sorted()
                    }
                    clazz.name to (listOf(clazz.collectionIds.sorted().toString()) + records)
                } + ("<indexes>" to s.schema.indexes.sorted())
            } finally { s.rollback() }
        }

        fun launcher(target: YTDBDatabaseProvider, validate: Boolean, close: Boolean, delete: Boolean = false,
                     directory: File = source) =
            XodusToOrientDataMigratorLauncher(
                MigrateToOrientConfig(target, params, close),
                MigrateFromXodusConfig(directory.absolutePath, storeName, null, 0, 10, delete),
                validate,
                entitiesPerTransaction = 1
            )

        // A delegating test provider observes real committed data. It changes no production API.
        fun afterCommittedData(action: () -> Unit): YTDBDatabaseProvider =
            object : YTDBDatabaseProvider by provider {
                private var fired = false
                override fun <R> withSession(block: (DatabaseSessionEmbedded) -> R): R {
                    val result = provider.withSession(block)
                    if (!fired) {
                        val populated = provider.withSession { session ->
                            session.beginReadOnly()
                            try {
                                session.schema.existsClass("type1") && session.countClass("type1") == 2L
                            } finally {
                                session.rollback()
                            }
                        }
                        if (populated) {
                            fired = true
                            action()
                        }
                    }
                    return result
                }
                override fun close() {
                    providerCloses++
                    provider.close()
                }
            }

        fun assertSource() {
            assertFalse(File(source, "xodus_backup").exists())
            val store = PersistentEntityStores.newInstance(source)
            try {
                store.withReadonlyTx { tx ->
                    val entities = tx.getAll("type1").toList()
                    assertEquals(2, entities.size)
                    assertTrue(entities.all { it.getProperty("value") == "original" })
                    val first = entities.single { "payload" in it.blobNames }
                    assertEquals("source blob", first.getBlobString("payload"))
                    assertEquals(entities.single { it.id != first.id }.id, first.getLink("next")!!.id)
                    assertEquals(71L, tx.getSequence("savedCounter").get())
                }
            } finally {
                store.close()
            }
        }

        fun reopenTarget() {
            if (provider.isOpen) provider.close()
            manager = YouTrackDBFactory.createEmbedded(params) as YouTrackDBImpl
            provider = YTDBDatabaseProviderFactory.createProvider(params, manager)
        }

        fun assertTarget(value: String) {
            provider.withSession { session ->
                session.beginReadOnly()
                try {
                    assertEquals(2L, session.countClass("type1"))
                    session.query("select value from type1").use { rows ->
                        val values = rows.stream().map { it.getString("value") }.toList()
                        assertEquals(listOf(value, value), values)
                    }
                } finally {
                    session.rollback()
                }
            }
        }

        override fun close() {
            if (provider.isOpen) provider.close()
        }
    }
}
