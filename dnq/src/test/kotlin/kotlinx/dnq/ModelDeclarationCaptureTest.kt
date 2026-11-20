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
import kotlinx.dnq.simple.XdConstrainedProperty
import kotlinx.dnq.simple.XdNullableProperty
import kotlinx.dnq.simple.XdPropertyRequirement
import kotlinx.dnq.simple.custom.type.XdCustomTypeBinding
import kotlinx.dnq.simple.custom.type.XdCustomTypeBindingRegistry
import kotlinx.dnq.simple.custom.type.XdCustomTypeProperty
import kotlinx.dnq.simple.max
import kotlinx.dnq.simple.min
import kotlinx.dnq.simple.requireIf
import kotlinx.dnq.simple.wrap
import kotlinx.dnq.simple.xdNullableProp
import kotlinx.dnq.simple.xdProp
import kotlinx.dnq.store.container.StaticStoreContainer
import kotlinx.dnq.util.DeclarationFact
import kotlinx.dnq.util.DeclarationFactKind.Companion.AUTO_INDEXED
import kotlinx.dnq.util.DeclarationFactKind.Companion.BINDING
import kotlinx.dnq.util.DeclarationFactKind.Companion.CONSTRAINTS
import kotlinx.dnq.util.DeclarationFactKind.Companion.DATABASE_NAME
import kotlinx.dnq.util.DeclarationFactKind.Companion.INDEXES
import kotlinx.dnq.util.DeclarationFactKind.Companion.KIND
import kotlinx.dnq.util.DeclarationFactKind.Companion.KOTLIN_NAME
import kotlinx.dnq.util.DeclarationFactKind.Companion.LOGICAL_TYPE
import kotlinx.dnq.util.DeclarationFactKind.Companion.REQUIREMENT
import kotlinx.dnq.util.DeclarationFactKind.Companion.TYPE_NAME
import kotlinx.dnq.util.DirectBindingFact
import kotlinx.dnq.util.ExplicitIndexFacts
import kotlinx.dnq.util.LogicalScalarType
import kotlinx.dnq.util.MissingDeclarationFact
import kotlinx.dnq.util.ScalarConstraintFacts
import kotlinx.dnq.util.XdHierarchyNode
import kotlinx.dnq.util.captureTypeDeclaration
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ModelDeclarationCaptureTest {
    class Plain(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Plain>("DeclarationCapturePlain")
        val value by xdStringProp()
    }

    abstract class Root(entity: Entity) : XdEntity(entity) {
        companion object : XdEntityType<Root>(StaticStoreContainer) {
            val nameProbe = Probe()
            override val entityType get() = nameProbe.read("DeclarationCaptureRoot")
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
            val nameProbe = Probe()
            var currentName = "DeclarationCaptureS"
            override val entityType get() = nameProbe.read(currentName)
            val indexesProbe = Probe()
            val firstIndex = GuardedList<KProperty1<S, *>>(S::target, S::required)
            val indexLists = GuardedList<List<KProperty1<S, *>>>(
                firstIndex, listOf(S::label, S::code), listOf(S::label, S::code), listOf(S::target, S::target), listOf(S::label)
            )
            override val compositeIndices get() = indexesProbe.read(indexLists)
            val tokenConstraints = GuardedList<PropertyConstraint<Token?>>()
            val tokenMetadata = Probe()
            val tokenBinding = Probe()
            var bindingValue: XdCustomTypeBinding<Token>? = TrappingBinding
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
            constraints.add(RangeSubclass())
        }
        var bounded by XdNullableProperty<S, String>(String::class.java, null, boundConstraints)
        var bound by xdNullableProp<S, Token>(binding = TrappingBinding)
        var boundRequired by xdProp<S, Token>(require = true, binding = TrappingBinding, default = { _, _ -> error("Default") })
        var unbound by xdNullableProp<S, Token>()
        var pair by xdProp<S, Token>(binding = TrappingBinding, require = true, indexed = false,
            default = { _, _ -> error("Default") })
            .wrap<S, Token, Pair<String, Int>>({ error("Wrap conversion") }, { error("Unwrap conversion") })
        val custom by DirectTokenProperty()
    }

    class Probe {
        var armed = false
        var rejectRead = true
        var reads = 0
            private set

        fun <T> read(value: T): T {
            if (armed) {
                reads++
                check(!rejectRead) { "Unexpected model read" }
            }
            return value
        }

        fun <T> observe(action: () -> T): T {
            val before = reads
            armed = true
            rejectRead = false
            try {
                return action().also { assertEquals(before + 1, reads) }
            } finally {
                rejectRead = true
                armed = false
            }
        }
    }

    // An ArrayList subtype is not an audited ArrayList, even when its contents are empty.
    class GuardedList<T>(vararg items: T) : ArrayList<T>(items.toList()) {
        val traversal = Probe()
        override fun iterator(): MutableIterator<T> = traversal.read(Unit).let { super.iterator() }
        override fun get(index: Int): T = traversal.read(Unit).let { super.get(index) }
        override val size: Int get() = traversal.read(Unit).let { super.size }
        override fun toArray(): Array<Any?> = traversal.read(Unit).let { super.toArray() }
        override fun <E> toArray(array: Array<E>): Array<E> = traversal.read(Unit).let { super.toArray(array) }
    }

    class DirectTokenProperty : XdConstrainedProperty<S, Token?>(
        null, S.tokenConstraints, XdPropertyRequirement.OPTIONAL, PRIMITIVE
    ), XdCustomTypeProperty<Token> {
        override val dbPropertyName get() = S.tokenMetadata.read("custom_token")
        override val requirement get() = S.tokenMetadata.read(XdPropertyRequirement.OPTIONAL)
        override val propertyType get() = S.tokenMetadata.read(PRIMITIVE)
        override val isAutoIndexed get() = S.tokenMetadata.read(false)
        override val binding get() = S.tokenBinding.read(S.bindingValue)
        override fun getValue(thisRef: S, property: KProperty<*>): Token? = error("Token getter")
        override fun isDefined(thisRef: S, property: KProperty<*>): Boolean = error("Token defined")
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

    class RangeSubclass : inRange<Long?>(-15, 22) {
        override fun isValid(value: Long?): Boolean = error("Range subclass validator")
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

    private fun rootName() = TYPE_NAME.at(root, "DeclarationCaptureRoot")
    private fun parentFacts() = arrayOf(rootName(), INDEXES.at(parent, ExplicitIndexFacts(arrayOf("code", "required"))))

    private fun childFacts(indexes: ExplicitIndexFacts = ExplicitIndexFacts(
        arrayOf("target", "required"), arrayOf("label", "code"), arrayOf("label", "code"),
        arrayOf("target", "target"), arrayOf("label")
    ), binding: CustomTypeDeclaration? = tokenBinding, currentName: String = S.currentName): Array<DeclarationFact<*>> {
        val slot = child.simpleProperties.getValue("custom")
        return arrayOf(rootName(), TYPE_NAME.at(child, currentName), INDEXES.at(child, indexes),
            DATABASE_NAME.at(child, "custom_token", slot), KIND.at(child, PRIMITIVE, slot),
            AUTO_INDEXED.at(child, false, slot), REQUIREMENT.at(child, OPTIONAL, slot),
            CONSTRAINTS.at(child, ScalarConstraintFacts(false), slot), BINDING.at(child, DirectBindingFact(binding), slot))
    }

    private fun <T> withoutModelReads(vararg extra: Probe, action: () -> T): T {
        val probes = listOf(Root.nameProbe, S.nameProbe, S.indexesProbe, S.indexLists.traversal,
            S.firstIndex.traversal, S.tokenConstraints.traversal, S.tokenMetadata, S.tokenBinding) + extra
        val before = probes.map { it.reads }
        probes.forEach { it.armed = true }
        try {
            return action()
        } finally {
            probes.forEach { it.armed = false }
            assertEquals(before, probes.map { it.reads }, "Model probe counts")
        }
    }

    @Test
    fun `capture uses proven defaults without supplied facts`() {
        val plain = XdModel.registerNode(Plain)
        assertDeclaration(TypeDeclaration("DeclarationCapturePlain", false, emptyList(), NATURAL,
            scalars = listOf(scalar("DeclarationCapturePlain", "value")), identity = identity),
            plain.captureTypeDeclaration())
    }

    @Test
    fun `capture retains local ownership and ordered mixed indexes`() = withoutModelReads {
        assertEquals(
            TypeDeclaration("DeclarationCaptureRoot", true, emptyList(), NON_NATURAL, identity = identity),
            root.captureTypeDeclaration(rootName())
        )
        assertDeclaration(expectedParent(), parent.captureTypeDeclaration(*parentFacts()))
        assertDeclaration(expectedChild(), child.captureTypeDeclaration(*childFacts()))
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
        assertSame(child, S.nameProbe.observe { XdModel.registerNode(S) })
        assertEquals(constructionsBefore, S.constructionCount)
        assertNull(XdCustomTypeBindingRegistry[Token::class.java])

        fun capture() = withoutModelReads {
            listOf(root.captureTypeDeclaration(rootName()), parent.captureTypeDeclaration(*parentFacts()),
                child.captureTypeDeclaration(*childFacts()))
        }
        val first = capture()
        val second = capture()
        assertEquals(first, second)
        first.zip(second).forEach { (a, b) -> assertNotSame(a, b) }

        assertEquals(hierarchyBefore, XdModel.hierarchy)
        assertEquals(pluginsBefore, XdModel.plugins)
        assertEquals(childrenBefore, nodes.map { it.children.toList() })
        assertEquals(propertiesBefore, nodes.map { it.simpleProperties.toList() to it.linkProperties.toList() })
        assertEquals(indexesBefore, S.compositeIndices.map { it.toList() })
        assertEquals(constructionsBefore, S.constructionCount)
        assertNull(XdCustomTypeBindingRegistry[Token::class.java])
    }

    @Test
    fun `capture detaches bounds and nested collections from registered inputs`() {
        val firstFields = arrayOf("target", "required")
        val oldIndex = ExplicitIndexFacts(firstFields, arrayOf("label", "code"), arrayOf("label", "code"),
            arrayOf("target", "target"), arrayOf("label"))
        val snapshot = child.captureTypeDeclaration(*childFacts(oldIndex))
        val constraints = S.boundConstraints.toList()
        val properties = child.simpleProperties.toMap()
        try {
            S.rawLength.min = 42
            S.boundConstraints.removeAt(0)
            firstFields.reverse()
            child.simpleProperties["label"] = checkNotNull(child.simpleProperties.remove("label"))

            assertDeclaration(expectedChild(), snapshot)
            assertEquals(childIndexes, child.captureTypeDeclaration(*childFacts(oldIndex)).indexes)
            val current = child.captureTypeDeclaration(*childFacts(ExplicitIndexFacts(firstFields)))
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
            assertUnmodifiable(parent.captureTypeDeclaration(*parentFacts()).conditionalRequiredCandidates)
        } finally {
            S.rawLength.min = Int.MIN_VALUE
            S.boundConstraints.clear()
            S.boundConstraints.addAll(constraints)
            child.simpleProperties.clear()
            child.simpleProperties.putAll(properties)
        }
    }

    @Test
    fun `unregistered composite field fails explicitly`() = withoutModelReads {
        val badIndexes = ExplicitIndexFacts(arrayOf("target", "notRegistered"))
        val failure = assertFailsWith<IllegalArgumentException> { child.captureTypeDeclaration(*childFacts(badIndexes)) }
        assertEquals("Cannot build composite index by property DeclarationCaptureS::notRegistered", failure.message)
    }

    @Test
    fun `refusal and retry do not evaluate model metadata or traverse model collections`() {
        // Positive controls reach the real getter and both iterator acquisitions independently.
        val indexes = S.indexesProbe.observe { S.compositeIndices }
        assertSame(S.firstIndex, S.indexLists.traversal.observe { indexes.iterator() }.next())
        S.firstIndex.traversal.observe { S.firstIndex.iterator() }
        S.tokenConstraints.traversal.observe { S.tokenConstraints.iterator() }
        val delegate = child.simpleProperties.getValue("custom").delegate as DirectTokenProperty
        assertSame(TrappingBinding, S.tokenBinding.observe { delegate.binding })

        withoutModelReads {
            fun verifyOmissions() {
                val supplied = childFacts()
                val childId = S.javaClass.name
                val slotId = "$childId::custom"
                val diagnostics = listOf(
                    "type name for ${Root.javaClass.name}", "type name for $childId", "explicit indexes for $childId",
                    "database name for $slotId", "scalar kind for $slotId", "auto-indexing for $slotId",
                    "requiredness for $slotId", "constraints for $slotId", "direct binding for $slotId"
                )
                assertEquals(diagnostics.size, supplied.size)
                for ((omitted, diagnostic) in supplied.zip(diagnostics)) {
                    val failure = assertFailsWith<MissingDeclarationFact> {
                        child.captureTypeDeclaration(*supplied.filterNot { it === omitted }.toTypedArray())
                    }
                    assertEquals("Missing declaration fact $diagnostic", failure.message)
                }
            }
            verifyOmissions()
            val original = child.captureTypeDeclaration(*childFacts())
            assertDeclaration(expectedChild(), original)
            verifyOmissions()

            S.currentName = "DeclarationCaptureSCurrent"
            try {
                val renamed = child.captureTypeDeclaration(*childFacts())
                assertEquals("DeclarationCaptureSCurrent", renamed.name)
                assertEquals(setOf("DeclarationCaptureSCurrent"), renamed.scalars.map { it.property.owner }.toSet())
                assertEquals(setOf("DeclarationCaptureSCurrent"), renamed.indexes.map { it.owner }.toSet())
                assertDeclaration(expectedChild(), original)
                verifyOmissions()
            } finally {
                S.currentName = "DeclarationCaptureS"
            }

            S.bindingValue = null
            try {
                val result = child.captureTypeDeclaration(*childFacts(ExplicitIndexFacts(), binding = null))
                assertEquals(emptyList(), result.indexes)
                assertNull(result.scalars.single { it.property.kotlinName == "custom" }.customType)
            } finally {
                S.bindingValue = TrappingBinding
            }
        }
    }

    @Test
    fun `owner and slot evidence fills gaps but cannot add or override declarations`() = withoutModelReads {
        val slot = child.simpleProperties.getValue("custom")
        val override = assertFailsWith<IllegalArgumentException> {
            child.captureTypeDeclaration(*childFacts(), TYPE_NAME.at(parent, "OverriddenParent"))
        }
        assertEquals("Cannot override declaration fact type name for ${P.javaClass.name}", override.message)
        val plain = XdModel.registerNode(Plain)
        val emptyOverride = assertFailsWith<IllegalArgumentException> {
            plain.captureTypeDeclaration(INDEXES.at(plain, ExplicitIndexFacts()))
        }
        assertEquals("Cannot override declaration fact explicit indexes for ${Plain.javaClass.name}", emptyOverride.message)
        val foreign = assertFailsWith<IllegalArgumentException> {
            child.captureTypeDeclaration(*childFacts(), BINDING.at(parent, DirectBindingFact(tokenBinding), slot))
        }
        assertEquals("Unused declaration fact direct binding for ${P.javaClass.name}", foreign.message)
        val fake = assertFailsWith<IllegalArgumentException> {
            child.captureTypeDeclaration(*childFacts(), BINDING.at(child, DirectBindingFact(tokenBinding), slot.copy()))
        }
        assertEquals("Unused declaration fact direct binding for ${S.javaClass.name}", fake.message)
    }

    @Test
    fun `an unproven property reference needs plain Kotlin facts`() {
        val original = child.simpleProperties.getValue("custom")
        val probe = Probe()
        val reference = object : KProperty1<S, Token?> by S::custom {
            override val name get() = probe.read("custom")
            override val returnType: KType get() = probe.read((S::custom).returnType)
            override fun equals(other: Any?) = probe.read(false)
            override fun hashCode() = probe.read(0)
            override fun toString() = probe.read("Custom reference")
        }
        val slot = original.copy(property = reference)
        child.simpleProperties["custom"] = slot
        try {
            withoutModelReads(probe) {
                val name = KOTLIN_NAME.at(child, "custom", slot)
                val type = LOGICAL_TYPE.at(child, LogicalScalarType("Token"), slot)
                fun verifyOmissions() {
                    for ((missing, present) in listOf("Kotlin name" to type, "logical scalar type" to name)) {
                        val failure = assertFailsWith<MissingDeclarationFact> {
                            child.captureTypeDeclaration(*childFacts(), present)
                        }
                        assertEquals("Missing declaration fact $missing for ${S.javaClass.name}::custom", failure.message)
                    }
                }
                verifyOmissions()
                assertDeclaration(expectedChild(), child.captureTypeDeclaration(*childFacts(), name, type))
                verifyOmissions()
            }
        } finally {
            child.simpleProperties["custom"] = original
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
                    minValue = 0, maxValue = -10),
                ConstraintDeclaration(ConstraintKind.NUMERIC_RANGE, RangeSubclass::class.java.name, SUBCLASS,
                    minValue = -15, maxValue = 22)
            )),
            scalar("DeclarationCaptureS", "bounded", constraints = listOf(
                rawLength.copy(minLength = 0), rawLength, subclassLength, opaque
            )),
            scalar("DeclarationCaptureS", "bound", type = "Token",
                customType = CustomTypeDeclaration(Token::class.java.name, TrappingBinding::class.java.name)),
            scalar("DeclarationCaptureS", "boundRequired", type = "Token", requirement = REQUIRED,
                customType = CustomTypeDeclaration(Token::class.java.name, TrappingBinding::class.java.name)),
            scalar("DeclarationCaptureS", "unbound", type = "Token"),
            scalar("DeclarationCaptureS", "pair", type = "Pair", parameters = listOf("string", "integer"), indexed = false,
                requirement = REQUIRED),
            scalar("DeclarationCaptureS", "custom", "custom_token", type = "Token", indexed = false,
                customType = CustomTypeDeclaration("kotlinx.dnq.ModelDeclarationCaptureTest\$Token",
                    "kotlinx.dnq.ModelDeclarationCaptureTest\$TrappingBinding"))
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
    private val tokenBinding = CustomTypeDeclaration(Token::class.java.name, TrappingBinding::class.java.name)
    private val childMixedIndex = IndexDeclaration("DeclarationCaptureS", listOf(
        IndexFieldDeclaration("child_label", true), IndexFieldDeclaration("parent_code", true)
    ))
    private val childIndexes = listOf(
        IndexDeclaration("DeclarationCaptureS", listOf(
            IndexFieldDeclaration("target_link", false), IndexFieldDeclaration("parent_required", true)
        )),
        childMixedIndex,
        childMixedIndex,
        IndexDeclaration("DeclarationCaptureS", listOf(IndexFieldDeclaration("target_link", false),
            IndexFieldDeclaration("target_link", false))),
        IndexDeclaration("DeclarationCaptureS", listOf(IndexFieldDeclaration("child_label", true)))
    )
    private val rawLength = ConstraintDeclaration(ConstraintKind.LENGTH, length::class.java.name, BUILT_IN,
        minLength = Int.MIN_VALUE, maxLength = Int.MAX_VALUE)
    private val subclassLength = ConstraintDeclaration(ConstraintKind.LENGTH, LengthSubclass::class.java.name, SUBCLASS,
        minLength = -7, maxLength = 19)
    private val opaque = ConstraintDeclaration(ConstraintKind.CUSTOM, Opaque::class.java.name, CUSTOM)
}
