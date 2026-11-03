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
    fun `ancestor and interface indexes count without collapsing child-only contexts`() {
        fun EntityMetaDataImpl.parentProperties() {
            property("inherited", "string")
            property("childOnly", "string")
            property("modelRequired", "string", required = true)
        }
        val m = model {
            entity("Parent") {
                parentProperties()
                index("inherited")
                index(IndexedField("linkWithoutSimpleProperty", false))
            }
            entity("Interface") {
                property("interfaceField", "string")
                index("interfaceField")
            }
            // DNQMetaDataUtil uses getAllProperties for both properties and requirements.
            // The test entity helper does not copy inherited metadata.
            entity("Child", "Parent") {
                parentProperties()
                property("interfaceField", "string")
                setInterfaces(listOf("Interface"))
            }
            entity("IndexedChild", "Parent") {
                parentProperties()
                index("childOnly")
            }
        }
        val target = m.youTrackDbSchemaTarget()
        m.prepare()
        assertTrue(m.getEntityMetaData("Child")!!.ownIndexes.isEmpty())
        assertEquals(setOf("inherited", "interfaceField"), m.getEntityMetaData("Child")!!.indexes
            .flatMap { it.fields }.filter { it.isProperty }.map { it.name }.toSet())
        fun flags(type: String, property: String, required: Boolean) {
            val p = target.getValue(type).properties.getValue(property)
            assertEquals(required, p.mandatory, "$type.$property mandatory")
            assertEquals(required, p.notNull, "$type.$property notNull")
        }
        flags("Parent", "inherited", true)
        flags("Child", "inherited", true)
        flags("Child", "interfaceField", true)
        flags("Child", "modelRequired", true)
        flags("Parent", "childOnly", false)
        flags("Child", "childOnly", false)
        flags("IndexedChild", "childOnly", true)
        assertFalse("linkWithoutSimpleProperty" in target.getValue("Parent").properties)
    }

    @Test
    fun `interface requirements and index owner expansion agree with prepared runtime`() {
        val fields = listOf("interfaceRequired", "transitive", "interfaceParent", "direct", "bridge")
        val m = model {
            entity("Transitive") { index("transitive") }
            entity("InterfaceParent") { index("interfaceParent") }
            entity("Interface", "InterfaceParent") {
                setInterfaces(listOf("Transitive"))
                requiredProperties = setOf("interfaceRequired")
            }
            entity("Direct") { index("direct") }
            entity("Bridge") {
                setInterfaces(listOf("Transitive"))
                index("bridge")
            }
            entity("Child") {
                setInterfaces(listOf("Interface", "Direct"))
                fields.forEach { property(it, "string") }
            }
            entity("Expanded") {
                setInterfaces(listOf("Bridge"))
                fields.forEach { property(it, "string") }
            }
            entity("ParentWithInterface") { setInterfaces(listOf("Direct")) }
            entity("ChildOfParent", "ParentWithInterface") {
                fields.forEach { property(it, "string") }
            }
        }
        val target = m.youTrackDbSchemaTarget()
        // An ordinary non-YTDB model supplies the independent runtime oracle.
        m.prepare()
        for ((type, expected) in mapOf(
            "Child" to setOf("direct"),
            "Expanded" to setOf("bridge", "transitive"),
            "ChildOfParent" to emptySet()
        )) {
            val runtime = m.getEntityMetaData(type)!!
            val required = runtime.requiredProperties + runtime.indexes.flatMap { it.fields }
                .filter { it.isProperty }.map { it.name }
            assertEquals(expected, required, "$type runtime requirements")
            for (field in fields) {
                val property = target.getValue(type).properties.getValue(field)
                assertEquals(field in required, property.mandatory, "$type.$field mandatory")
                assertEquals(field in required, property.notNull, "$type.$field notNull")
            }
        }
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
        for (name in metadata.sequenceLibrary.sequenceNames.sorted()) {
            val sequence = metadata.sequenceLibrary.getSequence(name)!!
            add("sequence:$name:${sequence.sequenceType}:${sequence.current(this@schemaEvidence)}")
        }
    }
}
