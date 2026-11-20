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
package kotlinx.dnq

import com.jetbrains.teamsys.dnq.database.PropertyConstraint
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.constraints.inRange
import jetbrains.exodus.entitystore.constraints.length
import jetbrains.exodus.query.metadata.ConstraintDeclaration
import jetbrains.exodus.query.metadata.ConstraintImplementation.BUILT_IN
import jetbrains.exodus.query.metadata.ConstraintImplementation.CUSTOM
import jetbrains.exodus.query.metadata.ConstraintImplementation.SUBCLASS
import jetbrains.exodus.query.metadata.ConstraintKind
import jetbrains.exodus.query.metadata.CustomTypeDeclaration
import jetbrains.exodus.query.metadata.DeclarationPropertyId
import jetbrains.exodus.query.metadata.DeclarationRequirement
import jetbrains.exodus.query.metadata.DeclarationRequirement.OPTIONAL
import jetbrains.exodus.query.metadata.DeclarationRequirement.REQUIRED
import jetbrains.exodus.query.metadata.DeclarationRequirement.UNIQUE
import jetbrains.exodus.query.metadata.IdentityDeclaration
import jetbrains.exodus.query.metadata.IndexDeclaration
import jetbrains.exodus.query.metadata.IndexFieldDeclaration
import jetbrains.exodus.query.metadata.PropertyType
import jetbrains.exodus.query.metadata.PropertyType.PRIMITIVE
import jetbrains.exodus.query.metadata.RegistrationCategory.NATURAL
import jetbrains.exodus.query.metadata.RegistrationCategory.NON_NATURAL
import jetbrains.exodus.query.metadata.ScalarDeclaration
import jetbrains.exodus.query.metadata.TypeDeclaration
import jetbrains.exodus.util.LightOutputStream
import kotlinx.dnq.simple.XdNullableProperty
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import kotlinx.dnq.simple.custom.type.XdCustomTypeBindingRegistry
import kotlinx.dnq.simple.max
import kotlinx.dnq.simple.min
import kotlinx.dnq.simple.requireIf
import kotlinx.dnq.simple.wrap
import kotlinx.dnq.simple.xdNullableProp
import kotlinx.dnq.simple.xdProp
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.XdHierarchyNode
import kotlinx.dnq.util.captureTypeDeclaration
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.reflect.KProperty1
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class ModelDeclarationCaptureTest {
    abstract class Root(entity: Entity) : XdEntity(entity) {
        companion object : XdEntityType<Root>(StaticStoreContainer) {
            override val entityType = "DeclarationCaptureRoot"
        }

        val notRegistered: String get() = error("Kotlin property getter")
    }

    abstract class P(entity: Entity) : Root(entity) {
        companion object : XdNaturalEntityType<P>("DeclarationCaptureP") {
            override val compositeIndices = listOf(listOf(P::code, P::required))
            override fun initEntityType(): Unit = error("Type initialization")
        }

        var code by xdRequiredStringProp(unique = true, dbName = "parent_code")
        var required by xdRequiredStringProp(dbName = "parent_required")
        var candidate by xdStringProp(trimmed = true, dbName = "parent_conditional", indexed = false) {
            requireIf { error("Conditional predicate") }
        }
        open var label by xdStringProp(dbName = "parent_label")
    }

    class S(entity: Entity) : P(entity) {
        companion object : XdNaturalEntityType<S>("DeclarationCaptureS") {
            val firstIndex = mutableListOf<KProperty1<S, *>>(S::target, S::required)
            override val compositeIndices = mutableListOf(firstIndex, mutableListOf(S::label, S::code))
            val rawLength = length(Int.MIN_VALUE, Int.MAX_VALUE)
            val boundConstraints = mutableListOf(length(), rawLength, LengthSubclass(), Opaque())
            var constructionCount = 0
            override fun initEntityType(): Unit = error("Type initialization")
        }

        init {
            constructionCount++
        }

        override var label by xdStringProp(dbName = "child_label")
        var target by xdLink0_1(P, "target_link")
        var tags by xdSetProp<S, String>()
        var text by xdBlobStringProp()
        var blob by xdBlobProp()
        var timestamp by xdDateTimeProp()
        var numeric by xdLongProp {
            min(Long.MIN_VALUE)
            max(-10)
        }
        var bounded by XdNullableProperty<S, String>(String::class.java, null, boundConstraints)
        var bound by xdNullableProp<S, Token>(binding = TrappingBinding)
        var boundRequired by xdProp<S, Token>(require = true, binding = TrappingBinding, default = { _, _ -> error("Default") })
        var unbound by xdNullableProp<S, Token>()
        var pair by xdProp<S, Token>(binding = TrappingBinding, indexed = false, default = { _, _ -> error("Default") })
            .wrap<S, Token, Pair<String, Int>>({ error("Wrap conversion") }, { error("Unwrap conversion") })
    }

    data class Token(val value: Int) : Comparable<Token> {
        override fun compareTo(other: Token) = value.compareTo(other.value)
    }

    object TrappingBinding : XdCustomTypeBinding<Token>() {
        override val clazz: Class<Token> get() = error("Binding class getter")
        override fun read(stream: ByteArrayInputStream): Token = error("Binding read")
        override fun write(stream: LightOutputStream, value: Token): Unit = error("Binding write")
        override fun min(): Token = error("Binding minimum")
        override fun max(): Token = error("Binding maximum")
        override fun prev(value: Token): Token = error("Binding previous")
        override fun next(value: Token): Token = error("Binding next")
    }

    class LengthSubclass : length(-7, 19) {
        override fun isValid(value: String?): Boolean = error("Subclass validator")
    }

    class Opaque : PropertyConstraint<String?>() {
        override fun isValid(value: String?): Boolean = error("Opaque validator")
        override fun getExceptionMessage(propertyName: String, propertyValue: String?): String = error("Opaque message")
    }

    private lateinit var child: XdHierarchyNode
    private val parent get() = checkNotNull(child.parentNode)
    private val root get() = checkNotNull(parent.parentNode)

    @Before
    fun registerFixtures() {
        child = XdModel.registerNode(S)
    }

    @Test
    fun `capture retains local ownership and ordered mixed indexes`() {
        assertEquals(
            TypeDeclaration("DeclarationCaptureRoot", true, emptyList(), NON_NATURAL, identity = identity),
            root.captureTypeDeclaration()
        )
        assertDeclaration(expectedParent(), parent.captureTypeDeclaration())
        assertDeclaration(expectedChild(), child.captureTypeDeclaration())
    }

    @Test
    fun `capture is repeatable and leaves registration and bindings alone`() {
        val hierarchyBefore = XdModel.hierarchy.toMap()
        val pluginsBefore = XdModel.plugins.toList()
        val nodes = listOf(root, parent, child)
        val childrenBefore = nodes.map { it.children.toList() }
        val propertiesBefore = nodes.map { it.simpleProperties.toList() to it.linkProperties.toList() }
        val indexesBefore = S.compositeIndices.map { it.toList() }
        val constructionsBefore = S.constructionCount
        assertNull(XdCustomTypeBindingRegistry[Token::class.java])

        val first = nodes.map { it.captureTypeDeclaration() }
        val second = nodes.map { it.captureTypeDeclaration() }
        assertEquals(first, second)
        first.zip(second).forEach { (a, b) -> assertNotSame(a, b) }

        assertEquals(hierarchyBefore, XdModel.hierarchy)
        assertEquals(pluginsBefore, XdModel.plugins)
        assertEquals(childrenBefore, nodes.map { it.children.toList() })
        assertEquals(propertiesBefore, nodes.map { it.simpleProperties.toList() to it.linkProperties.toList() })
        assertEquals(indexesBefore, S.compositeIndices)
        assertEquals(constructionsBefore, S.constructionCount)
        assertNull(XdCustomTypeBindingRegistry[Token::class.java])
    }

    @Test
    fun `capture detaches bounds and nested collections from registered inputs`() {
        val snapshot = child.captureTypeDeclaration()
        val constraints = S.boundConstraints.toList()
        val fields = S.firstIndex.toList()
        val indexes = S.compositeIndices.toList()
        val properties = child.simpleProperties.toMap()
        try {
            S.rawLength.min = 42
            S.boundConstraints.removeAt(0)
            S.firstIndex.reverse()
            S.compositeIndices.removeAt(1)
            child.simpleProperties["label"] = checkNotNull(child.simpleProperties.remove("label"))

            assertDeclaration(expectedChild(), snapshot)
            val current = child.captureTypeDeclaration()
            assertEquals("label", current.scalars.last().property.kotlinName)
            assertEquals(
                listOf(rawLength.copy(minLength = 42), subclassLength, opaque),
                current.scalars.single { it.property.kotlinName == "bounded" }.constraints
            )
            assertEquals(listOf(childIndexes[0].copy(fields = childIndexes[0].fields.reversed())), current.indexes)
            assertUnmodifiable(snapshot.scalars)
            assertUnmodifiable(snapshot.ancestors)
            assertUnmodifiable(snapshot.indexes)
            assertUnmodifiable(snapshot.indexes.first().fields)
            assertUnmodifiable(snapshot.scalars.single { it.property.kotlinName == "tags" }.parameters!!)
            assertUnmodifiable(snapshot.scalars.single { it.property.kotlinName == "bounded" }.constraints)
            assertUnmodifiable(parent.captureTypeDeclaration().conditionalRequiredCandidates)
        } finally {
            S.rawLength.min = Int.MIN_VALUE
            S.boundConstraints.clear()
            S.boundConstraints.addAll(constraints)
            S.firstIndex.clear()
            S.firstIndex.addAll(fields)
            S.compositeIndices.clear()
            S.compositeIndices.addAll(indexes)
            child.simpleProperties.clear()
            child.simpleProperties.putAll(properties)
        }
    }

    @Test
    fun `unregistered composite field fails explicitly`() {
        val fields = S.firstIndex.toList()
        try {
            S.firstIndex.add(S::notRegistered)
            val failure = assertFailsWith<IllegalArgumentException> { child.captureTypeDeclaration() }
            assertEquals("Cannot build composite index by property DeclarationCaptureS::notRegistered", failure.message)
        } finally {
            S.firstIndex.clear()
            S.firstIndex.addAll(fields)
        }
    }

    private fun assertDeclaration(expected: TypeDeclaration, actual: TypeDeclaration) {
        assertEquals(expected.scalars.map { it.property }, actual.scalars.map { it.property }, "Scalar order and ownership")
        expected.scalars.zip(actual.scalars).forEach { (a, b) ->
            fun ScalarDeclaration.facts() =
                listOf(databaseName, kind, primitiveTypeName, parameters, autoIndexed, requirement, constraints, customType)
            assertEquals(a.facts(), b.facts(), "Scalar facts for ${a.property}")
        }
        assertEquals(expected.indexes.map { it.owner to it.fields }, actual.indexes.map { it.owner to it.fields }, "Indexes")
        assertEquals(expected.copy(scalars = emptyList(), indexes = emptyList()),
            actual.copy(scalars = emptyList(), indexes = emptyList()), "Type facts for ${expected.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertUnmodifiable(values: Collection<T>) {
        assertFailsWith<UnsupportedOperationException> { (values as MutableCollection<T>).clear() }
    }

    private fun expectedParent() = TypeDeclaration(
        "DeclarationCaptureP", true, listOf("DeclarationCaptureRoot"), NATURAL,
        scalars = listOf(
            scalar("DeclarationCaptureP", "code", "parent_code", requirement = UNIQUE),
            scalar("DeclarationCaptureP", "required", "parent_required", requirement = REQUIRED),
            scalar("DeclarationCaptureP", "candidate", "parent_conditional", indexed = false, constraints = listOf(
                ConstraintDeclaration(ConstraintKind.CUSTOM, "kotlinx.dnq.simple.RequireIfConstraint", CUSTOM)
            )),
            scalar("DeclarationCaptureP", "label", "parent_label")
        ),
        conditionalRequiredCandidates = setOf("parent_conditional"),
        indexes = listOf(IndexDeclaration("DeclarationCaptureP", listOf(
            IndexFieldDeclaration("parent_code", true), IndexFieldDeclaration("parent_required", true)
        ))),
        identity = identity
    )

    private fun expectedChild() = TypeDeclaration(
        "DeclarationCaptureS", false, listOf("DeclarationCaptureRoot", "DeclarationCaptureP"), NATURAL,
        scalars = listOf(
            scalar("DeclarationCaptureS", "label", "child_label"),
            scalar("DeclarationCaptureS", "tags", type = "Set", parameters = listOf("string")),
            scalar("DeclarationCaptureS", "text", kind = PropertyType.TEXT),
            scalar("DeclarationCaptureS", "blob", kind = PropertyType.BLOB, type = "InputStream"),
            scalar("DeclarationCaptureS", "timestamp", type = "DateTime"),
            // xdLongProp defaults to 0L and exposes a non-null primitive long getter.
            scalar("DeclarationCaptureS", "numeric", type = "long", constraints = listOf(
                ConstraintDeclaration(ConstraintKind.NUMERIC_RANGE, inRange::class.java.name, BUILT_IN,
                    minValue = Long.MIN_VALUE, maxValue = Long.MAX_VALUE),
                ConstraintDeclaration(ConstraintKind.NUMERIC_RANGE, inRange::class.java.name, BUILT_IN,
                    minValue = 0, maxValue = -10)
            )),
            scalar("DeclarationCaptureS", "bounded", constraints = listOf(
                rawLength.copy(minLength = 0), rawLength, subclassLength, opaque
            )),
            scalar("DeclarationCaptureS", "bound", type = "Token",
                customType = CustomTypeDeclaration(Token::class.java.name, TrappingBinding::class.java.name)),
            scalar("DeclarationCaptureS", "boundRequired", type = "Token", requirement = REQUIRED,
                customType = CustomTypeDeclaration(Token::class.java.name, TrappingBinding::class.java.name)),
            scalar("DeclarationCaptureS", "unbound", type = "Token"),
            scalar("DeclarationCaptureS", "pair", type = "Pair", parameters = listOf("string", "integer"), indexed = false)
        ),
        indexes = childIndexes,
        identity = identity
    )

    private fun scalar(
        owner: String,
        name: String,
        dbName: String = name,
        kind: PropertyType = PRIMITIVE,
        type: String = "String",
        parameters: List<String>? = null,
        indexed: Boolean = true,
        requirement: DeclarationRequirement = OPTIONAL,
        constraints: List<ConstraintDeclaration> = emptyList(),
        customType: CustomTypeDeclaration? = null
    ) = ScalarDeclaration(DeclarationPropertyId(owner, name), dbName, kind, type, parameters, indexed,
        requirement, constraints, customType)

    private val identity = IdentityDeclaration("localEntityId", "LONG")
    private val childIndexes = listOf(
        IndexDeclaration("DeclarationCaptureS", listOf(
            IndexFieldDeclaration("target_link", false), IndexFieldDeclaration("parent_required", true)
        )),
        IndexDeclaration("DeclarationCaptureS", listOf(
            IndexFieldDeclaration("child_label", true), IndexFieldDeclaration("parent_code", true)
        ))
    )
    private val rawLength = ConstraintDeclaration(ConstraintKind.LENGTH, length::class.java.name, BUILT_IN,
        minLength = Int.MIN_VALUE, maxLength = Int.MAX_VALUE)
    private val subclassLength = ConstraintDeclaration(ConstraintKind.LENGTH, LengthSubclass::class.java.name, SUBCLASS,
        minLength = -7, maxLength = 19)
    private val opaque = ConstraintDeclaration(ConstraintKind.CUSTOM, Opaque::class.java.name, CUSTOM)
}
