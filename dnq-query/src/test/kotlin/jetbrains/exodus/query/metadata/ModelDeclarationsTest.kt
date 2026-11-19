package jetbrains.exodus.query.metadata

import org.junit.Test
import kotlin.test.*

class ModelDeclarationsTest {
    private val id = DeclarationPropertyId("P", "title")
    private val length = ConstraintDeclaration(ConstraintKind.LENGTH, "length", ConstraintImplementation.BUILT_IN, 0, 12)
    private val range = ConstraintDeclaration(ConstraintKind.NUMERIC_RANGE, "inRange",
        ConstraintImplementation.BUILT_IN, minValue = -7L, maxValue = 19L)
    private val extension = ExtensionDeclaration("plugin.example", DeclarationPropertyId("Extensions", "related"))
    private fun scalar() = ScalarDeclaration(id, "db_title", PropertyType.PRIMITIVE, "String",
        listOf("String", "Long"), false, DeclarationRequirement.UNIQUE, listOf(length, range),
        CustomTypeDeclaration("custom.money", "bindings.Money"))
    private fun endpoint() = EndpointDeclaration(DeclarationPropertyId("P", "related"), "db_related", "db_related",
        "S", DeclarationPropertyId("S", "back"), "db_back", AssociationEndCardinality._0_1,
        AssociationEndType.UndirectedAssociationEnd, DeclarationDeletePolicy.CASCADE, DeclarationDeletePolicy.CLEAR,
        RegistrationCategory.NATURAL, extension)
    private fun type() = TypeDeclaration("P", true, listOf("Root", "Ancestor"), RegistrationCategory.NATURAL,
        listOf(scalar()), setOf("db_title"), listOf(IndexDeclaration("P",
            listOf(IndexFieldDeclaration("db_title", true), IndexFieldDeclaration("db_related", false)))),
        listOf(endpoint()), listOf(extension), IdentityDeclaration("localEntityId", "LONG"))

    private fun assertScalar(s: ScalarDeclaration) {
        assertEquals("P", s.property.owner)
        assertEquals("title", s.property.kotlinName)
        assertEquals("db_title", s.databaseName)
        assertEquals(PropertyType.PRIMITIVE, s.kind)
        assertEquals("String", s.primitiveTypeName)
        assertEquals(listOf("String", "Long"), s.parameters)
        assertFalse(s.autoIndexed)
        assertEquals(DeclarationRequirement.UNIQUE, s.requirement)
        assertEquals(listOf(
            ConstraintDeclaration(ConstraintKind.LENGTH, "length", ConstraintImplementation.BUILT_IN, 0, 12),
            ConstraintDeclaration(ConstraintKind.NUMERIC_RANGE, "inRange", ConstraintImplementation.BUILT_IN,
                minValue = -7L, maxValue = 19L)), s.constraints)
        assertEquals(0, s.constraints[0].minLength)
        assertEquals(12, s.constraints[0].maxLength)
        assertEquals(-7L, s.constraints[1].minValue)
        assertEquals(19L, s.constraints[1].maxValue)
        assertEquals(CustomTypeDeclaration("custom.money", "bindings.Money"), s.customType)
    }

    private fun assertType(t: TypeDeclaration) {
        assertEquals("P", t.name)
        assertTrue(t.isAbstract)
        assertEquals(listOf("Root", "Ancestor"), t.ancestors)
        assertEquals(RegistrationCategory.NATURAL, t.registrationCategory)
        assertEquals(setOf("db_title"), t.conditionalRequiredCandidates)
        assertEquals(1, t.scalars.size)
        assertScalar(t.scalars.single())
        assertEquals("P", t.indexes.single().owner)
        assertEquals(listOf(IndexFieldDeclaration("db_title", true), IndexFieldDeclaration("db_related", false)),
            t.indexes.single().fields)
        val e = t.endpoints.single()
        assertEquals(DeclarationPropertyId("P", "related"), e.property)
        assertEquals("db_related", e.databaseName)
        assertEquals("db_related", e.databaseNameOverride)
        assertEquals("S", e.oppositeType)
        assertEquals(DeclarationPropertyId("S", "back"), e.oppositeProperty)
        assertEquals("db_back", e.oppositeDatabaseNameOverride)
        assertEquals(AssociationEndCardinality._0_1, e.cardinality)
        assertEquals(AssociationEndType.UndirectedAssociationEnd, e.endType)
        assertEquals(DeclarationDeletePolicy.CASCADE, e.onDelete)
        assertEquals(DeclarationDeletePolicy.CLEAR, e.onTargetDelete)
        assertEquals(RegistrationCategory.NATURAL, e.registrationCategory)
        assertEquals(ExtensionDeclaration("plugin.example", DeclarationPropertyId("Extensions", "related")), e.installedExtension)
        assertEquals(listOf(ExtensionDeclaration("plugin.example", DeclarationPropertyId("Extensions", "related"))),
            t.installedExtensions)
        assertEquals(IdentityDeclaration("localEntityId", "LONG"), t.identity)
    }

    private fun <T> lockedList(values: List<T>, replacement: T) {
        val mutable = values as MutableList<T>
        assertFailsWith<UnsupportedOperationException> { mutable.add(replacement) }
        assertFailsWith<UnsupportedOperationException> { mutable.removeAt(0) }
        assertFailsWith<UnsupportedOperationException> { mutable[0] = replacement }
        assertFailsWith<UnsupportedOperationException> { mutable.clear() }
    }

    @Test fun `all nested input and exposed collections detach through constructors and copies`() {
        for (copy in listOf(false, true)) {
            val parameters = mutableListOf("String", "Long")
            val constraints = mutableListOf(length, range)
            val s = if (copy) scalar().copy(parameters = parameters, constraints = constraints)
                else ScalarDeclaration(id, "db_title", PropertyType.PRIMITIVE, "String", parameters, false,
                    DeclarationRequirement.UNIQUE, constraints, CustomTypeDeclaration("custom.money", "bindings.Money"))
            val fields = mutableListOf(IndexFieldDeclaration("db_title", true), IndexFieldDeclaration("db_related", false))
            val index = if (copy) IndexDeclaration("unused", emptyList()).copy(owner = "P", fields = fields)
                else IndexDeclaration("P", fields)
            val ancestors = mutableListOf("Root", "Ancestor")
            val scalars = mutableListOf(s)
            val candidates = mutableSetOf("db_title")
            val indexes = mutableListOf(index)
            val endpoints = mutableListOf(endpoint())
            val extensions = mutableListOf(extension)
            val t = if (copy) type().copy(ancestors = ancestors, scalars = scalars,
                conditionalRequiredCandidates = candidates, indexes = indexes, endpoints = endpoints,
                installedExtensions = extensions)
                else TypeDeclaration("P", true, ancestors, RegistrationCategory.NATURAL, scalars, candidates,
                    indexes, endpoints, extensions, IdentityDeclaration("localEntityId", "LONG"))
            val types = linkedMapOf("P" to t)
            val model = if (copy) ModelDeclarations(emptyMap()).copy(types) else ModelDeclarations(types)
            val second = model.copy()
            parameters[0] = "changed"; parameters.clear()
            constraints[0] = range; constraints.clear()
            fields[0] = IndexFieldDeclaration("changed", false); fields.clear()
            ancestors[0] = "changed"; ancestors.clear()
            scalars[0] = scalar().copy(databaseName = "changed"); scalars.clear()
            candidates.clear()
            indexes[0] = IndexDeclaration("changed", emptyList()); indexes.clear()
            endpoints[0] = endpoint().copy(databaseName = "changed"); endpoints.clear()
            extensions[0] = extension.copy(pluginId = "changed"); extensions.clear()
            types["P"] = type().copy(name = "changed"); types.clear()
            for (m in listOf(model, second)) {
                assertEquals(setOf("P"), m.types.keys)
                val frozen = m.types.getValue("P")
                assertType(frozen)
                lockedList(frozen.ancestors, "changed")
                lockedList(frozen.scalars, scalar())
                lockedList(frozen.scalars.single().parameters!!, "changed")
                lockedList(frozen.scalars.single().constraints, range)
                lockedList(frozen.indexes, index)
                lockedList(frozen.indexes.single().fields, IndexFieldDeclaration("changed", false))
                lockedList(frozen.endpoints, endpoint())
                lockedList(frozen.installedExtensions, extension)
                val set = frozen.conditionalRequiredCandidates as MutableSet<String>
                assertFailsWith<UnsupportedOperationException> { set.add("changed") }
                assertFailsWith<UnsupportedOperationException> { set.remove("db_title") }
                assertFailsWith<UnsupportedOperationException> { set.clear() }
                val map = m.types as MutableMap<String, TypeDeclaration>
                assertFailsWith<UnsupportedOperationException> { map["P"] = type() }
                assertFailsWith<UnsupportedOperationException> { map.remove("P") }
                assertFailsWith<UnsupportedOperationException> { map.entries.first().setValue(type()) }
                assertFailsWith<UnsupportedOperationException> { map.keys.remove("P") }
                assertFailsWith<UnsupportedOperationException> { map.values.clear() }
                assertFailsWith<UnsupportedOperationException> { map.entries.clear() }
                assertFailsWith<UnsupportedOperationException> { map.clear() }
                assertType(frozen)
            }
        }
    }

    @Test fun `literal parent child requirements parameters and raw bounds`() {
        val p = type()
        val s = TypeDeclaration("S", false, listOf("Root", "Ancestor", "P"), RegistrationCategory.NON_NATURAL,
            listOf(scalar().copy(property = DeclarationPropertyId("S", "otherTitle"))))
        assertType(p)
        assertEquals("S", s.name)
        assertFalse(s.isAbstract)
        assertEquals(listOf("Root", "Ancestor", "P"), s.ancestors)
        assertEquals(RegistrationCategory.NON_NATURAL, s.registrationCategory)
        assertEquals("P", p.scalars.single().property.owner)
        assertEquals("S", s.scalars.single().property.owner)
        assertEquals("otherTitle", s.scalars.single().property.kotlinName)
        assertEquals("db_title", s.scalars.single().databaseName)
        for (r in listOf(DeclarationRequirement.OPTIONAL, DeclarationRequirement.REQUIRED, DeclarationRequirement.UNIQUE))
            assertEquals(r, scalar().copy(requirement = r).requirement)
        assertTrue(scalar().copy(autoIndexed = true).autoIndexed)
        assertNull(scalar().copy(parameters = null).parameters)
        assertEquals(emptyList(), scalar().copy(parameters = emptyList()).parameters)
        assertEquals(emptyList(), scalar().copy(constraints = emptyList()).constraints)
        val extreme = ConstraintDeclaration(ConstraintKind.LENGTH, "custom.Length", ConstraintImplementation.SUBCLASS,
            Int.MIN_VALUE, Int.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(Int.MIN_VALUE, extreme.minLength)
        assertEquals(Int.MAX_VALUE, extreme.maxLength)
        assertEquals(Long.MIN_VALUE, extreme.minValue)
        assertEquals(Long.MAX_VALUE, extreme.maxValue)
        assertEquals("custom.Length", extreme.implementationId)
        assertEquals(ConstraintImplementation.SUBCLASS, extreme.implementation)
        assertEquals(ConstraintKind.CUSTOM, extreme.copy(kind = ConstraintKind.CUSTOM).kind)
        assertEquals(ConstraintImplementation.CUSTOM, extreme.copy(implementation = ConstraintImplementation.CUSTOM).implementation)
        val absent = ConstraintDeclaration(ConstraintKind.CUSTOM, "unknown", ConstraintImplementation.CUSTOM)
        assertNull(absent.minLength)
        assertNull(absent.maxLength)
        assertNull(absent.minValue)
        assertNull(absent.maxValue)
        val numericExtreme = ConstraintDeclaration(ConstraintKind.NUMERIC_RANGE, "inRange",
            ConstraintImplementation.BUILT_IN, minValue = Long.MIN_VALUE, maxValue = Long.MAX_VALUE)
        assertNull(numericExtreme.minLength)
        assertNull(numericExtreme.maxLength)
        assertEquals(Long.MIN_VALUE, numericExtreme.minValue)
        assertEquals(Long.MAX_VALUE, numericExtreme.maxValue)
        assertEquals(ConstraintImplementation.BUILT_IN, numericExtreme.implementation)
        assertEquals(CustomTypeDeclaration("unrecognized", null),
            scalar().copy(customType = CustomTypeDeclaration("unrecognized", null)).customType)
    }

    @Test fun `literal endpoint directions opposites cardinalities and provenance`() {
        val cases = listOf(
            AssociationEndCardinality._0_1 to AssociationEndType.DirectedAssociationEnd,
            AssociationEndCardinality._1 to AssociationEndType.UndirectedAssociationEnd,
            AssociationEndCardinality._0_n to AssociationEndType.ParentEnd,
            AssociationEndCardinality._1_n to AssociationEndType.ChildEnd)
        for ((cardinality, kind) in cases) {
            val e = endpoint().copy(cardinality = cardinality, endType = kind)
            assertEquals(cardinality, e.cardinality)
            assertEquals(kind, e.endType)
            assertEquals("P", e.property.owner)
            assertEquals("S", e.oppositeProperty!!.owner)
        }
        val back = endpoint().copy(property = DeclarationPropertyId("S", "back"), databaseName = "db_back",
            databaseNameOverride = null, oppositeType = "P", oppositeProperty = DeclarationPropertyId("P", "related"),
            onDelete = DeclarationDeletePolicy.FAIL_PER_TYPE, onTargetDelete = DeclarationDeletePolicy.FAIL_PER_ENTITY,
            registrationCategory = RegistrationCategory.NON_NATURAL, installedExtension = null)
        assertEquals("S", back.property.owner)
        assertEquals("P", back.oppositeType)
        assertEquals(DeclarationPropertyId("P", "related"), back.oppositeProperty)
        assertNull(back.databaseNameOverride)
        assertEquals(DeclarationDeletePolicy.FAIL_PER_TYPE, back.onDelete)
        assertEquals(DeclarationDeletePolicy.FAIL_PER_ENTITY, back.onTargetDelete)
        assertNull(back.installedExtension)
        assertEquals(RegistrationCategory.NON_NATURAL, back.registrationCategory)
        val self = endpoint().copy(oppositeType = "P", oppositeProperty = DeclarationPropertyId("P", "self"),
            onDelete = DeclarationDeletePolicy.FAIL)
        assertEquals("P", self.oppositeType)
        assertEquals("self", self.oppositeProperty!!.kotlinName)
        assertEquals(DeclarationDeletePolicy.FAIL, self.onDelete)
        assertNull(endpoint().copy(oppositeProperty = null, oppositeDatabaseNameOverride = null).oppositeProperty)
    }

    @Test fun `one field at a time participates in structural equality`() {
        val s = scalar()
        assertEquals(s, s.copy())
        assertEquals(s.hashCode(), s.copy().hashCode())
        listOf(s.copy(property = id.copy(owner = "S")), s.copy(property = id.copy(kotlinName = "other")),
            s.copy(databaseName = "other"), s.copy(kind = PropertyType.TEXT), s.copy(kind = PropertyType.BLOB),
            s.copy(primitiveTypeName = "string"), s.copy(primitiveTypeName = null), s.copy(parameters = null),
            s.copy(parameters = emptyList()), s.copy(parameters = listOf("Long", "String")),
            s.copy(autoIndexed = true), s.copy(requirement = DeclarationRequirement.REQUIRED),
            s.copy(constraints = listOf(range, length)), s.copy(customType = null),
            s.copy(customType = CustomTypeDeclaration("other", "bindings.Money")),
            s.copy(customType = CustomTypeDeclaration("custom.money", "other"))).forEach { assertNotEquals(s, it) }
        val i = type().indexes.single()
        assertEquals(i, i.copy())
        listOf(i.copy(owner = "S"), i.copy(fields = i.fields.reversed()),
            i.copy(fields = listOf(i.fields[0].copy(isProperty = false), i.fields[1])),
            i.copy(fields = listOf(i.fields[0].copy(databaseName = "other"), i.fields[1]))).forEach { assertNotEquals(i, it) }
        val t = type()
        assertEquals(t, t.copy())
        assertEquals(t.hashCode(), t.copy().hashCode())
        listOf(t.copy(name = "S"), t.copy(isAbstract = false), t.copy(ancestors = t.ancestors.reversed()),
            t.copy(registrationCategory = RegistrationCategory.NON_NATURAL), t.copy(scalars = emptyList()),
            t.copy(conditionalRequiredCandidates = emptySet()), t.copy(indexes = emptyList()),
            t.copy(endpoints = emptyList()), t.copy(installedExtensions = emptyList()),
            t.copy(identity = null)).forEach { assertNotEquals(t, it) }
        val e = endpoint()
        listOf(e.copy(property = id), e.copy(databaseName = "other"), e.copy(databaseNameOverride = null),
            e.copy(oppositeType = "P"), e.copy(oppositeProperty = id), e.copy(oppositeDatabaseNameOverride = null),
            e.copy(cardinality = AssociationEndCardinality._1), e.copy(endType = AssociationEndType.ParentEnd),
            e.copy(onDelete = DeclarationDeletePolicy.FAIL), e.copy(onTargetDelete = DeclarationDeletePolicy.FAIL),
            e.copy(registrationCategory = RegistrationCategory.NON_NATURAL), e.copy(installedExtension = null)
        ).forEach { assertNotEquals(e, it) }
        listOf(length.copy(kind = ConstraintKind.CUSTOM), length.copy(implementationId = "other"),
            length.copy(implementation = ConstraintImplementation.SUBCLASS), length.copy(minLength = -1),
            length.copy(maxLength = 13), length.copy(minValue = 0), length.copy(maxValue = 1)
        ).forEach { assertNotEquals(length, it) }
        assertNotEquals(extension, extension.copy(pluginId = "other"))
        assertNotEquals(extension, extension.copy(property = id))
        assertNotEquals(IdentityDeclaration("localEntityId", "LONG"), IdentityDeclaration("id", "LONG"))
        assertNotEquals(IdentityDeclaration("localEntityId", "LONG"), IdentityDeclaration("localEntityId", "long"))
        val m = ModelDeclarations(mapOf("P" to t))
        assertEquals(m, m.copy())
        assertEquals(m.hashCode(), m.copy().hashCode())
        assertNotEquals(m, m.copy(types = mapOf("S" to t)))
        assertNotEquals(m, m.copy(types = mapOf("P" to t.copy(isAbstract = false))))
    }
}
