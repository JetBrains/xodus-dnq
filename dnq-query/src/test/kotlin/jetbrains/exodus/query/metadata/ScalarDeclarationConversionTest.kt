package jetbrains.exodus.query.metadata

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType as EngineType
import org.junit.Test
import kotlin.test.*

class ScalarDeclarationConversionTest {
    private fun declaration(kind: PropertyType, primitive: String?, parameters: List<String>?, auto: Boolean) =
        ScalarDeclaration(DeclarationPropertyId("P", "kotlinValue"), "db_value", kind, primitive, parameters,
            auto, DeclarationRequirement.REQUIRED)

    @Test fun `two fresh mutable scalar materializations preserve all raw fields`() {
        for (kind in listOf(PropertyType.PRIMITIVE, PropertyType.TEXT, PropertyType.BLOB)) {
            for (primitive in listOf("Set", "UnknownCase", null)) {
                for (parameters in listOf(null, emptyList(), listOf("String", "Long"))) {
                    for (auto in listOf(false, true)) {
                        val d = declaration(kind, primitive, parameters, auto)
                        val first = d.toSimplePropertyMetaData()
                        val second = d.toSimplePropertyMetaData()
                        assertNotSame(first, second)
                        for (m in listOf(first, second)) {
                            assertEquals("db_value", m.name)
                            assertEquals(kind, m.type)
                            assertEquals(primitive, m.primitiveTypeName)
                            assertEquals(parameters, m.typeParameterNames)
                            assertEquals(auto, m.isAutoIndexed)
                        }
                        if (parameters != null) {
                            assertNotSame(first.typeParameterNames, second.typeParameterNames)
                            assertNotSame(d.parameters, first.typeParameterNames)
                            assertNotSame(d.parameters, second.typeParameterNames)
                            first.typeParameterNames!!.add("first")
                            assertEquals(parameters, second.typeParameterNames)
                            second.typeParameterNames!!.add("second")
                            assertEquals(parameters + "first", first.typeParameterNames)
                            assertEquals(parameters + "second", second.typeParameterNames)
                        } else {
                            assertNull(first.typeParameterNames)
                            assertNull(second.typeParameterNames)
                        }
                        first.name = "first"; first.type = PropertyType.TEXT
                        first.primitiveTypeName = "first"; first.isAutoIndexed = !auto
                        assertEquals("db_value", second.name)
                        assertEquals(kind, second.type)
                        assertEquals(primitive, second.primitiveTypeName)
                        assertEquals(auto, second.isAutoIndexed)
                        second.name = "second"; second.type = PropertyType.BLOB
                        second.primitiveTypeName = "second"; second.isAutoIndexed = auto
                        assertEquals("first", first.name)
                        assertEquals(PropertyType.TEXT, first.type)
                        assertEquals("first", first.primitiveTypeName)
                        assertEquals(!auto, first.isAutoIndexed)
                        assertEquals("db_value", d.databaseName)
                        assertEquals(kind, d.kind)
                        assertEquals(primitive, d.primitiveTypeName)
                        assertEquals(parameters, d.parameters)
                        assertEquals(auto, d.autoIndexed)
                    }
                }
            }
        }
    }

    @Test fun `existing mapper supplies literal scalar and Set element mappings`() {
        val cases = mapOf("boolean" to EngineType.BOOLEAN, "string" to EngineType.STRING,
            "byte" to EngineType.BYTE, "short" to EngineType.SHORT, "int" to EngineType.INTEGER,
            "Integer" to EngineType.INTEGER, "long" to EngineType.LONG, "datetime" to EngineType.LONG,
            "float" to EngineType.FLOAT, "double" to EngineType.DOUBLE)
        for ((name, expected) in cases) {
            val scalar = declaration(PropertyType.PRIMITIVE, name, null, false).toSimplePropertyMetaData()
                .toYouTrackDbProperty()
            assertEquals("db_value", scalar.name)
            assertEquals(PropertyType.PRIMITIVE, scalar.kind)
            assertEquals(expected, scalar.type)
            assertNull(scalar.linkedType)
            val set = declaration(PropertyType.PRIMITIVE, "Set", listOf(name), true).toSimplePropertyMetaData()
                .toYouTrackDbProperty()
            assertEquals("db_value", set.name)
            assertEquals(PropertyType.PRIMITIVE, set.kind)
            assertEquals(EngineType.EMBEDDEDSET, set.type)
            assertEquals(expected, set.linkedType)
        }
        for (kind in listOf(PropertyType.TEXT, PropertyType.BLOB)) {
            val mapped = declaration(kind, "unsupported", null, false).toSimplePropertyMetaData().toYouTrackDbProperty()
            assertEquals("db_value", mapped.name)
            assertEquals(kind, mapped.kind)
            assertEquals(EngineType.LINK, mapped.type)
            assertNull(mapped.linkedType)
        }
        for ((primitive, parameters) in listOf("unsupported" to null, "Set" to listOf("unsupported"))) {
            assertFailsWith<IllegalArgumentException> {
                declaration(PropertyType.PRIMITIVE, primitive, parameters, true).toSimplePropertyMetaData().toYouTrackDbProperty()
            }
        }
        for (parameters in listOf<List<String>?>(null, emptyList())) {
            assertFailsWith<IllegalStateException> {
                declaration(PropertyType.PRIMITIVE, "Set", parameters, true).toSimplePropertyMetaData().toYouTrackDbProperty()
            }
        }
    }
}
