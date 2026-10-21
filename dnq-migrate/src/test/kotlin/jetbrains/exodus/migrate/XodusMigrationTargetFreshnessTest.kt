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
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryProxy
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseParams
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class XodusMigrationTargetFreshnessTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `runtime uses the exact approved engine artifact`() {
        val jar = File(DatabaseSessionEmbedded::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(jar.name.contains("0.5.0-20261015.230404-01cd3e1-dev-SNAPSHOT"))
        assertEquals("4b968d4e47f0667713da16e535d7d88842f153c4a55b38edba00d1ce99a6ae9d",
            MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `fresh target with actual standard records passes without persistent changes`() {
        Fixture().use { f ->
            f.session { s -> f.read(s) {
                assertEquals(setOf("OIdentity", "OSecurityPolicy", "ORole", "OUser", "OFunction",
                    "OSchedule", "OSequence", "O", "V", "E"), s.schema.classes.map { it.name }.toSet())
                assertTrue(sqlCount(s, "OUser") > 0)
                assertTrue(sqlCount(s, "ORole") > 0)
                assertTrue(sqlCount(s, "OSecurityPolicy") > 0)
            } }
            f.check()
        }
    }

    @Test
    fun `empty application O prefix case variant and abstract classes refuse`() {
        for (name in listOf("emptyX", "Application", "OApplication", "OUSER", "ouser", "LE", "AbstractApplication")) {
            Fixture().use { f ->
                f.session { s ->
                    if (name == "AbstractApplication") {
                        s.createAbstractClass(name)
                        assertContentEquals(intArrayOf(-1), s.schema.getClass(name)!!.collectionIds)
                    } else s.createClass(name)
                    f.read(s) { assertEquals(0L, sqlCount(s, name)) }
                }
                assertEquals("Target contains nonstandard class $name", f.check(refuse = true)!!.message)
            }
        }
    }

    @Test
    fun `empty subclasses of standard and sequence classes refuse`() {
        for (parent in listOf("OFunction", "OSequence")) Fixture().use { f ->
            f.session { s ->
                s.createClass("Child", parent)
                assertTrue(s.schema.getClass("Child")!!.isSubClassOf(parent))
                f.read(s) { assertEquals(0L, sqlCount(s, "Child")) }
            }
            assertEquals("Target contains nonstandard class Child", f.check(refuse = true)!!.message)
        }
    }

    @Test
    fun `standard content and definitions are not audited and absent permitted classes pass`() {
        Fixture().use { f ->
            f.session { s ->
                s.execute("alter class OFunction custom humanCheck=required").close()
                s.execute("alter class O custom humanCheck=required").close()
                f.write(s) {
                    s.execute("insert into OFunction set name = 'humanValidated', code = 'arbitrary'").close()
                    s.execute("insert into OSecurityPolicy set name = 'humanValidated'").close()
                }
                f.read(s) {
                    s.query("select code from OFunction where name = 'humanValidated'").use {
                        assertEquals("arbitrary", it.next().getProperty<String>("code"))
                    }
                }
            }
            f.check()
            f.session { s ->
                s.schema.dropClass("OSchedule")
                f.write(s) { s.execute("delete from OFunction").close() }
                s.schema.dropClass("OFunction")
                assertNull(s.schema.getClass("OSchedule"))
                assertNull(s.schema.getClass("OFunction"))
            }
            f.check()
        }
    }

    @Test
    fun `committed ordinary O prefix and case variant classes refuse`() {
        for (name in listOf("Application", "OApplication", "OUSER", "ouser", "LE")) {
            Fixture().use { f ->
                val clean = f.snapshot()
                f.session { s ->
                    if (s.schema.getClass(name) == null) s.createClass(name)
                    f.write(s) { s.newEntity(name).setProperty("payload", "kept") }
                }
                assertNotEquals(clean, f.snapshot(), "Logical oracle detects the committed fixture")
                f.session { s -> f.read(s) {
                    assertEquals(1L, sqlCount(s, name), name)
                } }
                assertEquals("Target contains nonstandard class $name", f.check(refuse = true)!!.message)
            }
        }
    }

    @Test
    fun `committed O V and E records independently pass without persistent changes`() {
        for (name in listOf("O", "V", "E")) Fixture().use { f ->
            val clean = f.snapshot()
            f.session { s -> f.write(s) {
                when (name) {
                    "V" -> s.newVertex("V").setProperty("payload", "kept")
                    "E" -> {
                        val from = s.newVertex("V")
                        val to = s.newVertex("V")
                        s.newEdge(from, to, "E").setProperty("payload", "kept")
                    }
                    else -> s.newEntity("O").setProperty("payload", "kept")
                }
            } }
            val committed = f.snapshot()
            assertNotEquals(clean, committed, "Logical oracle detects the committed fixture")
            f.session { s -> f.read(s) {
                assertEquals(1L, sqlCount(s, name), name)
                s.query("select payload from `$name`").use {
                    assertEquals("kept", it.next().getProperty<String>("payload"))
                }
                if (name == "E") assertEquals(2L, sqlCount(s, "V"))
            } }
            f.check()
            assertEquals(committed, f.snapshot())
        }
    }

    @Test
    fun `records in descendants of standard abstract and sequence classes refuse`() {
        for (parent in listOf("OFunction", "AbstractApplication", "OSequence")) Fixture().use { f ->
            f.session { s ->
                if (parent == "AbstractApplication") s.createAbstractClass(parent)
                s.createClass("Child", parent)
                f.write(s) { s.newEntity("Child").setProperty("name", "child") }
                f.read(s) {
                    assertEquals(1L, sqlCount(s, "Child"))
                    assertEquals(0L, s.countClass(parent, false))
                    assertEquals(1L, s.countClass(parent, true))
                }
            }
            val message = f.check(refuse = true)!!.message
            // Either nonstandard name may be enumerated first in the abstract-parent fixture.
            val forbiddenNames = if (parent == "AbstractApplication") listOf(parent, "Child") else listOf("Child")
            assertTrue(forbiddenNames.any { message == "Target contains nonstandard class $it" })
        }
    }

    @Test
    fun `persisted sequence refuses even with an explicitly empty library cache`() {
        Fixture().use { f ->
            f.session { s ->
                s.execute("create sequence allocated type ordered").close()
                f.read(s) {
                    assertEquals(1L, sqlCount(s, "OSequence"))
                    s.metadata.sequenceLibrary.load()
                    assertTrue(s.metadata.sequenceLibrary.sequenceNames.isNotEmpty())
                }
                // Public cache lifecycle operation does not remove the committed sequence record.
                (s.metadata.sequenceLibrary as SequenceLibraryProxy).delegate.close()
                assertTrue(s.metadata.sequenceLibrary.sequenceNames.isEmpty())
                f.read(s) { assertEquals(1L, sqlCount(s, "OSequence")) }
            }
            assertEquals("Target sequence library is not empty", f.check(refuse = true)!!.message)
        }
    }

    @Test
    fun `explicit load clears a stale sequence name left by rolled back creation`() {
        Fixture().use { f ->
            f.session { s ->
                s.begin()
                try {
                    s.execute("create sequence rolledBack type ordered").close()
                    assertTrue(s.metadata.sequenceLibrary.sequenceNames.isNotEmpty())
                } finally { s.rollback() }
                f.read(s) { assertEquals(0L, sqlCount(s, "OSequence")) }
                assertTrue(s.metadata.sequenceLibrary.sequenceNames.isNotEmpty(), "Real stale cache after rollback")
            }
            f.check()
            f.session { s -> assertTrue(s.metadata.sequenceLibrary.sequenceNames.isEmpty()) }
        }
    }

    @Test
    fun `missing sequence class passes and stays absent after explicit library load`() {
        Fixture().use { f ->
            f.session { s -> s.schema.dropClass("OSequence"); assertNull(s.schema.getClass("OSequence")) }
            f.check()
            f.session { s -> assertNull(s.schema.getClass("OSequence")) }
        }
    }

    @Test
    fun `native schema read denial refuses`() = deniedObservation(sequenceCollection = false)

    @Test
    fun `native sequence collection read denial refuses`() = deniedObservation(sequenceCollection = true)

    private fun deniedObservation(sequenceCollection: Boolean) {
        Fixture().use { f ->
            f.session { s ->
                if (sequenceCollection) s.execute("create sequence allocated type ordered").close()
                val resources = if (sequenceCollection) s.schema.getClass("OSequence")!!.collectionIds.map {
                    "database.collection.${assertNotNull(s.getCollectionNameById(it))}"
                } else listOf("database.schema")
                f.write(s) {
                    for (resource in resources) s.execute("revoke read on $resource from reader").close()
                }
                if (sequenceCollection) f.read(s) { assertEquals(1L, sqlCount(s, "OSequence")) }
            }
            val before = f.snapshot()
            f.manager.open("target", "observer", "readerPassword").use { s ->
                f.read(s) {
                    assertFailsWith<com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException> {
                        if (sequenceCollection) s.metadata.sequenceLibrary.load()
                        else s.schema.classes
                    }
                }
                val failure = assertFailsWith<XodusMigrationFreshnessRefusal> { XodusMigrationTargetFreshness.inspect(s) }
                assertIs<com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException>(failure.cause)
                assertFalse(s.isTxActive)
                assertFalse(s.isClosed)
            }
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `active caller transactions refuse without changing pending work or ownership`() {
        Fixture().use { f ->
            val before = f.snapshot()
            f.session { s ->
                s.begin()
                try {
                    val pending = s.newEntity("OFunction")
                    pending.setProperty("name", "pending")
                    assertFailsWith<XodusMigrationFreshnessRefusal> { XodusMigrationTargetFreshness.inspect(s) }
                    assertTrue(s.isTxActive)
                    assertEquals("pending", pending.getProperty<String>("name"))
                } finally { s.rollback() }
                s.beginReadOnly()
                try {
                    assertFailsWith<XodusMigrationFreshnessRefusal> { XodusMigrationTargetFreshness.inspect(s) }
                    assertTrue(s.isTxActive)
                } finally { s.rollback() }
            }
            assertEquals(before, f.snapshot())
        }
    }

    @Test
    fun `unavailable session refuses with the underlying read failure`() {
        Fixture().use { f ->
            val s = f.manager.open("target", "application", "applicationPassword")
            s.close()
            val failure = assertFailsWith<XodusMigrationFreshnessRefusal> { XodusMigrationTargetFreshness.inspect(s) }
            assertNotNull(failure.cause)
            assertTrue(s.isClosed)
        }
    }

    private fun sqlCount(s: DatabaseSessionEmbedded, name: String): Long =
        s.query("select count(*) as total from `$name`").use { assertNotNull(it.next().getProperty<Number>("total")).toLong() }

    private inner class Fixture : AutoCloseable {
        private val params = YTDBDatabaseParams.builder().withDatabasePath(temporary.newFolder().absolutePath)
            .withDatabaseName("target").withDatabaseType(DatabaseType.DISK)
            .withAppUser("application", "applicationPassword").addReaderUser("observer", "readerPassword").build()
        val manager = YouTrackDBFactory.createEmbedded(params) as YouTrackDBImpl
        private val provider = YTDBDatabaseProviderFactory.createProvider(params, manager)

        fun session(block: (DatabaseSessionEmbedded) -> Unit) =
            manager.open("target", "application", "applicationPassword").use(block)

        fun read(s: DatabaseSessionEmbedded, block: () -> Unit) {
            s.beginReadOnly()
            try { block() } finally { s.rollback() }
        }

        fun write(s: DatabaseSessionEmbedded, block: () -> Unit) {
            s.begin()
            try { block(); s.commit() } catch (e: Throwable) { s.rollback(); throw e }
        }

        // SQL independently records every class-visible RID/version, including standard records.
        // Schema names, own IDs and index names cover the small logical persistence boundary.
        // This is not a raw storage/configuration oracle and does not call the inspector.
        fun snapshot(): Map<String, List<String>> {
            var result = emptyMap<String, List<String>>()
            session { s -> read(s) {
                result = s.schema.classes.associate { clazz ->
                    val records = s.query("select @rid as rid, @version as version from `${clazz.name}`").use { rows ->
                        rows.toList().map { "${it.getProperty<Any>("rid")}@${it.getProperty<Any>("version")}" }.sorted()
                    }
                    clazz.name to (listOf(clazz.collectionIds.sorted().toString()) + records)
                } + ("<indexes>" to s.schema.indexes.sorted())
            } }
            return result
        }

        fun check(refuse: Boolean = false): XodusMigrationFreshnessRefusal? {
            val before = snapshot()
            var failure: XodusMigrationFreshnessRefusal? = null
            session { s ->
                if (refuse) failure = assertFailsWith { XodusMigrationTargetFreshness.inspect(s) }
                else XodusMigrationTargetFreshness.inspect(s)
                assertFalse(s.isTxActive)
                assertFalse(s.isClosed)
                read(s) { assertTrue(sqlCount(s, "OUser") > 0, "Caller session remains usable") }
            }
            assertEquals(before, snapshot(), "Independent post-open logical state remains unchanged")
            return failure
        }

        override fun close() { provider.close() }
    }
}
