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
package jetbrains.exodus.entitystore.youtrackdb.query

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.YT
import jetbrains.exodus.entitystore.youtrackdb.gremlin.asYT
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.TextP
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.structure.T
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

/**
 * DNQ is the query library under test. It emits eligible mixed `AND` filters as sequential steps.
 * Only non-null equality and null-free in-list qualify as safe property filters here.
 * Safe property filters precede link checks so YouTrackDB can use its SQL (Structured Query Language)
 * engine and an index. These tests check executed plans, source work, membership, and multiplicity.
 *
 * The fixture has a case-insensitive email index and adds many unrelated contacts for source-work tests.
 * Contacts with the same email differ in verified status or user link. Some properties and links are absent.
 * Duplicate links test multiplicity. A contact subtype and sibling user subtypes test type restrictions.
 * A `RID` is a record identifier. The transaction test uses a real temporary user `RID`.
 *
 * MATCH is YouTrackDB's pattern query mode translated from Gremlin.
 * Each parameterized test sets the session flag explicitly to exercise MATCH enabled and disabled.
 */
@RunWith(Parameterized::class)
class MixedAndNativeSourceTest(private val match: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "MATCH={0}")
        fun modes() = listOf(arrayOf(true), arrayOf(false))
        private const val CONTACT = "XDContact"
        private const val USER = "XDUser"
        private const val EMAIL = "wanted@example.org"
        private const val INDEX = "idx_xd_contact_email"
    }

    @Rule @JvmField val db = InMemoryYouTrackDB(initializeIssueSchema = false)

    // Set this session's translation mode for every transaction, including fixture writes.
    private fun <R> inTx(action: (YTDBStoreTransactionImpl) -> R): R = db.withStoreTx { tx ->
        tx.activeYtdbSession().configuration!!.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, match)
        action(tx)
    }

    // Vary unrelated email values without increasing the selective indexed population.
    private fun fixture(unrelated: Int = 0) {
        db.withSession { session ->
            val contacts = session.getOrCreateVertexClass(CONTACT)
            contacts.createProperty("email", PropertyType.STRING).setCollate("ci")
            contacts.createProperty("verified", PropertyType.BOOLEAN)
            contacts.createIndex(INDEX, SchemaClass.INDEX_TYPE.NOTUNIQUE, "email")
            session.getOrCreateVertexClass("XDChildContact").addSuperClass(contacts)
            val users = session.getOrCreateVertexClass(USER)
            session.getOrCreateVertexClass("XDEmployee").addSuperClass(users)
            session.getOrCreateVertexClass("XDContractor").addSuperClass(users)
            session.schema.createEdgeClass("user_link")
            session.schema.createEdgeClass("targets_link")
        }
        inTx { tx ->
            val user = tx.g().addV(USER).property("name", "user").next()
            val other = tx.g().addV(USER).property("name", "other").next()
            tx.g().addV(USER).property("name", "nobody").iterate()
            fun contact(name: String, email: String?, verified: Boolean?, target: Vertex?, type: String = CONTACT): Vertex {
                val vertex = tx.g().addV(type).property("name", name).next()
                if (email != null) vertex.property("email", email)
                if (verified != null) vertex.property("verified", verified)
                if (target != null) vertex.addEdge("user_link", target)
                return vertex
            }
            contact("match", EMAIL, true, user)
            contact("multi", EMAIL, true, user).addEdge("user_link", user)
            contact("wrongVerified", EMAIL, false, user)
            contact("differentUser", EMAIL, true, other)
            contact("noLink", EMAIL, true, null)
            contact("missingEmail", null, true, user)
            contact("missingVerified", EMAIL, null, user)
            contact("wrongEmail", "elsewhere", true, user)
            contact("case", EMAIL.uppercase(), true, user)
            contact("child", EMAIL, true, user, "XDChildContact")
            repeat(unrelated) { contact("unrelated$it", "other$it@example.org", true, user) }
        }
    }

    private fun user(tx: YTDBStoreTransactionImpl, name: String): RID =
        tx.g().V().hasLabel(USER).has("name", name).next().id() as RID

    // Put the link check first to test whether emission exposes both safe property filters.
    private fun condition(rid: RID) = And(listOf(HasLinkTo("user", rid), PropEqual("email", EMAIL), PropEqual("verified", true)))
    // Keep the contact label after the condition to exercise normal provider source optimization.
    private fun query(tx: YTDBStoreTransactionImpl, block: GremlinBlock): YT =
        GremlinQuery.Where.of(block).then(HasLabel(CONTACT)).start(tx.g())

    /**
     * Reproduces the original link-first `and()` traversal independently of `GremlinBlock`.
     * Its own source excludes MATCH translation to preserve the native scan and membership baseline.
     * This reference detects result changes without using the emission rule under test.
     */
    private fun reference(tx: YTDBStoreTransactionImpl, rid: RID): YT = tx.g().withoutStrategies(GremlinToMatchStrategy::class.java).V().and(
        `__`.where<Vertex>(`__`.out("user_link").hasId(rid)),
        `__`.has<YTDBVertex>("email", EMAIL),
        `__`.has<YTDBVertex>("verified", true)
    ).hasLabel(CONTACT).asYT()

    private data class Measurement(val names: List<String>, val candidates: Int, val plans: List<String>, val containers: List<Pair<String, Any?>>, val matched: Boolean)
    /**
     * Captures executed plans and counts native source vertices before the residual link filter.
     * Full consumption measures native no-match work. `early` checks native source closure after the first result.
     * `allowMatch` permits no native source only when MATCH actually translates the query.
     * Assertions reject listener failures, empty plan capture, and incorrect source open or close counts.
     */
    private fun measure(tx: YTDBStoreTransactionImpl, traversal: YT, early: Boolean = false, allowMatch: Boolean = false): Measurement {
        val graphTx = (tx.g().graph as YTDBGraphInternal).tx()
        val listener = MixedAndPlanListener()
        graphTx.withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT).withQueryListener(listener)
        val probe = MixedAndSourceProbe(traversal, allowMatch)
        val names = mutableListOf<String>()
        try {
            if (early) {
                if (traversal.hasNext()) names += traversal.next().value<String>("name")
            } else {
                while (traversal.hasNext()) names += traversal.next().value<String>("name")
            }
        } finally {
            traversal.close()
            graphTx.withQueryListener(QueryMetricsListener.NO_OP)
        }
        assertTrue(listener.failures.isEmpty(), listener.failures.toString())
        assertTrue(listener.plans.isNotEmpty(), "Positive executed-plan capture is required")
        assertEquals(if (allowMatch && probe.matched) 0 else 1, probe.opened, "Exactly one native source must execute unless MATCH takes the query")
        assertEquals(probe.opened, probe.closed, "The native source must close, including early success")
        return Measurement(names, probe.candidates, listener.plans.toList(), probe.containers, probe.matched)
    }

    // Eligible measured filters must use the expected path and an executed email-index plan in both modes.
    private fun assertIndexedPath(measured: Measurement) {
        assertEquals(match, measured.matched, "Eligible measured filters must translate exactly when MATCH is enabled")
        assertIndexedSource(measured.plans)
        if (!measured.matched) {
            assertEquals(setOf("email", "verified", T.label.accessor), measured.containers.map { it.first }.toSet(), "Both safe scalar filters and the trailing type must join the native source")
        }
    }

    private fun assertIndexedSource(plans: List<String>) {
        assertTrue(plans.isNotEmpty(), "Positive executed-plan capture is required")
        // In these measured plans, the first fetch is the native source or the outer MATCH prefetch.
        // A nested anti-join fetch does not establish outer-source index use.
        for (plan in plans) {
            val source = plan.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("+ FETCH FROM ") }
            assertEquals("+ FETCH FROM INDEX $INDEX", source, "Executed outer source must use $INDEX: $plan")
        }
    }

    /** Rejects a class-scanning outer source even when the anti-join branch uses the email index. */
    @Test fun `indexed source assertion rejects class scan with indexed anti join`() {
        val plan = """
            + PREFETCH contact
              + FETCH FROM CLASS XDContact
              + FILTER ITEMS WHERE
                email = ? AND verified = ?
            + HASH ANTI_JOIN on [contact] (
              + SET contact AS
                + FETCH FROM INDEX idx_xd_contact_email
              )
        """.trimIndent()
        assertTrue(INDEX in plan, "The nested index must be present in the counterexample")
        assertFailsWith<AssertionError> { assertIndexedSource(listOf(plan)) }
    }

    // Check emitted steps before provider optimization can remove an unwanted and() wrapper.
    private fun assertNormalized(traversal: YT, linkOperator: String = "where") {
        traversal.use {
            val operators = it.asAdmin().bytecode.stepInstructions.map { step -> step.operator }
            assertFalse("and" in operators, "Eligible mixed AND must emit sequential filters: $operators")
            assertTrue(operators.indexOf("has") < operators.indexOf(linkOperator), "Scalars must precede link existence: $operators")
        }
    }

    /**
     * Queries email and verified equality with a user-link check that matches no contact.
     * The native reference scans 138 then 522 contacts. A normalized native source emits only six candidates.
     * Normalized MATCH execution instead requires an executed email-index plan, including after early success.
     * A native matching query must close its source after early success with at most six candidates.
     * This fails on a missing index plan, wrong execution path, growing native work, or an unclosed native source.
     */
    @Test fun `indexed native source bounds candidates and closes on early success`() {
        fixture(128)
        for (population in listOf(138, 522)) {
            if (population == 522) inTx { tx ->
                val target = tx.g().V(user(tx, "user")).next()
                repeat(384) { tx.g().addV(CONTACT).property("name", "more$it").property("email", "more$it").property("verified", true).next().addEdge("user_link", target) }
            }
            inTx { tx ->
                val rid = user(tx, "nobody")
                val original = measure(tx, reference(tx, rid))
                assertEquals(emptyList(), original.names)
                assertEquals(population, original.candidates, "Observer must see the class-wide reference population")
                assertTrue(original.plans.any { "FETCH FROM CLASS" in it }, original.plans.toString())
                val normalized = measure(tx, query(tx, condition(rid)), allowMatch = match)
                assertEquals(original.names, normalized.names)
                println("XD-1306 MATCH=$match population=$population translated=${normalized.matched} original=${original.candidates} normalized=${if (normalized.matched) "MATCH-plan-only" else normalized.candidates} plans=${normalized.plans}")
                assertIndexedPath(normalized)
                if (!normalized.matched) {
                    assertEquals(6, normalized.candidates, "Source work must stay within the selective population as unrelated data grows")
                    assertTrue(normalized.candidates * 10 < original.candidates)
                }
                val success = measure(tx, query(tx, condition(user(tx, "user"))), early = true, allowMatch = match)
                assertTrue(success.names.single() in listOf("case", "child", "match", "multi"))
                assertIndexedPath(success)
                if (!success.matched) assertTrue(success.candidates in 1..6)
            }
        }
    }

    /**
     * Compares link existence or absence with a literal link-first `and()` membership reference.
     * The native literal reference reproduces link-first semantics independently of `GremlinBlock`.
     * A separate no-match reference validates the observer against all 138 contacts.
     * Require an executed email-index plan and six candidates when a native source exists.
     * A translated MATCH query has no native source, so its executed index plan supplies the evidence.
     */
    private fun assertLinkFirstSource(noLink: Boolean) {
        fixture(128)
        inTx { tx ->
            val original = measure(tx, reference(tx, user(tx, "nobody")))
            assertEquals(138, original.candidates, "Observer must see the class-wide reference population")
            assertTrue(original.plans.any { "FETCH FROM CLASS" in it }, original.plans.toString())
            val literalLink = if (noLink) `__`.not<YTDBVertex>(`__`.out("user_link"))
                else `__`.where<Vertex>(`__`.out("user_link"))
            val expected = tx.g().withoutStrategies(GremlinToMatchStrategy::class.java).V().and(literalLink, `__`.has<YTDBVertex>("email", EMAIL),
                `__`.has<YTDBVertex>("verified", true)).hasLabel(CONTACT).use {
                it.toList().map { v -> v.value<String>("name") }.sorted()
            }
            val link = if (noLink) HasNoLink("user") else HasLink("user")
            val block = And(listOf(link, PropEqual("email", EMAIL), PropEqual("verified", true)))
            val normalized = measure(tx, query(tx, block), allowMatch = match)
            assertEquals(expected, normalized.names.sorted(), "Membership must match the literal link-first reference")
            println("XD-1306 MATCH=$match link=$link translated=${normalized.matched} reference=${original.candidates} candidates=${if (normalized.matched) "MATCH-plan-only" else normalized.candidates} members=${normalized.names.sorted()} plans=${normalized.plans}")
            assertIndexedPath(normalized)
            // MATCH has no native source. Its executed index plan is the positive evidence.
            if (!normalized.matched) {
                assertEquals(6, normalized.candidates, "Native source must stay within the selective population")
                assertTrue(normalized.candidates * 10 < original.candidates, "Native source must be well below the class-wide reference")
            }
            assertNormalized(query(tx, block), if (noLink) "not" else "where")
        }
    }

    /**
     * Combines `HasLink` with email and verified equality. Five named contacts must match the reference.
     * This fails if emission retains `and()` or execution scans the class instead of using the email index.
     */
    @Test fun `link first HasLink uses indexed source`() = assertLinkFirstSource(noLink = false)
    /**
     * Combines `HasNoLink` with email and verified equality. Only `noLink` must match the reference.
     * This fails if emission retains `and()` or the native or MATCH plan does not use the email index.
     */
    @Test fun `link first HasNoLink uses indexed source or MATCH plan`() = assertLinkFirstSource(noLink = true)

    /**
     * Permutes and nests the user-link, email, and verified filters.
     * Every order must return `case`, `child`, `match`, and `multi` once with an executed email-index plan.
     * Native execution must use a six-candidate source with both safe scalar filters and the trailing type.
     * Two copies of `multi` in the input must remain two copies despite its duplicate matching links.
     * This fails on a retained `and()`, order-dependent results, link-induced duplication, or deduplication.
     */
    @Test fun `six operand orders nested associations and repeated inputs preserve multisets`() {
        fixture()
        inTx { tx ->
            val rid = user(tx, "user")
            val expected = listOf("case", "child", "match", "multi")
            assertEquals(expected, reference(tx, rid).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            val operands = condition(rid).operands
            val permutations = operands.indices.flatMap { a -> operands.indices.filter { it != a }.map { b -> listOf(operands[a], operands[b], operands[3 - a - b]) } }
            for (ops in permutations) {
                val traversal = query(tx, And(ops))
                assertEquals(expected, traversal.use { it.toList().map { v -> v.value<String>("name") }.sorted() })
                assertNormalized(query(tx, And(ops)))
                val measured = measure(tx, query(tx, And(ops)), allowMatch = match)
                assertEquals(expected, measured.names.sorted())
                assertIndexedPath(measured)
                if (!measured.matched) assertEquals(6, measured.candidates)
            }
            for (nested in listOf(And(operands[0], And(operands[1], operands[2])), And(And(operands[0], operands[1]), operands[2]))) {
                assertEquals(expected, query(tx, nested).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
                assertNormalized(query(tx, nested))
            }
            val id = tx.g().V().hasLabel(CONTACT).has("name", "multi").next().id()
            val repeated = condition(rid).traverse(tx.g().inject(tx.g().V(id).next(), tx.g().V(id).next()).asYT())
            assertEquals(listOf("multi", "multi"), repeated.use { it.toList().map { v -> v.value<String>("name") } })
        }
    }

    /**
     * Applies the mixed filter to a real unflushed user and transaction-local contacts.
     * Only `temporaryMatch` must pass before flush, after flush, and after commit.
     * This fails if normalization loses temporary identity or transaction visibility, or retains `and()`.
     */
    @Test fun `temporary real user and transaction local contacts survive flush and commit`() {
        fixture()
        lateinit var rid: RID
        inTx { tx ->
            val target = tx.g().addV(USER).property("name", "temporary").next()
            val matching = tx.g().addV(CONTACT).property("name", "temporaryMatch").property("email", EMAIL).property("verified", true).next()
            matching.addEdge("user_link", target)
            tx.g().addV(CONTACT).property("name", "temporaryMismatch").property("email", EMAIL).property("verified", false).next().addEdge("user_link", target)
            rid = target.id() as RID
            assertTrue(rid.isNew, "User RID must remain genuinely unflushed immediately before execution")
            assertEquals(listOf("temporaryMatch"), query(tx, condition(rid)).use { it.toList().map { v -> v.value<String>("name") } })
            assertEquals(listOf("temporaryMatch"), reference(tx, rid).use { it.toList().map { v -> v.value<String>("name") } })
            tx.flush()
            rid = user(tx, "temporary")
            assertFalse(rid.isNew)
            assertEquals(listOf("temporaryMatch"), query(tx, condition(rid)).use { it.toList().map { v -> v.value<String>("name") } })
        }
        inTx { tx ->
            assertEquals(listOf("temporaryMatch"), query(tx, condition(user(tx, "temporary"))).use { it.toList().map { v -> v.value<String>("name") } })
            assertNormalized(query(tx, condition(rid)))
        }
    }

    /**
     * Combines link filters with equality, in-list, absence, not-equal, range, and text property filters.
     * Safe equality and in-list must return the four matching contacts with an executed email-index plan.
     * Native execution must use a six-candidate source with both safe scalar filters and the trailing type.
     * Other kinds must retain their asserted members and `and()` wrapper. Pure-property emission must stay unchanged.
     * This fails if safe filters stay hidden, unsupported kinds are chained, or scalar scope changes.
     */
    @Test fun `native predicate kinds and dedicated existence filters retain scalar scope`() {
        fixture()
        inTx { tx ->
            val rid = user(tx, "user")
            val expected = listOf("case", "child", "match", "multi")
            val native = listOf(
                PropEqual("verified", true) to expected,
                PropWithin("verified", listOf(true)) to expected
            )
            val fallback = listOf(
                PropEqual("verified", P.neq(false)) to expected,
                PropNull("verified") to listOf("missingVerified")
            )
            for ((scalar, members) in native + fallback) {
                val block = And(listOf(HasLinkTo("user", rid), PropEqual("email", EMAIL), scalar))
                assertEquals(members, query(tx, block).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            }
            for ((link, members) in listOf(
                HasLink("user") to listOf("case", "child", "differentUser", "match", "multi"),
                HasNoLink("user") to listOf("noLink")
            )) {
                val block = And(listOf(link, PropEqual("email", EMAIL), PropEqual("verified", true)))
                assertEquals(members, query(tx, block).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            }
            val residual = And(HasLinkTo("user", rid), PropInRange("name", "a", "z"))
            assertEquals(listOf("case", "child", "match", "missingEmail", "missingVerified", "multi", "wrongEmail", "wrongVerified"),
                query(tx, residual).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            val text = And(HasLinkTo("user", rid), MatchStringProp("email", StringCompare.Prefix, "wanted", false))
            assertTrue("and" in query(tx, text).use { it.asAdmin().bytecode.stepInstructions.map { step -> step.operator } })
            assertEquals(listOf("case", "child", "match", "missingVerified", "multi", "wrongVerified"),
                query(tx, text).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            assertEquals(listOf("V", "has", "has", "hasLabel"), query(tx, And(PropEqual("email", EMAIL), PropEqual("verified", true))).use { it.asAdmin().bytecode.stepInstructions.map { step -> step.operator } }, "Pure-property path stays unchanged")
            for ((scalar, members) in native) {
                val block = And(listOf(HasLinkTo("user", rid), PropEqual("email", EMAIL), scalar))
                assertNormalized(query(tx, block))
                val measured = measure(tx, query(tx, block), allowMatch = match)
                assertEquals(members, measured.names.sorted())
                assertIndexedPath(measured)
                if (!measured.matched) assertEquals(6, measured.candidates)
            }
            for ((scalar, _) in fallback) {
                assertEquals(listOf("V", "and", "hasLabel"), query(tx, And(listOf(HasLinkTo("user", rid), PropEqual("email", EMAIL), scalar))).use { it.asAdmin().bytecode.stepInstructions.map { step -> step.operator } })
            }
            assertEquals(listOf("V", "and", "hasLabel"), query(tx, residual).use { it.asAdmin().bytecode.stepInstructions.map { step -> step.operator } })
        }
    }

    /**
     * Tests both operand orders with explicit nulls, absent properties, and non-null values.
     * Each ineligible scalar must keep `and()` and match its own literal reference, not the other order.
     * This fails if null-sensitive filters move to native SQL, which gives different results for explicit nulls.
     * Presence, range, and text filters also check that unsupported kinds retain their emission and results.
     */
    @Test fun `null sensitive and other scalar kinds preserve residual emission and results`() {
        fixture()
        inTx { tx ->
            val target = tx.g().V(user(tx, "user")).next()
            for ((name, value) in listOf("explicitNull" to null, "setX" to "x", "setY" to "y")) {
                tx.g().addV(CONTACT).property("name", name).property("email", EMAIL).next().also {
                    it.property<Any?>("p", value)
                    it.addEdge("user_link", target)
                }
            }
            tx.g().addV(CONTACT).property("name", "absent").property("email", EMAIL).next().addEdge("user_link", target)
        }
        inTx { tx ->
            val explicit = tx.g().V().hasLabel(CONTACT).has("name", "explicitNull").next()
            assertTrue(explicit.property<Any?>("p").isPresent, "Fixture must retain a present explicit-null property after commit")
            assertNull(explicit.property<Any?>("p").value())
            val absent = tx.g().V().hasLabel(CONTACT).has("name", "absent").next()
            assertFalse(absent.property<Any?>("p").isPresent)
            val rid = user(tx, "user")
            val link = HasLinkTo("user", rid)
            val cases: List<Pair<GremlinBlock, () -> YT>> = listOf(
                PropEqual("p", null) to { `__`.has<YTDBVertex>("p", null).asYT() },
                PropEqual("p", P.neq("x")) to { `__`.has<YTDBVertex>("p", P.neq("x")).asYT() },
                PropEqual("p", P.neq<String>(null)) to { `__`.has<YTDBVertex>("p", P.neq<String>(null)).asYT() },
                PropWithin("p", listOf(null)) to { `__`.has<YTDBVertex>("p", P.within<Any>(listOf(null))).asYT() },
                PropWithin("p", listOf(null, "x")) to { `__`.has<YTDBVertex>("p", P.within<Any>(listOf(null, "x"))).asYT() },
                PropNull("verified") to { `__`.hasNot<YTDBVertex>("verified").asYT() },
                PropNotNull("p") to { `__`.has<YTDBVertex>("p").asYT() },
                PropInRange("p", "x", "z") to { `__`.has<YTDBVertex>("p", P.gte("x").and(P.lte("z"))).asYT() },
                MatchStringProp("p", StringCompare.Prefix, "x", false) to { `__`.has<YTDBVertex>("p", TextP.startingWith("x")).asYT() }
            )
            for ((scalar, literalScalar) in cases) {
                for (linkFirst in listOf(true, false)) {
                    val operands = if (linkFirst) listOf(link, PropEqual("email", EMAIL), scalar)
                        else listOf(scalar, PropEqual("email", EMAIL), link)
                    // Reproduce each order's original emission independently of GremlinBlock.
                    // Native and residual null behavior can differ between these unchanged operand orders.
                    val literal = if (linkFirst) tx.g().V().and(
                        `__`.where<Vertex>(`__`.out("user_link").hasId(rid)),
                        `__`.has<YTDBVertex>("email", EMAIL), literalScalar()
                    ).hasLabel(CONTACT) else tx.g().V().and(
                        literalScalar(), `__`.has<YTDBVertex>("email", EMAIL),
                        `__`.where<Vertex>(`__`.out("user_link").hasId(rid))
                    ).hasLabel(CONTACT)
                    val expected = literal.use { it.toList().map { v -> v.value<String>("name") }.sorted() }
                    val block = And(operands)
                    assertEquals(listOf("V", "and", "hasLabel"), query(tx, block).use { it.asAdmin().bytecode.stepInstructions.map { step -> step.operator } }, "Ineligible scalar must preserve the entire AND emission: $scalar")
                    assertEquals(expected, query(tx, block).use { it.toList().map { v -> v.value<String>("name") }.sorted() }, "Membership must match the literal reference: $scalar linkFirst=$linkFirst")
                    println("XD-1306 fallback MATCH=$match scalar=$scalar linkFirst=$linkFirst members=$expected")
                }
            }
        }
    }

    /**
     * Navigates from one contact to an employee, a plain user, and a sibling contractor.
     * A `T.label` predicate inside `And` must select only the employee under the outer user label.
     * Check membership and the wrapper to detect labels merged as alternatives after navigation.
     */
    private fun assertTokenLabelRestriction(within: Boolean) {
        fixture()
        lateinit var originId: RID
        inTx { tx ->
            val target = tx.g().V(user(tx, "user")).next()
            val origin = tx.g().addV(CONTACT).property("name", "origin").next()
            for ((type, name) in listOf("XDEmployee" to "employee", USER to "plainUser", "XDContractor" to "contractor")) {
                val vertex = tx.g().addV(type).property("name", name).next()
                origin.addEdge("targets_link", vertex)
                vertex.addEdge("user_link", target)
            }
            tx.flush()
            originId = origin.id() as RID
        }
        inTx { tx ->
            val scalar = if (within) PropWithin(T.label.accessor, listOf("XDEmployee"))
                else PropEqual(T.label.accessor, "XDEmployee")
            val guarded = GremlinQuery.ByIds(listOf(originId)).then(OutLink("targets"))
                .then(And(HasLink("user"), scalar)).then(HasLabel(USER))
            val names = guarded.start(tx.g()).use { it.toList().map { vertex -> vertex.value<String>("name") }.sorted() }
            println("XD-1306 token label MATCH=$match within=$within members=$names")
            assertEquals(listOf("employee"), names, "Inner Employee and outer User restrictions must reject the plain User and sibling Contractor")
            assertEquals(listOf("V", "out", "and", "hasLabel"), guarded.start(tx.g()).use {
                it.asAdmin().bytecode.stepInstructions.map { step -> step.operator }
            }, "A label-token operand must preserve the AND wrapper after navigation")
        }
    }

    /**
     * Combines link existence with `T.label` equality after navigation. Only the employee must pass.
     * This fails if the type filter moves next to the outer user label and admits sibling types.
     */
    @Test fun `label token equality preserves destination restriction`() = assertTokenLabelRestriction(within = false)
    /**
     * Combines link existence with a `T.label` in-list after navigation. Only the employee must pass.
     * This fails if the type filter moves next to the outer user label and admits sibling types.
     */
    @Test fun `label token in list preserves destination restriction`() = assertTokenLabelRestriction(within = true)

    /**
     * Combines each `T` token key with a link check in equality and in-list predicates.
     * Every shape must retain `and()`, even with an additional ordinary email predicate.
     * This fails if special element tokens qualify as safe vertex-property filters.
     */
    @Test fun `special token keys keep mixed conjunction fallback`() {
        fixture()
        inTx { tx ->
            for (token in T.values()) for (scalar in listOf(
                PropEqual(token.accessor, "value"), PropWithin(token.accessor, listOf("value"))
            )) {
                for (operands in listOf(listOf(HasLink("user"), scalar), listOf(scalar, HasLink("user"), PropEqual("email", EMAIL)))) {
                    assertEquals(listOf("V", "and", "hasLabel"), query(tx, And(operands)).use {
                        it.asAdmin().bytecode.stepInstructions.map { step -> step.operator }
                    }, "Special token ${token.accessor} is not a scalar property: $scalar")
                }
            }
        }
    }

    /**
     * Checks mixed filters inside `Or` and `Not`, plus opaque operands, navigation, and an ordered slice.
     * `Or` adds `wrongEmail` to the four matching contacts. `Not` returns the other five contacts.
     * Navigation must reject sibling types when restricted to employee.
     * The employee label after the slice must reject its first, non-employee target rather than change the slice.
     * This fails if normalization crosses a branch, removes an opaque wrapper, or moves destination labels.
     */
    @Test fun `nested boundaries residual scalars and destination labels remain scoped`() {
        fixture()
        inTx { tx ->
            val rid = user(tx, "user")
            val positive = condition(rid)
            val branch = And(HasLinkTo("user", rid), PropEqual("email", "elsewhere"))
            val nested = Or(positive, branch)
            assertEquals(listOf("case", "child", "match", "multi", "wrongEmail"), query(tx, nested).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            assertEquals(listOf("differentUser", "missingEmail", "missingVerified", "noLink", "wrongVerified"), query(tx, Not(nested)).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            for ((scalar, expected) in listOf(
                PropNull("email") to listOf("missingEmail"),
                PropNotNull("email") to listOf("case", "child", "match", "missingVerified", "multi", "wrongEmail", "wrongVerified"),
                PropWithin("email", listOf(EMAIL)) to listOf("case", "child", "match", "missingVerified", "multi", "wrongVerified")
            )) {
                assertEquals(expected, query(tx, And(HasLinkTo("user", rid), scalar)).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            }
            for (opaque in listOf(OutLink("user"), Limit(1), Sort(Sort.ByProp("name"), SortDirection.ASC), Where(OutLink("user")), Or(PropNull("email"), PropEqual("email", EMAIL)), Not(PropEqual("verified", true)))) {
                val block = And(HasLink("user"), opaque)
                assertEquals(listOf("V", "and", "hasLabel"), query(tx, block).asAdmin().bytecode.stepInstructions.map { it.operator }, "Opaque operands must keep their wrapper")
            }
            val origin = tx.g().addV(CONTACT).property("name", "origin").next()
            val employee = tx.g().addV("XDEmployee").property("name", "employee").next()
            val contractor = tx.g().addV("XDContractor").property("name", "contractor").next()
            origin.addEdge("targets_link", employee)
            origin.addEdge("targets_link", contractor)
            origin.addEdge("targets_link", tx.g().V().hasLabel(CONTACT).has("name", "match").next())
            employee.addEdge("user_link", tx.g().V(rid).next())
            contractor.addEdge("user_link", tx.g().V(rid).next())
            val destination = GremlinQuery.ByIds(listOf(origin.id() as RID)).then(OutLink("targets"))
            assertEquals(listOf("contractor", "employee"), destination.then(HasLabel(USER)).start(tx.g()).use { it.toList().map { v -> v.value<String>("name") }.sorted() })
            val guarded = destination.then(And(HasLinkTo("user", rid), HasLabel("XDEmployee"))).then(HasLabel(USER))
            assertTrue("and" in guarded.start(tx.g()).asAdmin().bytecode.stepInstructions.map { it.operator })
            assertEquals(listOf("employee"), guarded.start(tx.g()).use { it.toList().map { v -> v.value<String>("name") } }, "Outer User and inner Employee restrictions must reject the sibling Contractor")
            val sliced = destination.then(Sort(Sort.ByProp("name"), SortDirection.ASC)).then(Limit(1)).then(HasLabel("XDEmployee"))
            assertEquals(emptyList(), sliced.start(tx.g()).use { it.toList() }, "Type restriction stays outside the explicitly ordered slice")
            assertNormalized(query(tx, positive))
        }
    }
}
