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
import kotlinx.dnq.simple.RequireIfConstraint
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
import kotlinx.dnq.util.ConstraintDescriptors
import kotlinx.dnq.util.DeclarationFact
import kotlinx.dnq.util.DeclarationFactKind.Companion.AUTO_INDEXED
import kotlinx.dnq.util.DeclarationFactKind.Companion.BINDING
import kotlinx.dnq.util.DeclarationFactKind.Companion.CONDITIONAL_REQUIRED
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
import kotlinx.dnq.util.XdHierarchyNode
import kotlinx.dnq.util.captureTypeDeclaration
import kotlinx.dnq.util.getPropertyConstraints
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.ref.SoftReference
import java.util.Arrays
import java.util.Collections
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelDeclarationCaptureTest {
    class Plain(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Plain>("DeclarationCapturePlain") {
            val summaryProbe = Probe()

            @JvmName("getCompositeIndices")
            fun indexSummary(): String = summaryProbe.read("summary")
        }
        val value by xdStringProp()
        val javaEmpty by XdNullableProperty<Plain, String>(String::class.java, null, Collections.emptyList())
    }

    class PropertyOrigin(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PropertyOrigin>("DeclarationCapturePropertyOrigin")
        val value by xdStringProp()
        val other by xdStringProp()
        var target by xdLink0_1(Plain)
    }

    open class IndexParent(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<IndexParent>("DeclarationCaptureIndexParent") {
            val databaseNameProbe = Probe()
        }

        val inherited by object : XdConstrainedProperty<IndexParent, String?>(
            null, emptyList(), XdPropertyRequirement.OPTIONAL, PRIMITIVE
        ) {
            override val dbPropertyName get() = databaseNameProbe.read("inherited_db")
            override fun getValue(thisRef: IndexParent, property: KProperty<*>): String? = error("Inherited getter")
            override fun isDefined(thisRef: IndexParent, property: KProperty<*>): Boolean = error("Inherited defined")
        }
    }

    class IndexChild(entity: Entity) : IndexParent(entity) {
        companion object : XdNaturalEntityType<IndexChild>("DeclarationCaptureIndexChild") {
            override val compositeIndices: List<List<KProperty1<IndexChild, *>>> = listOf(listOf(IndexChild::inherited))
        }
    }

    class ConstraintLists(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ConstraintLists>("DeclarationCaptureConstraintLists") {
            val required = RequireIfConstraint<ConstraintLists, String?>(null) { error("Conditional predicate") }
            val elementProbe = Probe()
            val guarded = Opaque(elementProbe)
            val shared = arrayOf<PropertyConstraint<String?>>(required, guarded)
        }

        private fun property(name: String, list: List<PropertyConstraint<String?>>) =
            XdNullableProperty<ConstraintLists, String>(String::class.java, name, list)

        val arrayList by property("array_list", arrayListOf(guarded, LengthSubclass(), guarded))
        val kotlinEmpty by property("kotlin_empty", emptyList())
        val kotlinZero by property("kotlin_zero", listOf())
        val kotlinOne by property("kotlin_one", listOf(required))
        val kotlinMany by property("kotlin_many", listOf(LengthSubclass(), required))
        val arraysZero by property("arrays_zero", Arrays.asList())
        val arraysMany by property("arrays_many", Arrays.asList(required, guarded))
        val listOfZero by property("list_of_zero", java.util.List.of())
        val listOfOne by property("list_of_one", java.util.List.of(guarded))
        val listOfTwo by property("list_of_two", java.util.List.of(required, guarded))
        val listOfMany by property("list_of_many", java.util.List.of(guarded, required, guarded))
        val javaEmpty by property("java_empty", Collections.emptyList())
        val javaSingleton by property("java_singleton", Collections.singletonList(required))
        val copiesZero by property("copies_zero", Collections.nCopies(0, required))
        val copiesMany by property("copies_many", Collections.nCopies(3, required))
        val sharedArray by property("shared_array", shared.asList())
        val single by property("single_wrap", listOf(required))
            .wrap<ConstraintLists, String?, Int?>({ error("Wrap conversion") }, { error("Unwrap conversion") })
        val double by property("double_wrap", listOf(required))
            .wrap<ConstraintLists, String?, Int?>({ error("Wrap conversion") }, { error("Unwrap conversion") })
            .wrap<ConstraintLists, Int?, Long?>({ error("Wrap conversion") }, { error("Unwrap conversion") })
    }

    class OpaqueConstraints(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<OpaqueConstraints>("DeclarationCaptureOpaqueConstraints") {
            val required = RequireIfConstraint<OpaqueConstraints, String?>(null) { error("Conditional predicate") }
            val directList = GuardedList<PropertyConstraint<String?>>(required)
            val backing = GuardedList<PropertyConstraint<String?>>(required)
            val delegating = Collections.unmodifiableList(backing)
        }

        val direct by XdNullableProperty<OpaqueConstraints, String>(String::class.java, "direct_db", directList)
        val single by XdNullableProperty<OpaqueConstraints, String>(String::class.java, "single_db", delegating)
            .wrap<OpaqueConstraints, String?, Int?>({ error("Wrap conversion") }, { error("Unwrap conversion") })
    }

    class WrappedConstraints(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<WrappedConstraints>("DeclarationCaptureWrappedConstraints") {
            val required = RequireIfConstraint<WrappedConstraints, String?>(null) { error("Conditional predicate") }
            val storageConstraints = GuardedList<PropertyConstraint<String?>>(required, Opaque())
        }

        val double by XdNullableProperty<WrappedConstraints, String>(String::class.java, null, storageConstraints)
            .wrap<WrappedConstraints, String?, Int?>({ error("Wrap conversion") }, { error("Unwrap conversion") })
            .wrap<WrappedConstraints, Int?, Long?>({ error("Wrap conversion") }, { error("Unwrap conversion") })
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
            val tokenConstraintsGetter = Probe()
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
        override fun isEmpty() = traversal.read(Unit).let { super.isEmpty() }
        override fun toArray(): Array<Any?> = traversal.read(Unit).let { super.toArray() }
        override fun <E> toArray(array: Array<E>): Array<E> = traversal.read(Unit).let { super.toArray(array) }
    }

    class DirectTokenProperty : XdConstrainedProperty<S, Token?>(
        null, S.tokenConstraints, XdPropertyRequirement.OPTIONAL, PRIMITIVE
    ), XdCustomTypeProperty<Token> {
        override val constraints get() = S.tokenConstraintsGetter.read(S.tokenConstraints)
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

    class Opaque(private val probe: Probe? = null) : PropertyConstraint<String?>() {
        override fun isValid(value: String?): Boolean = error("Opaque validator")
        override fun getExceptionMessage(propertyName: String, propertyValue: String?): String = error("Opaque message")
        override fun equals(other: Any?) = probe?.read(this === other) ?: (this === other)
        override fun hashCode() = probe?.read(System.identityHashCode(this)) ?: System.identityHashCode(this)
        override fun toString() = probe?.read("Opaque constraint") ?: super.toString()
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
            CONSTRAINTS.at(child, ConstraintDescriptors(), slot), CONDITIONAL_REQUIRED.at(child, false, slot),
            BINDING.at(child, DirectBindingFact(binding), slot))
    }

    private fun <T> withoutModelReads(vararg extra: Probe, action: () -> T): T {
        val probes = listOf(Root.nameProbe, S.nameProbe, S.indexesProbe, S.indexLists.traversal,
            S.firstIndex.traversal, S.tokenConstraintsGetter, S.tokenConstraints.traversal, S.tokenMetadata, S.tokenBinding) + extra
        val before = probes.map { it.reads }
        probes.forEach { it.armed = true }
        try {
            return action()
        } finally {
            probes.forEach { it.armed = false }
            assertEquals(before, probes.map { it.reads }, "Model probe counts")
        }
    }

    private fun <T> withRegisteredIndexes(vararg indexes: List<KProperty1<S, *>>, action: () -> T): T {
        val original = S.indexLists.toList()
        try {
            S.indexLists.clear()
            S.indexLists.addAll(indexes)
            return action()
        } finally {
            S.indexLists.clear()
            S.indexLists.addAll(original)
        }
    }

    @Test
    fun `capture uses proven defaults without supplied facts`() {
        val plain = XdModel.registerNode(Plain)
        val slot = plain.simpleProperties.getValue("javaEmpty")
        assertSame<List<*>>(Collections.emptyList<PropertyConstraint<String?>>(), slot.delegate.constraints)
        assertDeclaration(TypeDeclaration("DeclarationCapturePlain", false, emptyList(), NATURAL,
            scalars = listOf(scalar("DeclarationCapturePlain", "value"), scalar("DeclarationCapturePlain", "javaEmpty")),
            identity = identity), plain.captureTypeDeclaration())
        val failure = assertFailsWith<IllegalArgumentException> {
            plain.captureTypeDeclaration(CONSTRAINTS.at(plain, ConstraintDescriptors(opaque), slot))
        }
        assertEquals("Cannot override declaration fact constraints for ${Plain.javaClass.name}::javaEmpty", failure.message)
    }

    @Test
    fun `registered properties stay known after a reflection member cache rebuild`() {
        val node = XdModel.registerNode(PropertyOrigin)
        val slot = node.simpleProperties.getValue("value")
        val property = slot.property
        val delegate = slot.delegate
        val link = node.linkProperties.getValue("target")
        val ownerClass = PropertyOrigin::class
        assertSame(property, ownerClass.declaredMemberProperties.single { it.name == "value" })
        clearPropertyOriginMemberCache()
        val rebuilt = ownerClass.declaredMemberProperties.associateBy { it.name }
        val fresh = rebuilt.getValue("value")
        assertNotSame(property, fresh)
        assertNotSame(link.property, rebuilt.getValue("target"))
        assertSame(node, XdModel[PropertyOrigin])
        assertSame(slot, node.simpleProperties.getValue("value"))
        assertSame(property, node.simpleProperties.getValue("value").property)
        assertSame(delegate, node.simpleProperties.getValue("value").delegate)
        val sameTypeOtherNode = XdHierarchyNode(PropertyOrigin, null)

        withoutModelReads {
            fun assertValue() {
                val actual = node.captureTypeDeclaration().scalars.single { it.property.kotlinName == "value" }
                assertEquals(DeclarationPropertyId("DeclarationCapturePropertyOrigin", "value"), actual.property)
                assertEquals("String", actual.primitiveTypeName)
            }
            assertValue()
            assertTrue(node.hasRegisteredPropertyOrigin("target", link))
            for (fact in listOf(KOTLIN_NAME.at(node, "value", slot), LOGICAL_TYPE.at(node, LogicalScalarType("String"), slot))) {
                val failure = assertFailsWith<IllegalArgumentException> { node.captureTypeDeclaration(fact) }
                assertEquals("Cannot override declaration fact ${fact.kind.label} for ${PropertyOrigin.javaClass.name}::value", failure.message)
            }
            assertValue()

            fun missing(owner: XdHierarchyNode, key: String) {
                val failure = assertFailsWith<MissingDeclarationFact> { owner.captureTypeDeclaration() }
                assertEquals("Missing declaration fact Kotlin name for ${PropertyOrigin.javaClass.name}::$key", failure.message)
            }

            sameTypeOtherNode.simpleProperties["value"] = slot
            missing(sameTypeOtherNode, "value")

            try {
                node.simpleProperties["miskey"] = slot
                missing(node, "miskey")
            } finally {
                node.simpleProperties.remove("miskey")
            }
            try {
                node.simpleProperties["target"] = slot.copy(property = link.property)
                missing(node, "target") // Same node, key and reference as a registered link, but a scalar slot.
            } finally {
                node.simpleProperties.remove("target")
            }
            try {
                node.simpleProperties["value"] = slot.copy(property = rebuilt.getValue("other"))
                missing(node, "value")
                node.simpleProperties["value"] = slot.copy(property = fresh)
                assertValue() // Independent fresh-member proof.
                clearPropertyOriginMemberCache()
                val current = ownerClass.declaredMemberProperties.single { it.name == "value" }
                assertNotSame(fresh, current)
                assertEquals(current, fresh)
                missing(node, "value") // Equality does not make this stale replacement a proven reference.
                node.simpleProperties["value"] = slot.copy()
                assertValue() // Registration origin is not tied to the original slot object.
            } finally {
                node.simpleProperties["value"] = slot
            }
            assertValue()
        }
    }

    private fun clearPropertyOriginMemberCache() {
        // kotlin-reflect 2.1.0 layout. Clear only this fixture's actual declared instance member cache.
        val klass: Any = PropertyOrigin::class
        val data = (klass.javaClass.getMethod("getData").invoke(klass) as Lazy<*>).value!!
        val members = data.javaClass.getDeclaredField("declaredNonStaticMembers\$delegate")
            .apply { isAccessible = true }.get(data)
        val reference = members.javaClass.getDeclaredField("value").apply { isAccessible = true }.get(members) as SoftReference<*>
        checkNotNull(reference.get()) { "Expected a populated declared member cache" }
        reference.clear()
    }

    @Test
    fun `a distinct JVM return type does not obscure the audited index getter`() {
        val plain = XdModel.registerNode(Plain)
        val candidates = Plain.javaClass.methods.filter { it.name == "getCompositeIndices" && it.parameterCount == 0 }
        assertEquals(setOf(String::class.java to Plain.javaClass, List::class.java to XdNaturalEntityType::class.java),
            candidates.map { it.returnType to it.declaringClass }.toSet())
        assertEquals("summary", Plain.summaryProbe.observe { Plain.indexSummary() })

        withoutModelReads(Plain.summaryProbe) {
            assertEquals(emptyList(), plain.captureTypeDeclaration().indexes)
            for (indexes in listOf(ExplicitIndexFacts(), ExplicitIndexFacts(arrayOf("value")))) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    plain.captureTypeDeclaration(INDEXES.at(plain, indexes))
                }
                assertEquals("Cannot override declaration fact explicit indexes for ${Plain.javaClass.name}", failure.message)
            }
        }
    }

    @Test
    fun `inherited index database name evidence belongs to the declaring parent and current slot`() {
        val node = XdModel.registerNode(IndexChild)
        val declaring = checkNotNull(node.parentNode)
        val slot = declaring.simpleProperties.getValue("inherited")
        assertSame(IndexParent, declaring.entityType)
        assertTrue(node.simpleProperties.isEmpty())
        assertTrue(node.linkProperties.isEmpty())
        val probe = IndexParent.databaseNameProbe
        assertEquals("inherited_db", probe.observe { slot.delegate.dbPropertyName })
        val keys = arrayOf("inherited")
        val indexes = INDEXES.at(node, ExplicitIndexFacts(keys))
        val name = "inherited_db"

        withoutModelReads(probe) {
            fun missing(vararg facts: DeclarationFact<*>) {
                val failure = assertFailsWith<MissingDeclarationFact> { node.captureTypeDeclaration(indexes, *facts) }
                assertEquals("Missing declaration fact database name for ${IndexParent.javaClass.name}::inherited", failure.message)
            }
            missing()
            missing(DATABASE_NAME.at(node, name, slot))
            missing(DATABASE_NAME.at(declaring, name, slot.copy()))
            val result = node.captureTypeDeclaration(indexes, DATABASE_NAME.at(declaring, name, slot))
            keys[0] = "unregistered"
            assertTrue(result.scalars.isEmpty())
            val index = result.indexes.single()
            assertEquals("DeclarationCaptureIndexChild", index.owner)
            assertEquals(listOf(IndexFieldDeclaration("inherited_db", true)), index.fields)
            assertUnmodifiable(result.indexes)
            assertUnmodifiable(index.fields)
            missing()
        }
    }

    @Test
    fun `known conditional membership survives opaque storage constraints`() {
        val node = XdModel.registerNode(WrappedConstraints)
        val slot = node.simpleProperties.getValue("double")
        val list = WrappedConstraints.storageConstraints
        val iterator = list.traversal.observe { list.iterator() }
        assertSame(WrappedConstraints.required, iterator.next())
        assertEquals<Class<*>>(Opaque::class.java, iterator.next().javaClass)
        assertTrue(getPropertyConstraints(slot.delegate).isEmpty())

        withoutModelReads(list.traversal) {
            fun verifyOmission() {
                val failure = assertFailsWith<MissingDeclarationFact> { node.captureTypeDeclaration() }
                assertEquals("Missing declaration fact constraints for ${WrappedConstraints.javaClass.name}::double", failure.message)
            }
            verifyOmission()
            val descriptors = CONSTRAINTS.at(node, ConstraintDescriptors(conditional, opaque), slot)
            for (membership in arrayOf(false, true)) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    node.captureTypeDeclaration(descriptors, CONDITIONAL_REQUIRED.at(node, membership, slot))
                }
                assertEquals("Cannot override declaration fact conditional requiredness for ${WrappedConstraints.javaClass.name}::double",
                    failure.message)
            }
            val result = node.captureTypeDeclaration(descriptors)
            assertEquals(listOf(conditional, opaque), result.scalars.single().constraints)
            assertEquals(emptySet(), result.conditionalRequiredCandidates)
            verifyOmission()
        }
    }

    @Test
    fun `audited list families preserve effective facts without replacement evidence`() {
        val node = XdModel.registerNode(ConstraintLists)
        val probe = ConstraintLists.elementProbe
        val guarded = ConstraintLists.guarded
        probe.observe { guarded.equals(guarded) }
        probe.observe { guarded.hashCode() }
        probe.observe { guarded.toString() }
        assertFailsWith<IllegalStateException> { guarded.isValid(null) }
        assertFailsWith<IllegalStateException> { guarded.getExceptionMessage("list_of_one", null) }
        assertTrue(getPropertyConstraints(node.simpleProperties.getValue("double").delegate).isEmpty())

        val expected = mapOf(
            "arrayList" to listOf(opaque, subclassLength, opaque), "kotlinEmpty" to emptyList(),
            "kotlinZero" to emptyList(), "kotlinOne" to listOf(conditional),
            "kotlinMany" to listOf(subclassLength, conditional), "arraysZero" to emptyList(),
            "arraysMany" to listOf(conditional, opaque), "listOfZero" to emptyList(),
            "listOfOne" to listOf(opaque), "listOfTwo" to listOf(conditional, opaque),
            "listOfMany" to listOf(opaque, conditional, opaque), "javaEmpty" to emptyList(),
            "javaSingleton" to listOf(conditional), "copiesZero" to emptyList(),
            "copiesMany" to listOf(conditional, conditional, conditional), "sharedArray" to listOf(conditional, opaque),
            "single" to listOf(conditional), "double" to listOf(conditional)
        )
        val candidates = setOf("kotlin_one", "kotlin_many", "arrays_many", "list_of_two", "list_of_many",
            "java_singleton", "copies_many", "shared_array", "single_wrap")
        withoutModelReads(probe) {
            val snapshot = node.captureTypeDeclaration()
            assertEquals(expected, snapshot.scalars.associate { it.property.kotlinName to it.constraints })
            assertEquals(candidates, snapshot.conditionalRequiredCandidates)

            // listOfZero is the zero-argument Java List.of() case (RG-3).
            for ((key, conflicting) in listOf("listOfZero" to listOf(opaque), "listOfMany" to emptyList())) {
                val slot = node.simpleProperties.getValue(key)
                for (replacement in listOf(expected.getValue(key), conflicting)) {
                    val failure = assertFailsWith<IllegalArgumentException> {
                        node.captureTypeDeclaration(CONSTRAINTS.at(node, ConstraintDescriptors(*replacement.toTypedArray()), slot))
                    }
                    assertEquals("Cannot override declaration fact constraints for ${ConstraintLists.javaClass.name}::$key",
                        failure.message)
                }
            }
            for ((key, membership) in listOf("listOfZero" to false, "single" to true, "double" to false)) {
                for (replacement in arrayOf(membership, !membership)) {
                    val failure = assertFailsWith<IllegalArgumentException> {
                        node.captureTypeDeclaration(CONDITIONAL_REQUIRED.at(node, replacement, node.simpleProperties.getValue(key)))
                    }
                    assertEquals("Cannot override declaration fact conditional requiredness for ${ConstraintLists.javaClass.name}::$key",
                        failure.message)
                }
            }
            try {
                ConstraintLists.shared[0] = guarded
                val fresh = node.captureTypeDeclaration()
                assertEquals(expected + ("sharedArray" to listOf(opaque, opaque)),
                    fresh.scalars.associate { it.property.kotlinName to it.constraints })
                assertEquals(candidates - "shared_array", fresh.conditionalRequiredCandidates)
                assertEquals(expected, snapshot.scalars.associate { it.property.kotlinName to it.constraints })
                assertEquals(candidates, snapshot.conditionalRequiredCandidates)
            } finally {
                ConstraintLists.shared[0] = ConstraintLists.required
            }
        }
    }

    @Test
    fun `opaque direct and single wrapper evidence agrees with current registered slots`() {
        val node = XdModel.registerNode(OpaqueConstraints)
        val direct = node.simpleProperties.getValue("direct")
        val single = node.simpleProperties.getValue("single")
        val directList = OpaqueConstraints.directList
        val backing = OpaqueConstraints.backing
        assertSame<List<*>>(directList, getPropertyConstraints(direct.delegate))
        assertSame<List<*>>(OpaqueConstraints.delegating, getPropertyConstraints(single.delegate))
        for ((probe, list) in listOf(directList.traversal to directList, backing.traversal to OpaqueConstraints.delegating)) {
            assertEquals(1, probe.observe { list.size })
            assertSame(OpaqueConstraints.required, probe.observe { list[0] })
            assertSame(OpaqueConstraints.required, probe.observe { list.iterator() }.next())
            assertSame(OpaqueConstraints.required, probe.observe { ArrayList(list) }.single())
        }
        withoutModelReads(directList.traversal, backing.traversal) {
            fun missing(fact: String, key: String, vararg supplied: DeclarationFact<*>) {
                val failure = assertFailsWith<MissingDeclarationFact> { node.captureTypeDeclaration(*supplied) }
                assertEquals("Missing declaration fact $fact for ${OpaqueConstraints.javaClass.name}::$key", failure.message)
            }
            fun evidence(slot: XdHierarchyNode.SimpleProperty, descriptor: ConstraintDeclaration, membership: Boolean) =
                arrayOf(CONSTRAINTS.at(node, ConstraintDescriptors(descriptor), slot), CONDITIONAL_REQUIRED.at(node, membership, slot))

            val directFacts = evidence(direct, conditional, true)
            val singleFacts = evidence(single, conditional, true)
            missing("constraints", "direct")
            missing("constraints", "direct", CONSTRAINTS.at(child, ConstraintDescriptors(conditional), direct))
            missing("constraints", "direct", CONSTRAINTS.at(node, ConstraintDescriptors(conditional), direct.copy()))
            missing("conditional requiredness", "direct", directFacts[0])
            missing("constraints", "single", *directFacts)
            missing("conditional requiredness", "single", *directFacts, singleFacts[0])

            for ((descriptor, membership, message) in listOf(
                Triple(opaque, true, "Conditional requiredness needs a constraint descriptor"),
                Triple(conditional, false, "Conditional constraint descriptor needs conditional requiredness")
            )) {
                for (facts in listOf(evidence(direct, descriptor, membership) + singleFacts,
                    directFacts + evidence(single, descriptor, membership))) {
                    assertEquals(message, assertFailsWith<IllegalArgumentException> {
                        node.captureTypeDeclaration(*facts)
                    }.message)
                }
            }
            val snapshot = node.captureTypeDeclaration(*directFacts, *singleFacts)
            assertEquals(listOf(listOf(conditional), listOf(conditional)), snapshot.scalars.map { it.constraints })
            assertEquals(setOf("direct_db", "single_db"), snapshot.conditionalRequiredCandidates)
            try {
                directList[0] = Opaque()
                backing[0] = Opaque()
                val fresh = node.captureTypeDeclaration(*evidence(direct, opaque, false), *evidence(single, opaque, false))
                assertEquals(listOf(listOf(opaque), listOf(opaque)), fresh.scalars.map { it.constraints })
                assertEquals(emptySet(), fresh.conditionalRequiredCandidates)
                assertEquals(listOf(listOf(conditional), listOf(conditional)), snapshot.scalars.map { it.constraints })
                assertEquals(setOf("direct_db", "single_db"), snapshot.conditionalRequiredCandidates)
                missing("constraints", "direct")
                missing("constraints", "single", *evidence(direct, opaque, false))
            } finally {
                directList[0] = OpaqueConstraints.required
                backing[0] = OpaqueConstraints.required
            }
        }
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
            assertEquals(listOf("target", "required"), oldIndex.indexes.first())
            assertEquals(childIndexes, child.captureTypeDeclaration(*childFacts(oldIndex)).indexes)
            val current = withRegisteredIndexes(listOf(S::required, S::target)) {
                child.captureTypeDeclaration(*childFacts(ExplicitIndexFacts(firstFields)))
            }
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
    fun `unregistered composite field fails explicitly`() {
        withRegisteredIndexes(listOf(S::target, S::notRegistered)) {
            withoutModelReads {
                val badIndexes = ExplicitIndexFacts(arrayOf("target", "notRegistered"))
                val failure = assertFailsWith<IllegalArgumentException> { child.captureTypeDeclaration(*childFacts(badIndexes)) }
                assertEquals("Cannot build composite index by property DeclarationCaptureS::notRegistered", failure.message)
            }
        }
    }

    @Test
    fun `refusal and retry do not evaluate model metadata or traverse model collections`() {
        // The exact dispatch descriptors are bridges to effectful covariant overrides.
        for ((owner, getter) in listOf(S.javaClass to "getCompositeIndices", DirectTokenProperty::class.java to "getConstraints")) {
            val bridge = owner.methods.single { it.name == getter && it.parameterCount == 0 && it.returnType === List::class.java }
            assertSame(owner, bridge.declaringClass)
            assertTrue(bridge.isBridge && bridge.isSynthetic)
        }
        // Positive controls reach the real getter and both iterator acquisitions independently.
        val indexes = S.indexesProbe.observe { S.compositeIndices }
        assertSame(S.firstIndex, S.indexLists.traversal.observe { indexes.iterator() }.next())
        S.firstIndex.traversal.observe { S.firstIndex.iterator() }
        val delegate = child.simpleProperties.getValue("custom").delegate as DirectTokenProperty
        val constraints = S.tokenConstraintsGetter.observe { delegate.constraints }
        assertSame(S.tokenConstraints, constraints)
        S.tokenConstraints.traversal.observe { constraints.iterator() }
        assertSame(TrappingBinding, S.tokenBinding.observe { delegate.binding })

        withoutModelReads {
            fun verifyOmissions() {
                val supplied = childFacts()
                val childId = S.javaClass.name
                val slotId = "$childId::custom"
                val diagnostics = listOf(
                    "type name for ${Root.javaClass.name}", "type name for $childId", "explicit indexes for $childId",
                    "database name for $slotId", "scalar kind for $slotId", "auto-indexing for $slotId",
                    "requiredness for $slotId", "constraints for $slotId", "conditional requiredness for $slotId",
                    "direct binding for $slotId"
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
        }

        withRegisteredIndexes {
            S.bindingValue = null
            try {
                withoutModelReads {
                    val result = child.captureTypeDeclaration(*childFacts(ExplicitIndexFacts(), binding = null))
                    assertEquals(emptyList(), result.indexes)
                    assertNull(result.scalars.single { it.property.kotlinName == "custom" }.customType)
                }
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
            override fun equals(other: Any?) = probe.read(other === original.property)
            override fun hashCode() = probe.read(0)
            override fun toString() = probe.read("Custom reference")
        }
        assertEquals("custom", probe.observe { reference.name })
        probe.observe { reference.returnType }
        assertTrue(probe.observe { reference.equals(original.property) })
        probe.observe { reference.hashCode() }
        probe.observe { reference.toString() }
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

                val copy = slot.copy()
                child.simpleProperties["custom"] = copy
                val copyName = KOTLIN_NAME.at(child, "custom", copy)
                val copyType = LOGICAL_TYPE.at(child, LogicalScalarType("Token"), copy)
                for ((stale, current) in listOf(name to copyType, type to copyName)) {
                    val failure = assertFailsWith<MissingDeclarationFact> {
                        child.captureTypeDeclaration(*childFacts(), stale, current)
                    }
                    assertEquals("Missing declaration fact ${stale.kind.label} for ${S.javaClass.name}::custom", failure.message)
                }
                assertDeclaration(expectedChild(), child.captureTypeDeclaration(*childFacts(), copyName, copyType))
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
    private val conditional = ConstraintDeclaration(ConstraintKind.CUSTOM, "kotlinx.dnq.simple.RequireIfConstraint", CUSTOM)
}
