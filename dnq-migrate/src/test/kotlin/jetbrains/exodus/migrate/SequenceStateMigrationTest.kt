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
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseParams
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.YTDBPersistentEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddyImpl
import jetbrains.exodus.entitystore.youtrackdb.createVertexClassWithClassId
import jetbrains.shaded.exodus.bindings.LongBinding
import jetbrains.shaded.exodus.entitystore.PersistentEntityStoreImpl
import jetbrains.shaded.exodus.entitystore.PersistentEntityStores
import jetbrains.shaded.exodus.entitystore.PersistentStoreTransaction
import jetbrains.shaded.exodus.env.StoreConfig
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class SequenceStateMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `saved application values survive reopen and cached-only handles stay absent`() = Fixture().use { f ->
        f.source.executeInTransaction { tx ->
            tx.newEntity("Item")
            tx.getSequence("zero").set(0)
            tx.getSequence("custom").set(71)
            tx.getSequence("wide", 2147483650L).increment()
            tx.getSequence("lowered").set(90)
        }
        f.source.executeInTransaction { tx ->
            (tx.getSequence("lowered") as jetbrains.shaded.exodus.entitystore.PersistentSequence).forceSet(7)
        }
        f.reopenSource()
        f.source.withReadonlyTx { tx ->
            assertEquals(-1L, tx.getSequence("cachedOnly").get())
            assertEquals(99L, tx.getSequence("cachedInitial", 100).get())
        }
        assertFalse(f.rawRows().containsKey("cachedOnly"))
        assertFalse(f.rawRows().containsKey("cachedInitial"))
        val stats = f.copy()
        val expected = mapOf("sequence_classId" to 0L, "Item_sequence_localEntityId" to 0L,
            "zero" to 0L, "custom" to 71L, "wide" to 2147483650L, "lowered" to 7L)
        assertEquals(expected, f.values())
        f.reopenTarget()
        assertEquals(expected, f.values())
        val before = f.rows()
        repeat(2) { f.check(stats) }
        assertEquals(before, f.rows(), "Checking must keep record IDs, versions, names and values")
        f.provider.withSession { s ->
            for ((name, value) in mapOf("zero" to 1L, "custom" to 72L, "wide" to 2147483651L, "lowered" to 8L)) {
                assertEquals(value, s.metadata.sequenceLibrary.getSequence(name)!!.next(s))
            }
        }
    }

    @Test
    fun `ordinary allocator history survives deleted maxima empty types and reservations`() = Fixture().use { f ->
        f.source.executeInTransaction { tx ->
            tx.newEntity("Item")
            tx.newEntity("Item").delete()
            tx.newEntity("Reserved")
            tx.newEntity("Empty").delete()
            f.source.getEntityTypeId(tx as PersistentStoreTransaction, "Never", true)
            tx.newEntity("Deleted").delete()
        }
        f.source.executeInTransaction { tx ->
            f.source.deleteEntityType("Deleted")
            tx.getSequence("${f.source.name}.entities.sequences#1").set(20)
        }
        f.reopenSource()
        assertEquals(setOf("Item", "Reserved", "Empty", "Never"), f.source.withReadonlyTx { it.entityTypes.toSet() })
        val stats = f.copy()
        f.reopenTarget()
        assertEquals(mapOf("sequence_classId" to 4L, "Item_sequence_localEntityId" to 1L,
            "Reserved_sequence_localEntityId" to 20L, "Empty_sequence_localEntityId" to 0L,
            "Never_sequence_localEntityId" to -1L), f.values())
        f.check(stats)
        f.provider.withSession { s -> s.transaction { s.createVertexClassWithClassId("NewType") } }
        f.withTarget { target ->
            target.executeInTransaction { tx ->
                assertEquals(2L, tx.newEntity("Item").id.localId)
                assertEquals(21L, tx.newEntity("Reserved").id.localId)
                assertEquals(1L, tx.newEntity("Empty").id.localId)
                assertEquals(0L, tx.newEntity("Never").id.localId)
                assertEquals(5, tx.newEntity("NewType").id.typeId)
            }
        }
    }

    @Test
    fun `exact internal roster excludes only canonical names and keeps near patterns`(): Unit = Fixture().use { f ->
        f.source.executeInTransaction { it.newEntity("Item") }
        val p = f.source.name
        val excluded = listOf("$p.property.types.sequence", "$p.link.types.sequence",
            "$p.property.custom.types.sequence", "blob.handles.sequence", "$p.entities.sequences#999")
        val applications = listOf("$p.entities.sequences#00", "$p.entities.sequences#-1",
            "$p.entities.sequences#x", "$p.entities.sequences#2147483648", "$p.entity.types.sequence.extra",
            "$p.blob.handles.sequence", "blob.handles.sequence.extra", "$p.property.types.sequence.extra")
        f.source.executeInTransaction { tx ->
            (excluded + applications).forEach { tx.getSequence(it).set(83) }
        }
        f.reopenSource()
        assertTrue(f.rawRows().keys.containsAll(excluded + applications))
        val stats = f.copy()
        val expected = applications.associateWith { 83L } + mapOf(
            "sequence_classId" to 0L, "Item_sequence_localEntityId" to 0L)
        assertEquals(expected, f.values())
        f.check(stats)
    }

    @Test
    fun `native normalization rejects equal values case and Unicode across namespaces before writes`() {
        val cases = listOf(
            listOf("counter", "COUNTER") to 17L, listOf("ß", "SS") to 17L, listOf("ı", "I") to 17L,
            listOf("SEQUENCE_CLASSID") to 17L, listOf("ITEM_SEQUENCE_LOCALENTITYID") to 17L,
            listOf("SEQUENCE_CLASSID") to 0L, listOf("ITEM_SEQUENCE_LOCALENTITYID") to 0L
        )
        for ((names, value) in cases) Fixture().use { f ->
            f.source.executeInTransaction { tx ->
                tx.newEntity("Item").setProperty("sentinel", "source")
                names.forEach { tx.getSequence(it).set(value) }
            }
            f.reopenSource()
            val sourceBefore = f.rawRows()
            val before = f.snapshot()
            val failure = assertFailsWith<IllegalArgumentException> { f.copy() }
            assertTrue(failure.message.orEmpty().contains("Sequence destination collision"))
            assertEquals(before, f.snapshot())
            f.reopenSource()
            assertEquals(sourceBefore, f.rawRows())
            assertEquals("source", f.source.withReadonlyTx { it.getAll("Item").first().getProperty("sentinel") })
        }
        assertEquals("SS", normalizedMigrationSequenceName("ß"))
        assertEquals("I", normalizedMigrationSequenceName("ı"))
    }

    @Test
    fun `Issue and issue allocators refuse both entries including disabled validation without cleanup`() {
        for (direct in listOf(true, false)) Fixture().use { f ->
            f.source.executeInTransaction { tx -> tx.newEntity("Issue"); tx.newEntity("issue") }
            f.reopenSource()
            val sourceBefore = f.rawRows()
            val before = f.snapshot()
            val failure = assertFailsWith<IllegalArgumentException> {
                if (direct) f.copy() else {
                    val name = f.source.name
                    f.closeSource()
                    XodusToOrientDataMigratorLauncher(
                        MigrateToOrientConfig(f.provider, f.params, false),
                        MigrateFromXodusConfig(f.directory.path, name, null, 0, 10, true), false, 1
                    ).migrate { fail("Collision must block cleanup even without validation") }
                    error("Collision must refuse")
                }
            }
            assertTrue(failure.message.orEmpty().contains("Sequence destination collision"))
            assertEquals(before, f.snapshot())
            f.reopenSource()
            assertEquals(sourceBefore, f.rawRows())
            assertEquals(setOf("Issue", "issue"), f.source.withReadonlyTx { it.entityTypes.toSet() })
            assertEquals(1L, f.source.withReadonlyTx { it.getAll("Issue").size() })
            assertEquals(1L, f.source.withReadonlyTx { it.getAll("issue").size() })
        }
    }

    @Test
    fun `checker detects every missing and changed counter without repair even with stale cached handles`() {
        for (name in listOf("application", "sequence_classId", "Item_sequence_localEntityId")) {
            for (remove in listOf(false, true)) Fixture().use { f ->
                f.source.executeInTransaction { tx -> tx.newEntity("Item"); tx.getSequence("application").set(71) }
                f.reopenSource()
                val stats = f.copy()
                f.provider.withSession { s ->
                    assertNotNull(s.metadata.sequenceLibrary.getSequence(name))
                    s.transaction { tx ->
                        tx.command(if (remove) "delete from OSequence where name = '$name'"
                            else "update OSequence set value = 999 where name = '$name'")
                    }
                }
                val before = f.rows()
                val failure = assertFailsWith<IllegalArgumentException> { f.check(stats) }
                assertTrue(failure.message.orEmpty().contains("Sequence state differs"))
                assertTrue(failure.message.orEmpty().contains(name))
                assertEquals(before, f.rows(), "Mismatch checking must not create or repair a row")
                assertEquals(if (remove) null else 999L, f.values()[name])
            }
        }
    }

    @Test
    fun `checker rejects a renamed counter with unchanged count and values without repair`() = Fixture().use { f ->
        f.source.executeInTransaction { tx -> tx.newEntity("Item"); tx.getSequence("application").set(71) }
        f.reopenSource()
        val stats = f.copy()
        val expected = mapOf("sequence_classId" to 0L, "Item_sequence_localEntityId" to 0L, "application" to 71L)
        assertEquals(expected, f.values())
        f.provider.withSession { s ->
            s.transaction { tx -> tx.command("update OSequence set name = 'wrongApplication' where name = 'application'") }
        }
        val before = f.rows()
        val corrupted = f.values()
        assertEquals(mapOf("sequence_classId" to 0L, "Item_sequence_localEntityId" to 0L,
            "wrongApplication" to 71L), corrupted)
        assertEquals(expected.size, corrupted.size)
        assertEquals(expected.values.sorted(), corrupted.values.sorted())
        val failure = assertFailsWith<IllegalArgumentException> { f.check(stats) }
        assertTrue(failure.message.orEmpty().contains("Sequence state differs"))
        assertTrue(failure.message.orEmpty().contains("application"))
        assertTrue(failure.message.orEmpty().contains("wrongApplication"))
        assertEquals(before, f.rows(), "Mismatch checking must keep corrupted record IDs, versions, names and values")
    }

    @Test
    fun `empty source uses minus one global reservation and checker sees unexpected counters`() = Fixture().use { f ->
        f.reopenSource()
        val stats = f.copy()
        assertEquals(mapOf("sequence_classId" to -1L), f.values())
        f.check(stats)
        f.provider.withSession { s ->
            s.metadata.sequenceLibrary.createSequence("unexpected", DBSequence.SEQUENCE_TYPE.ORDERED,
                DBSequence.CreateParams().setStart(12))
        }
        val before = f.rows()
        assertFailsWith<IllegalArgumentException> { f.check(stats) }
        assertEquals(before, f.rows())
    }

    @Test
    fun `negative application cache values are not canonical saved rows`() = Fixture().use { f ->
        f.source.executeInTransaction { it.getSequence("negative").set(-7) }
        assertFalse(f.rawRows().containsKey("negative"))
        assertFailsWith<IllegalArgumentException> { LongBinding.longToCompressedEntry(-7) }
        f.reopenSource()
        f.copy()
        assertEquals(mapOf("sequence_classId" to -1L), f.values())
        f.withTarget { target ->
            target.executeInTransaction { tx ->
                assertEquals(100L, tx.getSequence("targetInitializer", 100).get())
            }
        }
    }

    @Test
    fun `pinned comparison rule uppercases whole names without trimming or Unicode normalization`() {
        for ((name, expected) in mapOf("counter" to "COUNTER", "ß" to "SS", "ı" to "I",
            "i" to "I", "İ" to "İ", " counter " to " COUNTER ", "é" to "É", "e\u0301" to "E\u0301")) {
            assertEquals(expected, normalizedMigrationSequenceName(name))
        }
        assertNotEquals(normalizedMigrationSequenceName("counter"), normalizedMigrationSequenceName("other"))
        assertNotEquals(normalizedMigrationSequenceName("é"), normalizedMigrationSequenceName("e\u0301"))
    }

    private inner class Fixture : AutoCloseable {
        val directory = temporary.newFolder()
        var source = PersistentEntityStores.newInstance(directory) as PersistentEntityStoreImpl
        private var sourceOpen = true
        val params = YTDBDatabaseParams.builder().withDatabasePath(temporary.newFolder().path)
            .withDatabaseName("target").withDatabaseType(DatabaseType.DISK).withAppUser("admin", "password").build()
        var provider: YTDBDatabaseProvider = YTDBDatabaseProviderFactory.createProvider(params, YouTrackDBFactory.createEmbedded(params))

        fun closeSource() {
            if (sourceOpen) source.close()
            sourceOpen = false
        }
        fun reopenSource() {
            closeSource()
            source = PersistentEntityStores.newInstance(directory) as PersistentEntityStoreImpl
            sourceOpen = true
        }
        fun reopenTarget() {
            provider.close()
            provider = YTDBDatabaseProviderFactory.createProvider(params, YouTrackDBFactory.createEmbedded(params))
        }
        fun copy() = migrateDataFromXodusToOrientDb(source, provider, entitiesPerTransaction = 1)
        fun <T> withTarget(block: (YTDBPersistentEntityStore) -> T): T =
            YTDBPersistentEntityStore(provider, "target", YTDBSchemaBuddyImpl(provider, autoInitialize = false)).use(block)
        fun check(stats: XodusToOrientMigrationStats) = withTarget {
            checkDataIsSame(source, it, stats.xEntityIdToOEntityId)
        }

        // This oracle reads literal target rows through the native session, not the production plan.
        fun rows(): List<List<Any?>> = provider.withSession { s ->
            s.beginReadOnly()
            try {
                s.query("select @rid as rid, @version as version, name, value from OSequence order by name").use { rows ->
                    rows.toList().map { row -> listOf("rid", "version", "name", "value").map { row.getProperty<Any>(it) } }
                }
            } finally { s.rollback() }
        }
        fun values() = rows().associate { it[2] as String to (it[3] as Number).toLong() }
        fun snapshot() = provider.withSession { s ->
            s.beginReadOnly()
            try {
                s.schema.classes.associate { clazz ->
                    clazz.name to (listOf(clazz.collectionIds.sorted().toString()) +
                        s.query("select @rid as rid, @version as version from `${clazz.name}`").use { rows ->
                            rows.toList().map { "${it.getProperty<Any>("rid")}@${it.getProperty<Any>("version")}" }.sorted()
                        })
                } + ("<indexes>" to s.schema.indexes.sorted())
            } finally { s.rollback() }
        }
        fun rawRows(): Map<String, Long> = source.withReadonlyTx { tx ->
            val raw = (tx as PersistentStoreTransaction).environmentTransaction
            val store = source.environment.openStore("sequences", StoreConfig.USE_EXISTING, raw)
            val values = linkedMapOf<String, Long>()
            store.openCursor(raw).use { c ->
                while (c.next) values[String(c.key.bytesUnsafe, 0, c.key.length, Charsets.UTF_8)] =
                    LongBinding.compressedEntryToLong(c.value)
            }
            values
        }
        override fun close() {
            try { closeSource() } finally { provider.close() }
        }
    }
}
