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
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.entitystore.youtrackdb.withTx
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.*
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType

class YouTrackDbStoredSchemaTest {
    @Rule @JvmField
    val db = InMemoryYouTrackDB(initializeIssueSchema = false, autoInitializeSchemaBuddy = false)

    @Test
    fun `fresh exact engine roster and unsupported engine-like names retain classifications`() {
        val fresh = db.withSession { it.youTrackDbStoredSchema() }
        assertEquals(setOf("O", "V", "E", "OIdentity", "OUser", "ORole",
            "OFunction", "OSchedule", "OSequence", "OSecurityPolicy"), fresh.classes.keys)
        for (c in fresh.classes.values) {
            assertKind(StoredSchemaKind.INFRASTRUCTURE, c.classification)
            c.declaredProperties.values.forEach { assertKind(StoredSchemaKind.INFRASTRUCTURE, it.classification) }
        }
        db.withSession { s ->
            for (name in listOf("ORestricted", "OTriggered")) {
                s.schema.createClass(name).createProperty("payload", EngineType.STRING)
            }
        }
        val evidence = db.withSession { it.youTrackDbStoredSchema() }
        for (name in listOf("ORestricted", "OTriggered")) {
            val c = evidence.classes.getValue(name)
            assertKind(StoredSchemaKind.APPLICATION, c.classification)
            assertEquals(setOf("payload"), c.declaredProperties.keys)
            assertEquals(name, c.declaredProperties.getValue("payload").owner)
            assertKind(StoredSchemaKind.APPLICATION, c.effectiveProperties.getValue("payload").classification)
        }
    }

    @Test
    fun `bare base-edge adjacency resolves both directions and retains counterexamples`() {
        assertEquals("out_", Vertex.getEdgeLinkFieldName(Direction.OUT, "E"))
        assertEquals("in_", Vertex.getEdgeLinkFieldName(Direction.IN, "E"))
        db.withSession { s ->
            val a = s.createVertexClass("BaseAdjacency")
            a.createProperty("out_", EngineType.LINKBAG)
            a.createProperty("in_", EngineType.LINKBAG, s.schema.getClass("E"))
            val noncanonical = s.createVertexClass("NoncanonicalAdjacency")
            noncanonical.createProperty("out_E", EngineType.LINKBAG)
            noncanonical.createProperty("in_E", EngineType.LINKBAG, s.schema.getClass("E"))
            val wrong = s.createVertexClass("WrongType")
            wrong.createProperty("out_", EngineType.STRING)
            wrong.createProperty("in_", EngineType.LONG)
            val conflict = s.createVertexClass("Conflict")
            conflict.createProperty("out_", EngineType.LINKBAG, s.schema.getClass("V"))
            conflict.createProperty("in_", EngineType.LINKBAG, s.schema.getClass("V"))
        }
        val evidence = db.withSession { it.youTrackDbStoredSchema() }
        for ((owner, kind) in listOf("BaseAdjacency" to StoredSchemaKind.INFRASTRUCTURE,
            "WrongType" to StoredSchemaKind.APPLICATION, "Conflict" to StoredSchemaKind.UNRESOLVED)) {
            val props = evidence.classes.getValue(owner).declaredProperties
            assertEquals(setOf("out_", "in_"), props.keys)
            props.values.forEach { assertKind(kind, it.classification) }
        }
        val noncanonical = evidence.classes.getValue("NoncanonicalAdjacency")
        assertEquals(setOf("out_E", "in_E"), noncanonical.declaredProperties.keys)
        for ((name, linkedClass) in listOf("out_E" to null, "in_E" to "E")) {
            val p = noncanonical.declaredProperties.getValue(name)
            assertEquals("NoncanonicalAdjacency", p.owner)
            assertEquals(EngineType.LINKBAG, p.type)
            assertEquals(linkedClass, p.linkedClass)
            assertKind(StoredSchemaKind.UNRESOLVED, p.classification)
            assertEquals(p, noncanonical.effectiveProperties.getValue(name))
        }
    }

    @Test
    fun `literal link names agree with writer naming helpers`() {
        assertEquals("owner_link", YTDBVertexEntity.edgeClassName("owner"))
        assertEquals("owner_targetEntityId", YTDBVertexEntity.linkTargetEntityIdPropertyName("owner"))
        assertEquals("Country_Region_link", YTDBVertexEntity.edgeClassName("Country/Region"))
        assertEquals("Country/Region_targetEntityId",
            YTDBVertexEntity.linkTargetEntityIdPropertyName("Country/Region"))
    }

    @Test
    fun `literal declarations owners linked types and effective inheritance are detached`() {
        db.withSession { s ->
            val base = s.createVertexClass("Base")
            base.createProperty("shared", EngineType.STRING).setMandatory(true)
            base.createProperty("numbers", EngineType.EMBEDDEDSET, EngineType.INTEGER).setNotNull(true)
            val child = s.schema.createClass("Child", base)
            child.createProperty("own", EngineType.LINK, base)
            s.createVertexClass("Unrelated").createProperty("shared", EngineType.LONG)
        }
        val before = db.withSession { it.youTrackDbStoredSchema() }
        val base = before.classes.getValue("Base")
        val child = before.classes.getValue("Child")
        assertEquals(listOf("Base"), child.superClasses)
        assertTrue(setOf("Base", "V").all { it in child.ancestors })
        assertEquals(setOf("own"), child.declaredProperties.keys)
        assertEquals("Base", child.effectiveProperties.getValue("shared").owner)
        assertEquals(EngineType.STRING, base.declaredProperties.getValue("shared").type)
        assertTrue(base.declaredProperties.getValue("shared").mandatory)
        assertFalse(base.declaredProperties.getValue("shared").notNull)
        val numbers = base.declaredProperties.getValue("numbers")
        assertEquals(EngineType.EMBEDDEDSET, numbers.type)
        assertEquals(EngineType.INTEGER, numbers.linkedType)
        assertTrue(numbers.notNull)
        assertFalse(numbers.mandatory)
        assertEquals("Base", child.declaredProperties.getValue("own").linkedClass)
        val unrelated = before.classes.getValue("Unrelated").declaredProperties.getValue("shared")
        assertEquals("Unrelated", unrelated.owner)
        assertEquals(EngineType.LONG, unrelated.type)
        db.withSession { s ->
            s.schema.getClass("Base").createProperty("later", EngineType.BOOLEAN)
            s.schema.getClass("Base").getProperty("shared").setNotNull(true)
        }
        val fresh = db.withSession { it.youTrackDbStoredSchema() }
        assertFalse("later" in base.declaredProperties)
        assertFalse(base.declaredProperties.getValue("shared").notNull)
        assertTrue(fresh.classes.getValue("Base").declaredProperties.getValue("shared").notNull)
        assertEquals(EngineType.BOOLEAN, fresh.classes.getValue("Child").effectiveProperties.getValue("later").type)
        assertFailsWith<UnsupportedOperationException> { (before.classes as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (child.superClasses as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (child.ancestors as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (base.declaredProperties as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (child.effectiveProperties as MutableMap).clear() }
    }

    @Test
    fun `child declarations shadow parent with actual owner`() {
        db.withSession { s ->
            val base = s.createVertexClass("Parent")
            base.createProperty("same", EngineType.STRING)
            s.schema.createClass("Child", base).createProperty("same", EngineType.STRING).setNotNull(true)
        }
        val evidence = db.withSession { it.youTrackDbStoredSchema() }
        val parent = evidence.classes.getValue("Parent").declaredProperties.getValue("same")
        val child = evidence.classes.getValue("Child")
        assertEquals("Parent", parent.owner)
        assertFalse(parent.notNull)
        assertEquals("Child", child.declaredProperties.getValue("same").owner)
        assertEquals(child.declaredProperties.getValue("same"), child.effectiveProperties.getValue("same"))
        assertTrue(child.effectiveProperties.getValue("same").notNull)
    }

    @Test
    fun `multiple parents preserve engine lookup order rather than sorted overwrite`() {
        db.withSession { s ->
            val first = s.schema.createClass("ZParent")
            val second = s.schema.createClass("AParent")
            first.createProperty("shared", EngineType.STRING).setNotNull(true)
            second.createProperty("shared", EngineType.STRING)
            s.schema.createClass("Multiple", first, second)
        }
        val multiple = db.withSession { it.youTrackDbStoredSchema() }.classes.getValue("Multiple")
        assertEquals(listOf("ZParent", "AParent"), multiple.superClasses)
        assertEquals("ZParent", multiple.effectiveProperties.getValue("shared").owner)
        assertTrue(multiple.effectiveProperties.getValue("shared").notNull)
    }

    @Test
    fun `normal and indexed links include helpers without outgoing adjacency`() {
        db.withSession { s ->
            val a = s.createVertexClass("A")
            val b = s.createVertexClass("B")
            s.createEdgeClass("owner_link").createProperty("payload", EngineType.STRING)
            a.createProperty("out_owner_link", EngineType.LINKBAG)
            b.createProperty("in_owner_link", EngineType.LINKBAG)
            a.createProperty("owner_targetEntityId", EngineType.LINKBAG)
            s.createEdgeClass("indexOnly_link")
            a.createProperty("indexOnly_targetEntityId", EngineType.LINKBAG)
            a.createIndex("literalIndexedLink", "NOTUNIQUE", "indexOnly_targetEntityId")
            s.createEdgeClass("NoAdjacency")
        }
        val evidence = db.withSession { it.youTrackDbStoredSchema() }
        for (name in listOf("V", "E", "owner_link", "indexOnly_link", "NoAdjacency")) {
            assertKind(StoredSchemaKind.INFRASTRUCTURE, evidence.classes.getValue(name).classification)
        }
        for ((owner, name) in listOf("A" to "out_owner_link", "B" to "in_owner_link",
            "A" to "owner_targetEntityId", "A" to "indexOnly_targetEntityId")) {
            val p = evidence.classes.getValue(owner).declaredProperties.getValue(name)
            assertKind(StoredSchemaKind.INFRASTRUCTURE, p.classification)
            assertNull(p.linkedClass)
            assertEquals(EngineType.LINKBAG, p.type)
        }
        assertFalse("out_indexOnly_link" in evidence.classes.getValue("A").effectiveProperties)
        assertKind(StoredSchemaKind.APPLICATION,
            evidence.classes.getValue("owner_link").declaredProperties.getValue("payload").classification)
    }

    @Test
    fun `exact pinned engine endpoints are declared not inferred from direction constants`() {
        assertEquals("out_owner_link", Vertex.getEdgeLinkFieldName(Direction.OUT, "owner_link"))
        assertEquals("in_owner_link", Vertex.getEdgeLinkFieldName(Direction.IN, "owner_link"))
        db.withSession { s ->
            s.createEdgeClass("CreatedEdge")
            s.schema.createClass("BareEdge", s.schema.getClass("E"))
            s.schema.createClass("InheritedEdge", s.schema.getClass("CreatedEdge"))
        }
        val evidence = db.withSession { it.youTrackDbStoredSchema() }
        assertFalse("in" in evidence.classes.getValue("E").effectiveProperties)
        assertFalse("out" in evidence.classes.getValue("E").effectiveProperties)
        val created = evidence.classes.getValue("CreatedEdge")
        assertEquals(setOf("in", "out"), created.declaredProperties.keys)
        for (name in listOf("in", "out")) {
            val p = created.declaredProperties.getValue(name)
            assertEquals(EngineType.LINK, p.type)
            assertEquals("CreatedEdge", p.owner)
            assertKind(StoredSchemaKind.INFRASTRUCTURE, p.classification)
            assertEquals(p, evidence.classes.getValue("InheritedEdge").effectiveProperties.getValue(name))
        }
        assertTrue(evidence.classes.getValue("InheritedEdge").declaredProperties.isEmpty())
        assertTrue(evidence.classes.getValue("BareEdge").effectiveProperties.isEmpty())
        assertKind(StoredSchemaKind.INFRASTRUCTURE, evidence.classes.getValue("BareEdge").classification)
    }

    @Test
    fun `name only wrong type missing and conflicting structures retain evidence`() {
        db.withSession { s ->
            val a = s.createVertexClass("Invoice_link")
            s.schema.createClass("Ordinary")
            s.schema.createClass("OApplication")
            s.schema.createClass("ApplicationChild", s.schema.getClass("OUser"))
            s.createEdgeClass("real_link")
            a.createProperty("owner_targetEntityId", EngineType.STRING)
            a.createProperty("out_real_link", EngineType.STRING)
            a.createProperty("out_missing_link", EngineType.LINKBAG)
            a.createProperty("orphan_targetEntityId", EngineType.LINKBAG)
            a.createProperty("in_real_link", EngineType.LINKBAG, s.schema.getClass("Invoice_link"))
            a.createProperty("suffix_blob_size", EngineType.LONG)
        }
        val evidence = db.withSession { it.youTrackDbStoredSchema() }
        for (name in listOf("Invoice_link", "Ordinary", "OApplication", "ApplicationChild")) {
            assertKind(StoredSchemaKind.APPLICATION, evidence.classes.getValue(name).classification)
        }
        val props = evidence.classes.getValue("Invoice_link").declaredProperties
        assertEquals(6, props.size)
        for (name in listOf("owner_targetEntityId", "out_real_link", "suffix_blob_size")) {
            assertKind(StoredSchemaKind.APPLICATION, props.getValue(name).classification)
        }
        for (name in listOf("out_missing_link", "orphan_targetEntityId", "in_real_link")) {
            assertKind(StoredSchemaKind.UNRESOLVED, props.getValue(name).classification)
        }
    }

    @Test
    fun `raw slash helper and sanitized edge names distinguish collisions`() {
        db.withSession { s ->
            val a = s.createVertexClass("A")
            s.createEdgeClass("Country_Region_link")
            a.createProperty("Country/Region_targetEntityId", EngineType.LINKBAG)
            a.createProperty("out_Country_Region_link", EngineType.LINKBAG)
        }
        val first = db.withSession { it.youTrackDbStoredSchema() }
        assertKind(StoredSchemaKind.INFRASTRUCTURE,
            first.classes.getValue("A").declaredProperties.getValue("Country/Region_targetEntityId").classification)
        db.withSession { s ->
            s.schema.getClass("A").createProperty("Country_Region_targetEntityId", EngineType.LINKBAG)
        }
        val ambiguous = db.withSession { it.youTrackDbStoredSchema() }.classes.getValue("A")
        for (name in listOf("Country/Region_targetEntityId", "Country_Region_targetEntityId")) {
            val classification = ambiguous.declaredProperties.getValue(name).classification
            assertKind(StoredSchemaKind.UNRESOLVED, classification)
            assertTrue(classification.reason.contains("Ambiguous"))
        }
        assertKind(StoredSchemaKind.INFRASTRUCTURE,
            ambiguous.declaredProperties.getValue("out_Country_Region_link").classification)
    }

    @Test
    fun `raw link prefix does not choose adjacency and dual structures stay unresolved`() {
        db.withSession { s ->
            val a = s.createVertexClass("A")
            s.createEdgeClass("out_owner_link")
            a.createProperty("out_owner_targetEntityId", EngineType.LINKBAG)
        }
        val first = db.withSession { it.youTrackDbStoredSchema() }
        assertKind(StoredSchemaKind.INFRASTRUCTURE,
            first.classes.getValue("A").declaredProperties.getValue("out_owner_targetEntityId").classification)
        db.withSession { it.createEdgeClass("owner_targetEntityId") }
        val ambiguous = db.withSession { it.youTrackDbStoredSchema() }
        assertKind(StoredSchemaKind.UNRESOLVED,
            ambiguous.classes.getValue("A").declaredProperties.getValue("out_owner_targetEntityId").classification)
    }

    @Test
    fun `snapshot enumeration and property failures propagate unchanged`() {
        val failure = IllegalStateException("sentinel")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            readYouTrackDbStoredSchema { throw failure }
        })
        val brokenSchema = proxy<Schema> { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> {
            readYouTrackDbStoredSchema { brokenSchema }
        })
        val brokenProperty = proxy<SchemaProperty> { method ->
            if (method == "getName") "broken" else throw failure
        }
        val brokenClass = proxy<SchemaClass> { method ->
            when (method) {
                "getName" -> "Broken"
                "getSuperClassesNames", "getAllSuperClasses" -> emptyList<SchemaClass>()
                "getDeclaredProperties" -> listOf(brokenProperty)
                else -> error(method)
            }
        }
        val schema = proxy<Schema> { listOf(brokenClass) }
        assertSame(failure, assertFailsWith<IllegalStateException> {
            readYouTrackDbStoredSchema { schema }
        })
    }

    @Test
    fun `standalone repeated reads leave schema indexes sequences flags and data unchanged`() {
        db.withSession { s ->
            val c = s.createVertexClass("Historical")
            c.createProperty("value", EngineType.STRING).setMandatory(true).setNotNull(true)
            c.createProperty("extra", EngineType.INTEGER)
            c.createIndex("retainedIndex", "NOTUNIQUE", "value")
            s.metadata.sequenceLibrary.createSequence("retainedSequence", DBSequence.SEQUENCE_TYPE.ORDERED,
                DBSequence.CreateParams().setStart(41))
            s.withTx { it.newVertex("Historical").setProperty("value", "retained") }
        }
        val before = db.withSession { it.independentEvidence() }
        repeat(3) {
            val read = db.withSession { it.youTrackDbStoredSchema() }
            val props = read.classes.getValue("Historical").declaredProperties
            assertEquals(setOf("value", "extra"), props.keys)
            assertTrue(props.getValue("value").mandatory)
            assertTrue(props.getValue("value").notNull)
            assertEquals(EngineType.INTEGER, props.getValue("extra").type)
        }
        assertEquals(before, db.withSession { it.independentEvidence() })
        assertTrue(before.any { it == "data:[retained]" })
        assertTrue(before.any { it == "sequence:RETAINEDSEQUENCE:ORDERED:41" })
        assertTrue(before.any { it.startsWith("index:retainedIndex:NOTUNIQUE:") })
    }

    private fun assertKind(expected: StoredSchemaKind, actual: StoredSchemaClassification) {
        assertEquals(expected, actual.kind, actual.reason)
        assertTrue(actual.reason.isNotBlank())
    }

    private inline fun <reified T> proxy(crossinline invoke: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            invoke(method.name)
        } as T

    private fun DatabaseSessionEmbedded.independentEvidence(): List<String> = buildList {
        for (c in schema.classes.sortedBy { it.name }) {
            add("class:${c.name}:${c.superClassesNames.sorted()}")
            for (p in c.declaredProperties.sortedBy { it.name }) {
                add("property:${p.ownerClass.name}:${p.name}:${p.type}:${p.linkedType}:${p.linkedClass?.name}:${p.isMandatory}:${p.isNotNull}")
            }
            for (i in (c as SchemaClassInternal).indexesInternal.sortedBy { it.name }) {
                add("index:${i.name}:${i.type}:${i.definition.properties}:${i.definition.types.toList()}")
            }
        }
        for (name in metadata.sequenceLibrary.sequenceNames.sorted()) {
            val seq = metadata.sequenceLibrary.getSequence(name)!!
            add("sequence:$name:${seq.sequenceType}:${seq.current(this@independentEvidence)}")
        }
        withTx { s ->
            s.activeTransaction.query("select from Historical").use { rows ->
                add("data:" + rows.vertexStream().map { it.getProperty<String>("value") }.sorted().toList())
            }
        }
    }
}
