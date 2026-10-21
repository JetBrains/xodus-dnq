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
@file:OptIn(ExperimentalStdlibApi::class)

package jetbrains.exodus.migrate

import YTDBDatabaseProviderFactory
import YouTrackDBFactory
import com.jetbrains.youtrackdb.api.DatabaseType
import jetbrains.exodus.entitystore.youtrackdb.YTDBComparableSet
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseParams
import jetbrains.exodus.entitystore.youtrackdb.YTDBDatabaseProvider
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.shaded.exodus.bindings.StringBinding
import jetbrains.shaded.exodus.entitystore.PersistentEntityStores
import jetbrains.shaded.exodus.util.ByteArraySizedInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class PropertyNameConversionMigrationTest {
    @get:Rule
    val target = InMemoryYouTrackDB(initializeIssueSchema = false, autoInitializeSchemaBuddy = false)
    @get:Rule
    val source = XodusTestDB()
    @get:Rule
    val temporary = TemporaryFolder()

    private val mapper: (String) -> String = { name ->
        mapOf("oldTitle" to "title", "broken" to "repairedName")[name]
            ?: if (name.startsWith("legacy_")) name.removePrefix("legacy_") else name
    }

    private fun copy(map: (String) -> String = { it }) =
        migrateDataFromXodusToOrientDb(source.store, target.provider, mapPropertyName = map)

    private fun check(stats: XodusToOrientMigrationStats, map: (String) -> String = { it }) =
        checkDataIsSame(source.store, target.store, stats.xEntityIdToOEntityId, mapPropertyName = map)

    @Test
    fun `map plus rule preserves independently expected names and values`() {
        val raw = "e197b0cf83c4a7e2b1a2ceb1c59fc4a7ceb96d20e1b9a8c4a7ceb1e1b8adeda080c4a700".hexToByteArray()
        val broken = StringBinding.BINDING.readObject(ByteArraySizedInputStream(raw)) as String
        assertFailsWith<java.nio.charset.MalformedInputException> { broken.encodeToByteArray(throwOnInvalidSequence = true) }
        source.withTx { tx ->
            tx.createEntities(pileOfEntities(
                eProps("Item", 1, "oldTitle" to "hello", "legacy_count" to 42, "broken" to broken),
                eSets("Item", 2, "legacy_tags" to setOf("red", "blue"))
            ))
        }
        val stats = copy(mapper)
        check(stats, mapper)
        target.withStoreTx { tx ->
            val first = tx.getAll("Item").single { it.getProperty("id") == 1 }
            assertEquals(setOf("id", "title", "count", "repairedName"), first.propertyNames.toSet())
            assertEquals("hello", first.getProperty("title"))
            assertEquals(42, first.getProperty("count"))
            assertEquals("ᗰσħⱢαşħιm Ṩħαḭ?ħ", first.getProperty("repairedName"))
            val second = tx.getAll("Item").single { it.getProperty("id") == 2 }
            assertEquals(setOf("id", "tags"), second.propertyNames.toSet())
            assertEquals(setOf("red", "blue"), (second.getProperty("tags") as YTDBComparableSet<*>).toSet())
        }
        // Force the malformed-string diagnostic path, including the original-name raw read.
        target.withStoreTx {
            it.getAll("Item").single { e -> e.getProperty("id") == 1 }.setProperty("repairedName", "wrong")
        }
        val failure = assertFailsWith<IllegalArgumentException> { check(stats, mapper) }
        assertTrue(failure.message.orEmpty().contains("xStore raw bytes:"))
    }

    @Test
    fun `omitted and explicit identity preserve ordinary names`() {
        source.withTx { it.createEntities(pileOfEntities(eProps("Item", 1, "value" to 7))) }
        val stats = migrateDataFromXodusToOrientDb(source.store, target.provider)
        checkDataIsSame(source.store, target.store, stats.xEntityIdToOEntityId)
        check(stats) { it }
        target.withStoreTx { assertEquals(7, it.getAll("Item").first().getProperty("value")) }
    }

    @Test
    fun `explicit identity copier preserves names`() {
        source.withTx { it.createEntities(pileOfEntities(eProps("Item", 1, "value" to 7))) }
        val stats = copy { it }
        check(stats)
        target.withStoreTx { assertEquals(setOf("id", "value"), it.getAll("Item").first().propertyNames.toSet()) }
    }

    @Test
    fun `wrong checker mapping fails independently`() {
        source.withTx {
            it.createEntities(pileOfEntities(eProps("Item", 1, "legacy_count" to 42, "other" to 99)))
        }
        val stats = copy(mapper)
        target.withStoreTx {
            val entity = it.getAll("Item").first()
            assertEquals(42, entity.getProperty("count"))
            assertEquals(99, entity.getProperty("other"))
        }
        val wrong: (String) -> String = {
            when (it) { "legacy_count" -> "other"; "other" -> "count"; else -> it }
        }
        val failure = assertFailsWith<IllegalArgumentException> { check(stats, wrong) }
        assertTrue(failure.message.orEmpty().contains("properties are different"))
    }

    @Test
    fun `altered mapped scalar fails independently`() {
        source.withTx { it.createEntities(pileOfEntities(eProps("Item", 1, "legacy_count" to 42))) }
        val stats = copy(mapper)
        target.withStoreTx { it.getAll("Item").first().setProperty("count", 43) }
        assertFailsWith<IllegalArgumentException> { check(stats, mapper) }
    }

    private fun refuse(destination: String, second: String? = null, blob: Boolean = false) {
        source.withTx { tx ->
            val e = tx.newEntity("Item")
            e.setProperty("first", 1)
            if (second != null) e.setProperty(second, 2)
            if (blob) e.setBlobString(destination, "blob")
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            copy { if (it == "first" || it == "second") destination else it }
        }
        assertTrue(failure.message.orEmpty().contains("destination collision"), failure.toString())
        source.withTx { assertEquals(1, it.getAll("Item").first().getProperty("first")) }
    }

    @Test fun `two renamed ordinary names cannot share a destination`() = refuse("shared", second = "second")
    @Test fun `renamed name cannot reach unchanged destination`() = refuse("existing", second = "existing")
    @Test fun `renamed name cannot reach blob`() = refuse("payload", blob = true)
    @Test fun `renamed name cannot reach local entity id`() = refuse("localEntityId")
    @Test fun `renamed name cannot reach blob size suffix`() = refuse("_payload_blob_size")
    @Test fun `renamed name cannot reach string hash suffix`() = refuse("_payload_string_blob_hash")
    @Test fun `renamed name cannot reach link target suffix`() = refuse("next_targetEntityId")

    @Test
    fun `checker refuses renamed collisions before identity filtering can hide them`() {
        source.withTx { tx ->
            val e = tx.newEntity("Item")
            e.setProperty("first", 1)
            e.setProperty("existing", 2)
            e.setBlobString("payload", "blob")
        }
        val stats = copy()
        for (destination in listOf("payload", "localEntityId", "_payload_blob_size",
            "_payload_string_blob_hash", "next_targetEntityId", "existing")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                check(stats) { if (it == "first") destination else it }
            }
            assertTrue(failure.message.orEmpty().contains("destination collision"), failure.toString())
        }
    }

    @Test
    fun `pinned engine refuses graph edge field names without a DNQ name policy`() {
        source.withTx { it.newEntity("Item").setProperty("first", 1) }
        val failure = assertFailsWith<IllegalArgumentException> { copy { "out_reserved" } }
        assertTrue(failure.message.orEmpty().contains("booked"), failure.toString())
    }

    @Test
    fun `destinations may be reused on different entities`() {
        source.withTx { it.createEntities(pileOfEntities(
            eProps("Item", 1, "first" to 11), eProps("Item", 2, "first" to 22),
            eProps("Item", 3, "second" to 33)
        )) }
        val map: (String) -> String = { if (it == "first" || it == "second") "shared" else it }
        val stats = copy(map)
        check(stats, map)
        target.withStoreTx { tx ->
            val values = tx.getAll("Item").associate { it.getProperty("id") to it.getProperty("shared") }
            assertEquals<Map<*, *>>(mapOf(1 to 11, 2 to 22, 3 to 33), values)
            assertTrue(tx.getAll("Item").all { it.propertyNames.toSet() == setOf("id", "shared") })
        }
    }

    @Test
    fun `blobs and links keep names while same-name ordinary property maps safely away`() {
        source.withTx { tx ->
            val first = tx.newEntity("Item")
            val second = tx.newEntity("Item")
            first.setProperty("payload", "ordinary")
            first.setBlobString("payload", "blob")
            first.setProperty("next", 7)
            first.addLink("next", second)
        }
        val stats = copy { "mapped_$it" }
        check(stats) { "mapped_$it" }
        target.withStoreTx { tx ->
            val first = tx.getAll("Item").single { "payload" in it.blobNames }
            assertEquals("ordinary", first.getProperty("mapped_payload"))
            assertEquals(7, first.getProperty("mapped_next"))
            assertEquals("blob", first.getBlobString("payload"))
            assertEquals(setOf("payload"), first.blobNames.toSet())
            assertEquals(setOf("next"), first.linkNames.toSet())
            assertEquals(tx.getAll("Item").single { it.id != first.id }.id, first.getLink("next")!!.id)
        }
        target.withStoreTx { tx ->
            tx.getAll("Item").single { "payload" in it.blobNames }.setProperty("mapped_payload", "wrong")
        }
        val failure = assertFailsWith<IllegalArgumentException> { check(stats) { "mapped_$it" } }
        assertTrue(failure.message.orEmpty().contains("payload strings are different"), failure.toString())
        assertTrue(failure.message.orEmpty().contains("xStore: 'ordinary'"), failure.toString())
        assertTrue(failure.message.orEmpty().contains("oStore: 'wrong'"), failure.toString())
    }

    @Test
    fun `ordinary property may keep the same name as a link`() {
        source.withTx { tx ->
            val first = tx.newEntity("Item")
            first.setProperty("old", 7)
            first.addLink("next", tx.newEntity("Item"))
        }
        val stats = copy { "next" }
        check(stats) { "next" }
        target.withStoreTx { tx ->
            val first = tx.getAll("Item").single { "next" in it.propertyNames }
            assertEquals(7, first.getProperty("next"))
            assertNotNull(first.getLink("next"))
        }
    }

    @Test
    fun `mapper exception identity reaches copy caller`() {
        source.withTx { it.newEntity("Item").setProperty("value", 1) }
        val failure = IllegalStateException("mapper failed")
        assertSame(failure, assertFailsWith<IllegalStateException> { copy { throw failure } })
    }

    @Test
    fun `mapper exception identity reaches checker caller`() {
        source.withTx { it.newEntity("Item").setProperty("value", 1) }
        val stats = copy()
        val failure = IllegalStateException("mapper failed")
        assertSame(failure, assertFailsWith<IllegalStateException> { check(stats) { throw failure } })
    }

    @Test
    fun `launcher forwards mapper to both phases`() {
        val directory = temporary.newFolder()
        val store = PersistentEntityStores.newInstance(directory)
        val name = store.name
        try { store.executeInTransaction { it.newEntity("Item").setProperty("oldTitle", "hello") } }
        finally { store.close() }
        var cleanup = false
        XodusToOrientDataMigratorLauncher(
            MigrateToOrientConfig(target.provider, target.params),
            MigrateFromXodusConfig(directory.path, name, null, 0, 10),
            true, 1, mapper
        ).migrate { cleanup = true }
        assertTrue(cleanup, "Successful mapped validation reaches cleanup")
        target.withStoreTx { tx ->
            val e = tx.getAll("Item").first()
            assertEquals(setOf("title"), e.propertyNames.toSet())
            assertEquals("hello", e.getProperty("title"))
        }
    }

    @Test
    fun `disabled validation collision preserves reopened source and refuses durable partial target retry`() {
        val directory = temporary.newFolder()
        val store = PersistentEntityStores.newInstance(directory)
        val name = store.name
        try {
            store.executeInTransaction { tx ->
                tx.newEntity("Item").setProperty("safe", 17)
                val second = tx.newEntity("Item")
                second.setProperty("first", 1)
                second.setProperty("second", 2)
                second.setBlobString("payload", "source blob")
            }
        } finally { store.close() }
        val params = YTDBDatabaseParams.builder().withDatabasePath(temporary.newFolder().path)
            .withDatabaseName("target").withDatabaseType(DatabaseType.DISK).withAppUser("admin", "password").build()
        var provider = YTDBDatabaseProviderFactory.createProvider(params, YouTrackDBFactory.createEmbedded(params))
        fun launcher(p: YTDBDatabaseProvider) = XodusToOrientDataMigratorLauncher(
            MigrateToOrientConfig(p, params), MigrateFromXodusConfig(directory.path, name, null, 0, 10, true),
            false, 1, { if (it == "first" || it == "second") "shared" else it }
        )
        try {
            val failure = assertFailsWith<IllegalArgumentException> {
                launcher(provider).migrate { fail("Collision must not make source cleanup eligible") }
            }
            assertTrue(failure.message.orEmpty().contains("destination collision"))
            assertFalse(File(directory, "xodus_backup").exists())
            val reopenedSource = PersistentEntityStores.newInstance(directory)
            try {
                reopenedSource.withReadonlyTx { tx ->
                    val entities = tx.getAll("Item").toList()
                    assertEquals(2, entities.size)
                    assertEquals(17, entities.first().getProperty("safe"))
                    assertEquals(1, entities.last().getProperty("first"))
                    assertEquals(2, entities.last().getProperty("second"))
                    assertEquals("source blob", entities.last().getBlobString("payload"))
                }
            } finally { reopenedSource.close() }
            provider.close()
            provider = YTDBDatabaseProviderFactory.createProvider(params, YouTrackDBFactory.createEmbedded(params))
            provider.withSession { s ->
                s.beginReadOnly()
                try {
                    s.query("select safe from Item").use { rows ->
                        assertEquals(17, rows.next().getProperty<Int>("safe"))
                        assertFalse(rows.hasNext())
                    }
                } finally { s.rollback() }
            }
            assertFailsWith<XodusMigrationFreshnessRefusal> {
                launcher(provider).migrate { fail("Retry refusal must not clean up") }
            }
        } finally { provider.close() }
    }
}
