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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.youtrackdb.YTDBSchemaBuddy
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity.Companion.localEntityIdSequenceName
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.createClassIdSequenceIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.setClassIdIfAbsent
import jetbrains.exodus.entitystore.youtrackdb.withTx
import org.junit.Rule
import org.junit.Test
import kotlin.test.*
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType
import jetbrains.exodus.query.metadata.YouTrackDbSchemaDifference.*

class YouTrackDbSchemaComparisonTest {
    @Rule @JvmField
    val db = InMemoryYouTrackDB(false, false)

    private fun SchemaClass.identity() {
        createProperty("localEntityId", EngineType.LONG).setMandatory(true).setNotNull(true)
    }

    private fun snapshot() = db.withSession { it.youTrackDbStoredSchema() }
    private fun compare(model: ModelMetaDataImpl) = db.withSession { model.compareYouTrackDbSchema(it) }
    private fun YouTrackDbSchemaComparison.values() = differences.filterIsInstance<PropertyValue>()
    private fun YouTrackDbSchemaComparison.extras() = differences.filterIsInstance<ActualOnlyProperty>()
        .map { it.className to it.actual.name }.toSet()

    private fun assertClean(result: YouTrackDbSchemaComparison) {
        assertTrue(result.differences.isEmpty(), result.differences.toString())
        assertTrue(result.flagConflicts.isEmpty(), result.flagConflicts.toString())
        assertTrue(result.unresolved.isEmpty(), result.unresolved.toString())
    }

    @Test
    fun `literal scalar container TEXT BLOB and identity storage agree without logical kind`() {
        val scalars = listOf(
            "boolean" to EngineType.BOOLEAN, "string" to EngineType.STRING,
            "byte" to EngineType.BYTE, "short" to EngineType.SHORT,
            "int" to EngineType.INTEGER, "integer" to EngineType.INTEGER,
            "long" to EngineType.LONG, "datetime" to EngineType.LONG,
            "float" to EngineType.FLOAT, "double" to EngineType.DOUBLE
        )
        db.withSession { s ->
            val c = s.createVertexClass("Example")
            c.identity()
            for ((i, scalar) in scalars.withIndex()) {
                c.createProperty("scalar$i", scalar.second)
                c.createProperty("set$i", EngineType.EMBEDDEDSET, scalar.second)
            }
            c.createProperty("required", EngineType.INTEGER).setMandatory(true).setNotNull(true)
            c.createProperty("text", EngineType.LINK)
            c.createProperty("blob", EngineType.LINK).setMandatory(true).setNotNull(true)
        }
        val m = model {
            entity("Example") {
                for ((i, scalar) in scalars.withIndex()) {
                    property("scalar$i", scalar.first)
                    setProperty("set$i", scalar.first)
                }
                property("required", "integer", required = true)
                stringBlobProperty("text")
                blobProperty("blob")
                requiredProperties = requiredProperties + "blob"
            }
        }
        val target = m.youTrackDbSchemaTarget()
        val actual = snapshot()
        val saved = actual.copy(classes = actual.classes.toMap())
        repeat(3) { assertClean(compareYouTrackDbSchema(target, actual)) }
        assertEquals(saved, actual)
        assertEquals(target, m.youTrackDbSchemaTarget())
        assertSame(actual, compareYouTrackDbSchema(target, actual).actual)
    }

    @Test
    fun `missing names native and linked element differences are independent`() {
        db.withSession { s ->
            val c = s.createVertexClass("Example")
            c.identity()
            c.createProperty("value", EngineType.INTEGER)
            c.createProperty("text", EngineType.STRING)
            c.createProperty("dates", EngineType.EMBEDDEDSET, EngineType.STRING)
            c.createProperty("noElement", EngineType.EMBEDDEDSET)
            c.createProperty("extra", EngineType.INTEGER)
            c.createProperty("owner_targetEntityId", EngineType.STRING)
            c.createProperty("out_owner_link", EngineType.STRING)
            s.createVertexClass("Legacy").createProperty("payload", EngineType.STRING)
        }
        val result = compare(model {
            entity("Example") {
                property("value", "string")
                stringBlobProperty("text")
                setProperty("dates", "datetime")
                setProperty("noElement", "datetime")
                property("missing", "string")
            }
            entity("MissingClass") { property("alsoMissing", "string") }
        })
        assertEquals(listOf("MissingClass"), result.differences.filterIsInstance<MissingClass>().map { it.desired.name })
        assertEquals(listOf("Example" to "missing"),
            result.differences.filterIsInstance<MissingProperty>().map { it.className to it.desired.mapping.name })
        assertEquals(setOf(
            Triple("value", SchemaPropertyField.TYPE, EngineType.STRING to EngineType.INTEGER),
            Triple("text", SchemaPropertyField.TYPE, EngineType.LINK to EngineType.STRING),
            Triple("dates", SchemaPropertyField.LINKED_TYPE, EngineType.LONG to EngineType.STRING),
            Triple("noElement", SchemaPropertyField.LINKED_TYPE, EngineType.LONG to null)
        ), result.values().map { Triple(it.actual.name, it.field, it.expected to it.observed) }.toSet())
        assertEquals(listOf("Legacy"), result.differences.filterIsInstance<ActualOnlyClass>().map { it.actual.name })
        assertEquals(setOf("Example" to "extra", "Example" to "owner_targetEntityId",
            "Example" to "out_owner_link", "Legacy" to "payload"), result.extras())
        assertTrue(result.flagConflicts.isEmpty())
        assertTrue(result.unresolved.isEmpty())
        assertEquals(11, result.differences.size)
    }

    @Test
    fun `mandatory and historical not-null differ separately in both directions`() {
        // Each case has a distinct name so either flag being ignored fails independently.
        val cases = listOf(
            Triple("optionalMandatory", false, true to false),
            Triple("optionalNotNull", false, false to true),
            Triple("optionalBoth", false, true to true),
            Triple("requiredMandatory", true, false to true),
            Triple("requiredNotNull", true, true to false),
            Triple("requiredBoth", true, false to false)
        )
        db.withSession { s ->
            val c = s.createVertexClass("Example")
            c.identity()
            for ((name, _, flags) in cases) {
                c.createProperty(name, EngineType.STRING).setMandatory(flags.first).setNotNull(flags.second)
            }
        }
        val result = compare(model {
            entity("Example") { for ((name, required, _) in cases) property(name, "string", required) }
        })
        val expected = cases.flatMap { (name, required, flags) ->
            buildList {
                if (required != flags.first) add(listOf(name, SchemaPropertyField.MANDATORY, required, flags.first))
                if (required != flags.second) add(listOf(name, SchemaPropertyField.NOT_NULL, required, flags.second))
            }
        }.toSet()
        assertEquals(expected, result.values().map { listOf(it.actual.name, it.field, it.expected, it.observed) }.toSet())
        assertEquals(8, result.differences.size)
        assertTrue(result.flagConflicts.isEmpty())
    }

    @Test
    fun `implicit identity missing wrong native type and flags are compared`() {
        db.withSession { s ->
            s.createVertexClass("Missing")
            s.createVertexClass("Wrong").createProperty("localEntityId", EngineType.STRING)
                .setMandatory(true).setNotNull(true)
            s.createVertexClass("Flags").createProperty("localEntityId", EngineType.LONG)
        }
        val result = compare(model { entity("Missing"); entity("Wrong"); entity("Flags") })
        val missing = result.differences.filterIsInstance<MissingProperty>().single()
        assertEquals("Missing", missing.className)
        assertEquals("localEntityId", missing.desired.mapping.name)
        assertEquals(setOf(
            listOf("Wrong", SchemaPropertyField.TYPE, EngineType.LONG, EngineType.STRING),
            listOf("Flags", SchemaPropertyField.MANDATORY, true, false),
            listOf("Flags", SchemaPropertyField.NOT_NULL, true, false)
        ), result.values().map { listOf(it.className, it.field, it.expected, it.observed) }.toSet())
        assertEquals(4, result.differences.size)
    }

    private fun helperFixture() {
        db.withSession { s ->
            val a = s.createVertexClass("A")
            val b = s.createVertexClass("B")
            a.identity()
            b.identity()
            s.createEdgeClass("owner_link").createProperty("payload", EngineType.STRING)
            a.createProperty("out_owner_link", EngineType.LINKBAG)
            b.createProperty("in_owner_link", EngineType.LINKBAG)
            a.createProperty("owner_targetEntityId", EngineType.LINKBAG)
            s.createEdgeClass("indexOnly_link")
            a.createProperty("indexOnly_targetEntityId", EngineType.LINKBAG)
            a.createProperty("out_missing_link", EngineType.LINKBAG)
            b.createProperty("out_owner_link", EngineType.LINKBAG, s.schema.getClass("V"))
        }
    }

    @Test
    fun `complete helpers disappear only from differences while uncertain and edge payload remain`() {
        helperFixture()
        val result = compare(model { entity("A"); entity("B") })
        assertEquals(setOf("owner_link" to "payload"), result.extras())
        assertEquals(1, result.differences.size)
        assertEquals(setOf("A" to "out_missing_link", "B" to "out_owner_link"),
            result.unresolved.map { it.className to it.actual.name }.toSet())
        for (u in result.unresolved) {
            assertEquals(StoredSchemaKind.UNRESOLVED, u.actual.classification.kind)
            assertTrue(u.actual.classification.reason.isNotBlank())
            assertSame(result.actual.classes.getValue(u.className).effectiveProperties.getValue(u.actual.name), u.actual)
        }
        assertEquals(EngineType.STRING,
            result.actual.classes.getValue("owner_link").declaredProperties.getValue("payload").type)
        for ((owner, name) in listOf("A" to "out_owner_link", "A" to "owner_targetEntityId",
            "A" to "indexOnly_targetEntityId", "B" to "in_owner_link")) {
            assertEquals(StoredSchemaKind.INFRASTRUCTURE,
                result.actual.classes.getValue(owner).effectiveProperties.getValue(name).classification.kind)
        }
        assertTrue(result.flagConflicts.isEmpty())
    }

    @Test
    fun `desired class and property names precede infrastructure and uncertainty exclusion`() {
        helperFixture()
        val result = compare(model {
            entity("A") {
                property("out_owner_link", "string")
                property("owner_targetEntityId", "string")
                property("out_missing_link", "string")
            }
            entity("B")
            entity("owner_link") { property("payload", "string") }
        })
        assertEquals(setOf("out_owner_link", "owner_targetEntityId", "out_missing_link"),
            result.values().map { it.actual.name }.toSet())
        for (v in result.values()) {
            assertEquals("A", v.className)
            assertEquals(SchemaPropertyField.TYPE, v.field)
            assertEquals(EngineType.STRING, v.expected)
            assertEquals(EngineType.LINKBAG, v.observed)
        }
        val missing = result.differences.filterIsInstance<MissingProperty>().single()
        assertEquals("owner_link", missing.className)
        assertEquals("localEntityId", missing.desired.mapping.name)
        assertEquals(4, result.differences.size)
        assertEquals(listOf("B" to "out_owner_link"), result.unresolved.map { it.className to it.actual.name })
    }

    private fun inheritedModel(
        uniform: Boolean = false,
        siblingIndex: Boolean = false,
        other: Boolean = false
    ) = model {
        for ((name, parent) in listOf("Base" to null, "Middle" to "Base",
            "Leaf" to "Middle", "Sibling" to "Base")) {
            entity(name, parent) {
                // Raw test metadata must explicitly populate every inherited model context.
                property("inherited", "string")
                property("childOnly", "string", required = uniform)
                property("modelRequired", "string", required = true)
                property("optional", "string")
                if (name == "Base") index("inherited")
                if (name == "Leaf" || name == "Sibling" && siblingIndex) index("childOnly")
            }
        }
        if (other) entity("Other") { property("childOnly", "string", required = true) }
    }

    private fun inheritedActual(
        flags: Boolean = false,
        wrongType: Boolean = false,
        shadow: Boolean = false,
        other: Boolean = false
    ) {
        db.withSession { s ->
            val base = s.createVertexClass("Base")
            base.identity()
            base.createProperty("inherited", EngineType.STRING).setMandatory(true).setNotNull(true)
            base.createProperty("childOnly", if (wrongType) EngineType.INTEGER else EngineType.STRING)
                .setMandatory(flags).setNotNull(flags)
            base.createProperty("modelRequired", EngineType.STRING).setMandatory(true).setNotNull(true)
            base.createProperty("optional", EngineType.STRING)
            val middle = s.schema.createClass("Middle", base)
            val leaf = s.schema.createClass("Leaf", middle)
            s.schema.createClass("Sibling", base)
            if (shadow) leaf.createProperty("childOnly", EngineType.STRING)
            if (other) {
                val c = s.createVertexClass("Other")
                c.identity()
                c.createProperty("childOnly", EngineType.STRING)
            }
        }
    }

    private fun assertInheritedConflict(
        result: YouTrackDbSchemaComparison,
        requiredContexts: Set<String> = setOf("Leaf")
    ) {
        val conflict = result.flagConflicts.single()
        assertEquals("Base", conflict.owner)
        assertEquals("childOnly", conflict.propertyName)
        assertEquals("Base", conflict.actual.owner)
        assertEquals(setOf("Base", "Middle", "Leaf", "Sibling"), conflict.requirements.map { it.className }.toSet())
        for (requirement in conflict.requirements) {
            assertEquals(requirement.className in requiredContexts, requirement.mandatory)
            assertEquals(requirement.className in requiredContexts, requirement.notNull)
        }
        assertFalse(result.values().any {
            it.actual.name == "childOnly" && it.field in setOf(SchemaPropertyField.MANDATORY, SchemaPropertyField.NOT_NULL)
        })
    }

    @Test
    fun `ancestor index and child-only index yield one actual-owner conflict`() {
        inheritedActual()
        val result = compare(inheritedModel())
        assertInheritedConflict(result)
        assertTrue(result.differences.isEmpty(), result.differences.toString())
        assertTrue(result.unresolved.isEmpty())
    }

    @Test
    fun `historical required flags cannot pick a conflict winner`() {
        inheritedActual(flags = true)
        val result = compare(inheritedModel())
        assertInheritedConflict(result)
        assertTrue(result.flagConflicts.single().actual.mandatory)
        assertTrue(result.flagConflicts.single().actual.notNull)
        assertTrue(result.differences.isEmpty())
    }

    @Test
    fun `two required descendants stay in one conflict group`() {
        inheritedActual()
        val result = compare(inheritedModel(siblingIndex = true))
        assertInheritedConflict(result, setOf("Leaf", "Sibling"))
        assertTrue(result.differences.isEmpty())
    }

    @Test
    fun `uniform requirements agree with actual TT`() {
        inheritedActual(flags = true)
        assertClean(compare(inheritedModel(uniform = true)))
    }

    @Test
    fun `uniform requirements against FF produce ordinary per-context flag differences`() {
        inheritedActual()
        val result = compare(inheritedModel(uniform = true))
        assertTrue(result.flagConflicts.isEmpty())
        assertEquals(setOf("Base", "Middle", "Leaf", "Sibling"), result.values().map { it.className }.toSet())
        assertEquals(8, result.differences.size)
        for (v in result.values()) {
            assertEquals("childOnly", v.actual.name)
            assertEquals("Base", v.actual.owner)
            assertTrue(v.field in setOf(SchemaPropertyField.MANDATORY, SchemaPropertyField.NOT_NULL))
            assertEquals(true, v.expected)
            assertEquals(false, v.observed)
        }
    }

    @Test
    fun `native differences survive inherited flag conflicts`() {
        inheritedActual(wrongType = true)
        val result = compare(inheritedModel())
        assertInheritedConflict(result)
        assertEquals(setOf("Base", "Middle", "Leaf", "Sibling"), result.values().map { it.className }.toSet())
        assertEquals(4, result.differences.size)
        for (v in result.values()) {
            assertEquals(SchemaPropertyField.TYPE, v.field)
            assertEquals(EngineType.STRING, v.expected)
            assertEquals(EngineType.INTEGER, v.observed)
        }
    }

    @Test
    fun `linked element differences also survive inherited flag conflicts`() {
        inheritedActual()
        val actual = snapshot()
        val desired = inheritedModel().youTrackDbSchemaTarget().mapValues { (_, c) ->
            c.copy(properties = c.properties.mapValues { (name, p) ->
                if (name == "childOnly") p.copy(mapping = p.mapping.copy(type = EngineType.EMBEDDEDSET, linkedType = EngineType.LONG))
                else p
            })
        }
        val result = compareYouTrackDbSchema(desired, actual)
        assertInheritedConflict(result)
        assertEquals(8, result.differences.size)
        assertEquals(4, result.values().count { it.field == SchemaPropertyField.LINKED_TYPE && it.expected == EngineType.LONG && it.observed == null })
    }

    @Test
    fun `child shadowing uses its own actual declaring owner`() {
        inheritedActual(shadow = true)
        val result = compare(inheritedModel())
        assertTrue(result.flagConflicts.isEmpty())
        assertEquals(2, result.differences.size)
        for (v in result.values()) {
            assertEquals("Leaf", v.className)
            assertEquals("Leaf", v.actual.owner)
            assertEquals("childOnly", v.actual.name)
            assertEquals(true, v.expected)
            assertEquals(false, v.observed)
        }
        assertEquals(setOf(SchemaPropertyField.MANDATORY, SchemaPropertyField.NOT_NULL), result.values().map { it.field }.toSet())
    }

    @Test
    fun `unrelated same-name declaration remains separate from inherited conflict`() {
        inheritedActual(other = true)
        val result = compare(inheritedModel(other = true))
        val conflict = result.flagConflicts.single()
        assertEquals("Base", conflict.owner)
        assertFalse(conflict.requirements.any { it.className == "Other" })
        assertEquals(2, result.differences.size)
        for (v in result.values()) {
            assertEquals("Other", v.className)
            assertEquals("Other", v.actual.owner)
            assertEquals(true, v.expected)
            assertEquals(false, v.observed)
        }
    }

    @Test
    fun `raw comparison never prepares builds or calls onPrepared and errors propagate`() {
        var allowPrepare = false
        var callbacks = 0
        val guarded = object : ModelMetaDataImpl() {
            override fun prepare(): MutableMap<String, EntityMetaData> {
                check(allowPrepare) { "Comparison invoked prepare" }
                return super.prepare()
            }
            override fun buildModel(build: Runnable) = error("Comparison invoked buildModel")
            override fun onPrepared(entities: MutableCollection<EntityMetaData>) { callbacks++ }
        }
        guarded.entity("Example") { property("value", "string") }
        repeat(3) {
            assertEquals("Example", compare(guarded).differences.filterIsInstance<MissingClass>().single().desired.name)
        }
        assertEquals(0, callbacks)
        val invalid = model { entity("Bad") { property("unsupported", "unsupported") } }
        assertFailsWith<IllegalArgumentException> { compare(invalid) }
        allowPrepare = true
        guarded.prepare()
        assertEquals(1, callbacks)
    }

    @Test
    fun `real unprepared comparison changes no schema flags indexes sequences or data and later prepare applies`() {
        db.withSession { s ->
            val c = s.createVertexClass("Example")
            c.createProperty("optional", EngineType.STRING).setNotNull(true)
            c.createProperty("differentType", EngineType.INTEGER)
            c.createProperty("extra", EngineType.STRING)
            c.createIndex("historical", "NOTUNIQUE", "extra")
            s.createClassIdSequenceIfAbsent()
            val historical = s.createVertexClass("Historical")
            s.setClassIdIfAbsent(historical)
            historical.createProperty("value", EngineType.STRING)
            s.metadata.sequenceLibrary.createSequence("historicalSequence", DBSequence.SEQUENCE_TYPE.ORDERED,
                DBSequence.CreateParams().setStart(41))
            s.withTx { it.newVertex("Historical").setProperty("value", "retained") }
        }
        var initializations = 0
        val counting = object : YTDBSchemaBuddy by db.schemaBuddy {
            override fun initialize(session: DatabaseSessionEmbedded) {
                initializations++
                db.schemaBuddy.initialize(session)
            }
        }
        val m = oModel(db.provider, counting) {
            entity("Example") {
                property("optional", "string")
                property("differentType", "long")
                property("missing", "string", required = true)
            }
            entity("MissingClass")
        }
        val cache = ModelMetaDataImpl::class.java.getDeclaredField("typeToEntityMetaDatas").apply { isAccessible = true }
        assertNull(cache.get(m))
        val before = db.withSession { it.independentEvidence() }
        assertTrue("data:[retained]" in before)
        assertTrue("sequence:HISTORICALSEQUENCE:ORDERED:41" in before)
        assertTrue(before.any { it.startsWith("index:historical:NOTUNIQUE:") })
        repeat(3) {
            val result = compare(m)
            assertEquals(listOf("MissingClass"), result.differences.filterIsInstance<MissingClass>().map { it.desired.name })
            assertEquals(setOf("missing", "localEntityId"),
                result.differences.filterIsInstance<MissingProperty>().map { it.desired.mapping.name }.toSet())
            assertEquals(setOf(
                listOf("optional", SchemaPropertyField.NOT_NULL, false, true),
                listOf("differentType", SchemaPropertyField.TYPE, EngineType.LONG, EngineType.INTEGER)
            ), result.values().map { listOf(it.actual.name, it.field, it.expected, it.observed) }.toSet())
            assertEquals(listOf("Historical"), result.differences.filterIsInstance<ActualOnlyClass>().map { it.actual.name })
            assertEquals(setOf("Example" to "extra", "Historical" to "value"), result.extras())
            assertEquals(8, result.differences.size)
            assertTrue(result.flagConflicts.isEmpty())
            assertTrue(result.unresolved.isEmpty())
            assertNull(cache.get(m))
            assertEquals(0, initializations)
            assertEquals(before, db.withSession { s -> s.independentEvidence() })
        }
        (m.registeredEntityMetaDatas.single { it.type == "Example" } as EntityMetaDataImpl)
            .property("differentType", "integer")
        m.prepare()
        assertNotNull(cache.get(m))
        assertEquals(1, initializations)
        db.withSession { s ->
            assertNotNull(s.schema.getClass("MissingClass"))
            val c = s.schema.getClass("Example")
            assertEquals(EngineType.INTEGER, c.getProperty("differentType").type)
            assertTrue(c.getProperty("missing").isMandatory)
            assertTrue(c.getProperty("missing").isNotNull)
            assertTrue(c.getProperty("localEntityId").isMandatory)
            assertTrue(c.getProperty("localEntityId").isNotNull)
            for (name in listOf("Example", "MissingClass")) {
                assertNotNull(s.metadata.sequenceLibrary.getSequence(localEntityIdSequenceName(name)))
            }
            assertTrue(s.independentEvidence().contains("data:[retained]"))
            assertTrue(s.independentEvidence().contains("sequence:HISTORICALSEQUENCE:ORDERED:41"))
        }
    }

    /** Independent engine enumeration and data query, not the snapshot reader or target mapper. */
    private fun DatabaseSessionEmbedded.independentEvidence(): List<String> = buildList {
        for (c in schema.classes.sortedBy { it.name }) {
            add("class:${c.name}:${c.superClassesNames.sorted()}:${c.allSuperClasses.map { it.name }.sorted()}:${c.getCustom("classId")}")
            for (p in c.declaredProperties.sortedBy { it.name }) {
                add("property:${p.ownerClass.name}:${p.name}:${p.type}:${p.linkedType}:${p.linkedClass?.name}:${p.isMandatory}:${p.isNotNull}")
            }
            for (i in (c as SchemaClassInternal).indexesInternal.sortedBy { it.name }) {
                add("index:${i.name}:${i.type}:${i.definition.properties}:${i.definition.types.toList()}")
            }
        }
        for (name in metadata.sequenceLibrary.sequenceNames.sorted()) {
            val sequence = metadata.sequenceLibrary.getSequence(name)!!
            add("sequence:$name:${sequence.sequenceType}:${sequence.current(this@independentEvidence)}")
        }
        withTx { s ->
            s.activeTransaction.query("select from Historical").use { rows ->
                add("data:" + rows.vertexStream().map { it.getProperty<String>("value") }.sorted().toList())
            }
        }
    }
}
