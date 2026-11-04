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
package jetbrains.exodus.query.metadata

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType
import jetbrains.exodus.entitystore.youtrackdb.withTx
import jetbrains.exodus.entitystore.youtrackdb.createClassIdSequenceIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.setClassIdIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class YouTrackDbSchemaTargetTest {
    @Rule @JvmField
    val db = InMemoryYouTrackDB(initializeIssueSchema = false, autoInitializeSchemaBuddy = false)

    @Test
    fun `literal scalar and set mappings with optional and required flags`() {
        val expected = mapOf(
            "Boolean" to EngineType.BOOLEAN, "STRING" to EngineType.STRING,
            "byte" to EngineType.BYTE, "short" to EngineType.SHORT,
            "int" to EngineType.INTEGER, "Integer" to EngineType.INTEGER,
            "long" to EngineType.LONG, "float" to EngineType.FLOAT,
            "double" to EngineType.DOUBLE, "datetime" to EngineType.LONG
        )
        val target = model {
            entity("Example") {
                expected.keys.forEach {
                    property(it, it)
                    property("required$it", it, required = true)
                    setProperty("set$it", it)
                }
                blobProperty("blob")
                stringBlobProperty("text")
                // The Java primitive name must not override the property kind.
                propertiesMetaData = listOf(SimplePropertyMetaDataImpl("kindFirst", "unsupported").apply {
                    type = PropertyType.TEXT
                })
            }
        }.youTrackDbSchemaTarget()
        assertEquals(setOf("Example"), target.keys)
        val properties = target.getValue("Example").properties
        for ((name, type) in expected) {
            assertEquals(YouTrackDbPropertyTarget(
                YouTrackDbPropertyMapping(name, PropertyType.PRIMITIVE, type), false, false
            ), properties[name])
            assertEquals(YouTrackDbPropertyTarget(
                YouTrackDbPropertyMapping("required$name", PropertyType.PRIMITIVE, type), true, true
            ), properties["required$name"])
            assertEquals(YouTrackDbPropertyTarget(
                YouTrackDbPropertyMapping("set$name", PropertyType.PRIMITIVE, EngineType.EMBEDDEDSET, type), false, false
            ), properties["set$name"])
        }
        for ((name, kind) in mapOf("blob" to PropertyType.BLOB, "text" to PropertyType.TEXT,
            "kindFirst" to PropertyType.TEXT)) {
            assertEquals(YouTrackDbPropertyTarget(
                YouTrackDbPropertyMapping(name, kind, EngineType.LINK), false, false
            ), properties[name])
        }
        assertEquals(YouTrackDbPropertyTarget(
            YouTrackDbPropertyMapping("localEntityId", PropertyType.PRIMITIVE, EngineType.LONG), true, true
        ), properties["localEntityId"])
        assertEquals(34, properties.size)
    }

    @Test
    fun `unsupported scalar and set elements and missing set parameter fail loudly`() {
        for (type in listOf("unsupported", "Set")) {
            val m = model { entity("Example") { property("broken", type) } }
            if (type == "Set") {
                assertFailsWith<IllegalStateException> { m.youTrackDbSchemaTarget() }
            } else {
                assertFailsWith<IllegalArgumentException> { m.youTrackDbSchemaTarget() }
            }
        }
        val m = model { entity("Example") { setProperty("broken", "unsupported") } }
        assertFailsWith<IllegalArgumentException> { m.youTrackDbSchemaTarget() }
    }

    @Test
    fun `literal superclass requirements stay in their class contexts`() {
        fun EntityMetaDataImpl.properties() {
            property("inherited", "string")
            property("childOnly", "string")
            property("modelRequired", "string", required = true)
            property("optional", "string")
        }
        val m = model {
            entity("Base") {
                properties()
                index("inherited")
                index(IndexedField("linkWithoutSimpleProperty", false))
            }
            entity("Middle", "Base") { properties() }
            entity("Leaf", "Middle") { properties(); index("childOnly") }
            entity("Sibling", "Base") { properties() }
        }
        val target = m.youTrackDbSchemaTarget()
        for ((type, required) in mapOf(
            "Base" to setOf("inherited", "modelRequired", "localEntityId"),
            "Middle" to setOf("inherited", "modelRequired", "localEntityId"),
            "Leaf" to setOf("inherited", "childOnly", "modelRequired", "localEntityId"),
            "Sibling" to setOf("inherited", "modelRequired", "localEntityId")
        )) {
            val properties = target.getValue(type).properties
            assertEquals(setOf("inherited", "childOnly", "modelRequired", "optional", "localEntityId"),
                properties.keys)
            for ((name, property) in properties) {
                assertEquals(name in required, property.mandatory, "$type.$name mandatory")
                assertEquals(name in required, property.notNull, "$type.$name notNull")
            }
        }
    }

    @Test
    fun `raw acquisition rejects missing ancestors and cycles explicitly`() {
        val missing = model { entity("Leaf", "Missing") }
        assertEquals("No entity metadata for super type [Missing]",
            assertFailsWith<IllegalArgumentException> { missing.youTrackDbSchemaTarget() }.message)
        val cycle = model { entity("Base", "Leaf"); entity("Leaf", "Base") }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            cycle.youTrackDbSchemaTarget()
        }.message!!.startsWith("Cyclic entity ancestry at ["))
    }

    @Test
    fun `each raw read validates custom metadata without preparing or changing stored evidence`() {
        db.withSession { session ->
            session.createClassIdSequenceIfAbsent()
            val stored = session.createVertexClass("Historical")
            session.setClassIdIfAbsent(stored)
            stored.createProperty("value", EngineType.STRING).setNotNull(true)
            stored.createProperty("extra", EngineType.INTEGER)
            stored.createIndex("historical", "NOTUNIQUE", "value")
            session.metadata.sequenceLibrary.createSequence(
                "historicalSequence", DBSequence.SEQUENCE_TYPE.ORDERED, DBSequence.CreateParams().setStart(41)
            )
            session.withTx {
                it.newVertex("Historical").apply {
                    setProperty("value", "retained")
                    setProperty("localEntityId", 1L)
                }
            }
        }
        val cache = ModelMetaDataImpl::class.java.getDeclaredField("typeToEntityMetaDatas").apply {
            isAccessible = true
        }
        for ((case, invalid) in listOf("interfaces", null, "Unrelated", "Base", "Leaf").withIndex()) {
            val suffix = case.toString()
            db.withSession { session ->
                val stored = session.createVertexClass("Middle$suffix")
                stored.createProperty("missing", EngineType.STRING)
                stored.createProperty("extra", EngineType.INTEGER)
                stored.createIndex("historicalMiddle$suffix", "NOTUNIQUE", "extra")
            }
            class CustomEntity : EntityMetaDataImpl() {
                var memberships = false
                var raw = true
                override fun getInterfaceTypes(): Collection<String> =
                    if (memberships) listOf("Unrelated$suffix") else super.getInterfaceTypes()
                override fun getIndexes(): Set<Index> {
                    check(!raw) { "Raw acquisition called live indexes" }
                    return super.getIndexes()
                }
            }
            val middle = CustomEntity().apply {
                type = "Middle$suffix"
                superType = "Base$suffix"
                property("indexed", "string")
                property("missing", "string", required = true)
                index("indexed")
            }
            val index = middle.ownIndexes.single() as IndexImpl
            val m = oModel(db.provider, db.schemaBuddy) {
                entity("Base$suffix")
                addEntityMetaData(middle)
                entity("Leaf$suffix", middle.type)
                entity("Unrelated$suffix")
            }
            fun invalidate() {
                if (invalid == "interfaces") middle.memberships = true
                else index.ownerEntityType = invalid?.plus(suffix)
            }
            fun repair() {
                middle.memberships = false
                index.ownerEntityType = middle.type
            }
            val before = db.withSession { it.schemaEvidence() }
            fun unchanged() {
                assertNull(cache.get(m), "Raw projection published a prepared model")
                assertEquals(before, db.withSession { it.schemaEvidence() })
            }
            invalidate()
            repeat(2) {
                assertTrue(assertFailsWith<IllegalArgumentException> {
                    m.youTrackDbSchemaTarget()
                }.message!!.contains(middle.type))
                unchanged()
            }
            repair()
            val projected = m.youTrackDbSchemaTarget().getValue(middle.type)
            assertTrue(projected.properties.getValue("indexed").mandatory)
            assertTrue(projected.properties.getValue("missing").mandatory)
            assertFalse("extra" in projected.properties)
            db.withSession { session ->
                assertFalse(session.schema.getClass(middle.type).getProperty("missing").isMandatory)
                assertNull(session.schema.getClass(middle.type).getProperty("indexed"))
            }
            unchanged()
            invalidate()
            assertFailsWith<IllegalArgumentException> { m.youTrackDbSchemaTarget() }
            unchanged()
            repair()
            middle.raw = false
            m.prepare()
            assertNotNull(cache.get(m))
            db.withSession { session ->
                assertTrue(session.schema.getClass(middle.type).getProperty("missing").isMandatory)
                assertNotNull(session.schema.getClass("Leaf$suffix"))
            }
        }
    }

    @Test
    fun `raw acquisition never invokes prepare build or callbacks`() {
        var preparing = false
        var callbacks = 0
        val m = object : ModelMetaDataImpl() {
            override fun prepare(): MutableMap<String, EntityMetaData> {
                check(preparing) { "Raw projection invoked prepare" }
                return super.prepare()
            }
            override fun buildModel(build: Runnable) = error("Raw projection invoked buildModel")
            override fun onPrepared(entities: MutableCollection<EntityMetaData>) { callbacks++ }
        }
        m.entity("Example") { property("optional", "string") }
        repeat(2) { m.youTrackDbSchemaTarget() }
        assertEquals(0, callbacks)
        preparing = true
        m.prepare()
        assertEquals(1, callbacks)
    }

    @Test
    fun `alternate property implementations are absent from target and initialized schema`() {
        val alternate = listOf(
            PropertyMetaDataImpl("alternateText", PropertyType.TEXT),
            SimplePropertyMetaDataImpl("alternatePrimitive", "unsupported")
        ).map { delegate -> object : PropertyMetaData by delegate {} }
        val m = oModel(db.provider, db.schemaBuddy) {
            entity("Example") {
                propertiesMetaData = alternate
                property("supported", "string")
            }
        }
        val before = db.withSession { it.schemaEvidence() }
        val target = m.youTrackDbSchemaTarget().getValue("Example")
        assertEquals(setOf("supported", "localEntityId"), target.properties.keys)
        assertEquals(before, db.withSession { it.schemaEvidence() })
        m.prepare()
        db.withSession { session ->
            assertEquals(target.properties.keys,
                session.schema.getClass("Example").declaredProperties.map { it.name }.toSet())
        }
    }

    @Test
    fun `unprepared YTDB acquisition leaves differing stored schema intact and later prepare applies`() {
        db.withSession { session ->
            val stored = session.createVertexClass("Example")
            stored.createProperty("optional", EngineType.STRING).setNotNull(true)
            stored.createProperty("differentType", EngineType.INTEGER)
            stored.createProperty("extra", EngineType.STRING)
            stored.createIndex("historical", "NOTUNIQUE", "extra")
            session.metadata.sequenceLibrary.createSequence(
                "historicalSequence", DBSequence.SEQUENCE_TYPE.ORDERED, DBSequence.CreateParams().setStart(41)
            )
        }
        val m = oModel(db.provider, db.schemaBuddy) {
            entity("Example") {
                property("optional", "string")
                property("differentType", "long")
                property("missing", "string", required = true)
            }
            entity("MissingClass")
        }
        val before = db.withSession { it.schemaEvidence() }
        val target = m.youTrackDbSchemaTarget()
        assertEquals(before, db.withSession { it.schemaEvidence() })
        val optional = target.getValue("Example").properties.getValue("optional")
        assertFalse(optional.mandatory)
        assertFalse(optional.notNull)
        assertEquals(EngineType.LONG, target.getValue("Example").properties.getValue("differentType").mapping.type)
        db.withSession { session ->
            assertTrue(session.schema.getClass("Example").getProperty("optional").isNotNull)
            assertNull(session.schema.getClass("MissingClass"))
            assertNull(session.schema.getClass("Example").getProperty("localEntityId"))
        }
        // Align the intentional model type mismatch before ordinary application.
        m.registeredEntityMetaDatas.single { it.type == "Example" }.let {
            (it as EntityMetaDataImpl).property("differentType", "integer")
        }
        m.prepare()
        db.withSession { session ->
            assertNotNull(session.schema.getClass("MissingClass"))
            val example = session.schema.getClass("Example")
            assertNotNull(example.getProperty("localEntityId"))
            assertTrue(example.getProperty("missing").isMandatory)
            assertTrue(example.getProperty("missing").isNotNull)
            assertNotNull(session.metadata.sequenceLibrary.getSequence(localEntityIdSequenceName("Example")))
        }
    }

    /** Test-only engine evidence, independent of target mapping and schema application. */
    private fun DatabaseSessionEmbedded.schemaEvidence(): List<String> = buildList {
        for (c in schema.classes.sortedBy { it.name }) {
            add("class:${c.name}:${c.superClassesNames.sorted()}")
            for (p in c.declaredProperties.sortedBy { it.name }) {
                add("property:${c.name}:${p.name}:${p.type}:${p.linkedType}:${p.isMandatory}:${p.isNotNull}")
            }
            for (i in (c as SchemaClassInternal).indexesInternal.sortedBy { it.name }) {
                add("index:${i.name}:${i.type}:${i.definition.properties}:${i.definition.types.toList()}")
            }
        }
        if (schema.getClass("Historical") != null) {
            withTx { session ->
                session.activeTransaction.query("select from Historical").use { rows ->
                    add("data:" + rows.vertexStream().map { it.getProperty<String>("value") }.sorted().toList())
                }
            }
        }
        for (name in metadata.sequenceLibrary.sequenceNames.sorted()) {
            val sequence = metadata.sequenceLibrary.getSequence(name)!!
            add("sequence:$name:${sequence.sequenceType}:${sequence.current(this@schemaEvidence)}")
        }
    }
}
