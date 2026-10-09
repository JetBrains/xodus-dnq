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
package jetbrains.exodus.query

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.*
import jetbrains.exodus.entitystore.youtrackdb.withTx
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterableImpl
import jetbrains.exodus.entitystore.youtrackdb.query.*
import jetbrains.exodus.entitystore.youtrackdb.testutil.InMemoryYouTrackDB
import jetbrains.exodus.query.metadata.*
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

/** Exercises production lowering in both engines. Parallel raw edges have no unique pair index. */
@RunWith(Parameterized::class)
class ReverseLinkEqualityEngineTest(private val match: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "MATCH={0}")
        fun modes() = listOf(arrayOf(true), arrayOf(false))
    }
    @Rule @JvmField val db = InMemoryYouTrackDB(initializeIssueSchema = false)
    private val engine by lazy { QueryEngine(null, db.store) }
    private fun <R> inTx(action: (YTDBStoreTransactionImpl) -> R): R = db.withStoreTx { tx ->
        tx.activeYtdbSession().configuration!!.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, match)
        action(tx)
    }
    private fun fixture(indexed: Boolean = false) {
        db.withSession { s ->
            val types = listOf("Source", "ChildSource", "Target", "ChildTarget", "Foreign")
            s.createSequencesIfAbsent(listOf(YTDBVertexEntity.CLASS_ID_SEQUENCE_NAME) + types.map(YTDBVertexEntity::localEntityIdSequenceName))
            val source = s.schema.createVertexClass("Source")
            source.createProperty("rank", PropertyType.INTEGER)
            source.createIndex("rank_index", SchemaClass.INDEX_TYPE.NOTUNIQUE, "rank")
            s.schema.createVertexClass("ChildSource").addSuperClass(source)
            val target = s.schema.createVertexClass("Target")
            s.schema.createVertexClass("ChildTarget").addSuperClass(target)
            s.schema.createVertexClass("Foreign")
            for (type in types) s.setClassIdIfAbsent(s.schema.getClass(type))
            if (indexed) {
                s.initializeIndicesInTx(s.withTx { session -> listOf("Source", "Foreign").map {
                    session.addAssociation(LinkMetadata("target", it, "Target", AssociationEndCardinality._0_n), emptyList())
                }.merged() })
                s.assertAssociationExists("Source", "Target", "target", AssociationEndCardinality._0_n)
                s.checkIndex("target_link", true, "in", "out")
            } else s.schema.createEdgeClass("target_link")
        }
        inTx { tx ->
            val target = tx.newEntity("ChildTarget").apply { setProperty("name", "target") }
            tx.newEntity("ChildTarget").setProperty("name", "empty")
            val targetVertex = tx.g().V((target.id as YTDBEntityId).asOId()).use { it.next() }
            for ((type, name, rank) in listOf(Triple("Source", "a", 2), Triple("Source", "b", 1),
                Triple("Source", "c", null), Triple("ChildSource", "child", 0), Triple("Foreign", "foreign", 3))) {
                val source = tx.newEntity(type).apply { setProperty("name", name); if (rank != null) setProperty("rank", rank) }
                val v = tx.g().V((source.id as YTDBEntityId).asOId()).use { it.next() }
                v.addEdge("target_link", targetVertex)
                if (!indexed && name == "a") v.addEdge("target_link", targetVertex)
            }
        }
    }
    private fun target(tx: YTDBStoreTransactionImpl, name: String = "target") =
        tx.find("ChildTarget", "name", name).first!!
    private fun lookup(target: Entity, polymorphic: Boolean = true): YTDBEntityIterable =
        NodeFactory.hasLinkTo("target", target).instantiate("Source", engine, null, polymorphic) as YTDBEntityIterable
    private fun names(query: EntityIterable) = query.map { it.getProperty("name") as String }
    private fun measure(tx: YTDBStoreTransactionImpl, traversal: GraphTraversal<*, *>) =
        measureLinkAccess(tx, traversal) { it.toList().map { v -> (v as YTDBVertex).value<String>("name") } }
    private fun assertRoot(access: ReverseLinkAccess<*>, target: Entity) {
        println("XD-1307 MATCH=$match translated=${access.matched} sourceIds=${access.sourceIds} candidates=${access.candidates} steps=${access.steps} plans=${access.plans}")
        if (access.matched) {
            assertTrue(access.plans.any { Regex("FETCH FROM RIDs\\s*\\[${Regex.escape((target.id as YTDBEntityId).asOId().toString())}\\]").containsMatchIn(it) && "DISTINCT" in it && "in(\"target_link\")" in it }, access.plans.toString())
        } else {
            assertEquals(listOf((target.id as YTDBEntityId).asOId()), access.sourceIds)
            assertEquals(1, access.candidates)
            assertTrue("VertexStep(IN,[target_link],vertex)" in access.steps, access.steps)
        }
    }
    @Test fun `standalone roots preserve types distinct consumers and protected sorted limits`() {
        fixture()
        inTx { tx ->
            val target = target(tx)
            val query = lookup(target)
            val logical = GremlinQuery.Labeled(GremlinQuery.Where(HasLinkTo("target", (target.id as YTDBEntityId).asOId())), "Source")
            assertEquals(logical, query.query)
            val full = measure(tx, query.traversal())
            assertEquals(match, full.matched)
            assertRoot(full, target)
            assertEquals(listOf("a", "b", "c", "child"), full.result.sorted())
            assertEquals(listOf("a", "b", "c"), names(lookup(target, false)).sorted())
            assertEquals(4L, query.size())
            assertEquals(4L, query.count())
            assertFalse(query.isEmpty)
            assertRoot(measureLinkAccess(tx, query.traversal().count()) { it.next() }, target)
            assertRoot(measure(tx, query.traversal().limit(1)), target)
            assertRoot(measure(tx, (query.distinct().skip(1).take(2) as YTDBEntityIterable).traversal()), target)
            assertEquals(2, names(query.skip(1).take(2)).distinct().size)
            assertRoot(measure(tx, YTDBEntityIterable.query(db.store, query.query.then(Tail(2)), linkTargets = (query as YTDBEntityIterableImpl).linkTargets).traversal()), target)
            assertEquals(names(query).reversed(), names(query.reverse()))
            val sorted = tx.sort("Source", "rank", query, true, true)
            val sortedLogical = sorted.query
            assertEquals(listOf("child", "b", "a", "c"), names(sorted))
            assertRoot(measure(tx, sorted.traversal()), target)
            assertEquals(listOf("a", "b", "child", "c"), names(tx.sort("Source", "rank", query, false, true)))
            for ((consumer, expected) in listOf<Pair<() -> Any, Any>>({ sorted.count() } to 4L, { sorted.isEmpty } to false)) {
                val access = measureLinkTerminal(tx, consumer)
                assertEquals(expected, access.result)
                assertRoot(access, target)
            }
            for (page in listOf(sorted.take(1), sorted.skip(1), sorted.skip(1).take(2),
                YTDBEntityIterable.query(db.store, sorted.query.then(Tail(2)), linkTargets = (sorted as YTDBEntityIterableImpl).linkTargets))) {
                val wrapped = page as YTDBEntityIterable
                val before = wrapped.query
                wrapped.traversal().use { actual -> before.start(tx.g()).use { assertEquals(it.asAdmin().bytecode, actual.asAdmin().bytecode) } }
                val access = measure(tx, wrapped.traversal())
                assertEquals(before, wrapped.query)
                assertFalse(access.sourceIds == listOf((target.id as YTDBEntityId).asOId()))
                assertTrue(access.plans.isNotEmpty())
                val reference = before.start(tx.g()).use { it.toList().map { v -> v.value<String>("name") } }
                assertEquals(reference, access.result)
                for (consumer in listOf<() -> Any>({ page.count() }, { page.isEmpty })) {
                    val terminal = measureLinkTerminal(tx, consumer)
                    assertFalse(terminal.sourceIds == listOf((target.id as YTDBEntityId).asOId()))
                    assertTrue(terminal.plans.isNotEmpty() && terminal.plans.none { "FETCH FROM RIDs" in it }, terminal.plans.toString())
                    assertEquals(if (terminal.result is Long) reference.size.toLong() else reference.isEmpty(), terminal.result)
                }
            }
            for ((terminal, expected) in listOf({ sorted.first } to "child", { sorted.last } to "c")) {
                val (value, plans) = captureLinkPlans(tx, terminal)
                assertEquals(expected, value!!.getProperty("name"))
                assertTrue(plans.any { Regex("FETCH FROM INDEX( VALUES (ASC|DESC))? rank_index").containsMatchIn(it) || "FETCH FROM CLASS Source" in it }, plans.toString())
            }
            assertEquals(sortedLogical, sorted.query)
            for ((page, size) in listOf(query.reverse() to 4, query.distinct().take(4) to 4, query.skip(1).take(2) to 2,
                query.reverse().distinct().skip(1).take(2) to 2)) {
                val wrapped = page as YTDBEntityIterable
                val before = wrapped.query
                val access = measure(tx, wrapped.traversal())
                assertRoot(access, target)
                assertEquals(size, access.result.size)
                assertEquals(size, access.result.toSet().size)
                assertTrue(full.result.containsAll(access.result))
                assertEquals(before, wrapped.query)
            }
            for (terminal in listOf({ query.first }, { query.last })) {
                val access = measureLinkTerminal(tx, terminal)
                assertRoot(access, target)
                assertTrue(access.result!!.getProperty("name") in full.result)
            }
            assertEquals(logical, query.query)
            val empty = lookup(target(tx, "empty"))
            assertTrue(empty.isEmpty)
            assertNull(empty.first)
            assertNull(empty.last)
            assertEquals(0L, empty.size())
            assertRoot(measure(tx, empty.traversal()), target(tx, "empty"))
            assertEquals(logical.copy(inner = GremlinQuery.Where(HasLinkTo("target", (target(tx, "empty").id as YTDBEntityId).asOId()))), empty.query)
        }
    }
    @Test fun `final shape permits reduced equal operands and rejects unsupported roots`() {
        fixture(indexed = true)
        inTx { tx ->
            val target = target(tx)
            val node = NodeFactory.hasLinkTo("target", target)
            val query = lookup(target)
            for (eligible in listOf(engine.query("Source", node.clone), engine.query(engine.queryGetAll("Source"), "Source", node),
                engine.query("Source", NodeFactory.or(node.clone, node.clone)), engine.query("Source", NodeFactory.and(node.clone, node.clone)),
                query.union(lookup(target)), query.intersect(lookup(target)), engine.query(tx.sort("Source", "rank", true), "Source", node.clone))) {
                val result = eligible as YTDBEntityIterable
                assertEquals(query.query, result.query.withoutResultOrder())
                assertRoot(measure(tx, result.traversal()), target)
                assertEquals(listOf("a", "b", "c", "child"), names(result).sorted())
            }
            assertEquals(listOf("a", "b", "c"), names(lookup(target, false)).sorted())
            assertEquals(4L, query.count())
            assertEquals(5, tx.g().V((target.id as YTDBEntityId).asOId()).`in`("target_link").use { it.toList().size })
            val rid = (target.id as YTDBEntityId).asOId()
            val unresolved = LeafNode(HasLinkTo("target", rid), RIDEntityId(Int.MAX_VALUE, 1, rid, null))
            val mismatch = YTDBEntityIterable.query(db.store, query.query, linkTargets = mapOf(rid to target(tx, "empty").id as YTDBEntityId))
            val before = mismatch.query
            mismatch.traversal().use { actual -> before.start(tx.g()).use { assertEquals(it.asAdmin().bytecode, actual.asAdmin().bytecode) } }
            assertEquals(listOf("a", "b", "c", "child"), names(mismatch).sorted())
            assertEquals(before, mismatch.query)
            for (fallback in listOf(LeafNode(HasLinkTo("target", rid)), unresolved, NodeFactory.hasLinkTo("target", null),
                NodeFactory.not(node.clone), NodeFactory.and(node.clone, NodeFactory.propEqual("name", "a")),
                NodeFactory.or(node.clone, NodeFactory.propEqual("name", "nobody")), NodeFactory.nested("target", node.clone))) {
                val result = engine.query("Source", fallback) as YTDBEntityIterable
                val before = result.query
                result.traversal().use { actual -> before.start(tx.g()).use { assertEquals(it.asAdmin().bytecode, actual.asAdmin().bytecode) } }
                assertEquals(before.start(tx.g()).use { it.toList().map { v -> v.value<String>("name") }.sorted() }, names(result).sorted())
                assertEquals(before, result.query)
            }
            for (receiver in listOf(tx.getAll("Source").take(1), query.selectMany("target"), tx.sort("Source", "rank", true).take(2),
                YTDBEntityIterable.single(db.store, query.first!!.id))) {
                val result = engine.query(receiver, "Source", node.clone) as YTDBEntityIterable
                result.traversal().use { actual -> result.query.start(tx.g()).use { reference ->
                    assertEquals(reference.asAdmin().bytecode, actual.asAdmin().bytecode, "Unsupported final shape must keep its reference lowering")
                } }
                assertEquals(result.query.start(tx.g()).use { it.toList().map { v -> v.value<String>("name") }.sorted() }, names(result).sorted())
            }
        }
    }
    @Test fun `composition preserves scalar index and direct source RID plans`() {
        fixture()
        (0 until 120).toList().chunked(100).forEach { chunk ->
            inTx { tx -> chunk.forEach { tx.newEntity("Source").apply { setProperty("name", "unrelated$it"); setProperty("rank", 99) } } }
        }
        inTx { tx ->
            val target = target(tx)
            val query = lookup(target)
            val member = tx.find("Source", "name", "a").first!!
            val nonmember = tx.find("Source", "name", "unrelated0").first!!
            val filtered = engine.query(query, "Source", NodeFactory.propEqual("rank", 2)) as YTDBEntityIterable
            val indexed = measure(tx, filtered.traversal())
            assertEquals(listOf("a"), indexed.result)
            assertTrue(indexed.plans.any { "FETCH FROM INDEX rank_index" in it }, indexed.plans.toString())
            assertFalse(indexed.sourceIds == listOf((target.id as YTDBEntityId).asOId()))
            filtered.traversal().use { actual -> filtered.query.start(tx.g()).use { reference ->
                assertEquals(reference.asAdmin().bytecode, actual.asAdmin().bytecode)
                assertEquals(listOf("V", "has", "where", "hasLabel"), actual.asAdmin().bytecode.stepInstructions.map { it.operator })
            } }
            val ids = listOf(member, nonmember).map { (it.id as YTDBEntityId).asOId() }
            val byIds = YTDBEntityIterable.query(db.store, GremlinQuery.ByIds(ids))
            for (intersection in listOf(query.intersect(byIds), byIds.intersect(query))) {
                val access = measure(tx, (intersection as YTDBEntityIterable).traversal())
                assertEquals(listOf("a"), access.result)
                if (access.matched) assertTrue(access.plans.any { "FETCH FROM RIDs" in it && ids.all { id -> id.toString() in it } }, access.plans.toString())
                else {
                    assertEquals(ids, access.sourceIds)
                    assertEquals(2, access.candidates)
                }
            }
            for ((entity, expected) in listOf(member to true, nonmember to false)) {
                val access = measureLinkTerminal(tx) { query.contains(entity) }
                assertEquals(expected, access.result)
                val rid = (entity.id as YTDBEntityId).asOId()
                println("XD-1307 direct membership MATCH=$match entity=$rid access=$access")
                if (access.matched) {
                    assertTrue(access.plans.any { Regex("FETCH FROM RIDs\\s*\\[${Regex.escape(rid.toString())}\\]").containsMatchIn(it) && "out(\"target_link\")" in it }, access.plans.toString())
                    assertTrue(access.plans.none { "FETCH FROM CLASS Source" in it }, access.plans.toString())
                } else {
                    assertEquals(listOf(rid), access.sourceIds)
                    assertEquals(1, access.candidates)
                    assertTrue("VertexStep(OUT,[target_link],vertex)" in access.steps, access.steps)
                }
            }
            val page = query.take(4)
            for ((entity, expected) in listOf(member to true, nonmember to false)) {
                val access = measureLinkTerminal(tx) { page.contains(entity) }
                assertEquals(expected, access.result)
                assertRoot(access, target)
            }
            val order = names(query)
            for (entity in listOf(member, nonmember)) {
                val access = measureLinkTerminal(tx) { query.indexOf(entity) }
                assertEquals(order.indexOf(entity.getProperty("name")), access.result)
                assertRoot(access, target)
            }
            val sortedPage = tx.sort("Source", "rank", query, true, true).take(2)
            for ((entity, expected) in listOf(member to false, tx.find("Source", "name", "b").first!! to true)) {
                val access = measureLinkTerminal(tx) { sortedPage.contains(entity) }
                assertEquals(expected, access.result)
                assertTrue(access.plans.isNotEmpty() && access.plans.none { "FETCH FROM RIDs" in it }, access.plans.toString())
            }
            assertEquals(listOf("a", "b", "c", "child"), names(query).sorted())
        }
    }
    @Test fun `SortEngine linked sort preserves exact and polymorphic source types`() {
        fixture()
        inTx { tx ->
            for (name in listOf("b", "c")) tx.deleteEntity(tx.find("Source", "name", name).first!!.id)
            tx.find("ChildSource", "name", "child").first!!.setLink("target", target(tx, "empty"))
        }
        inTx { tx ->
            val sorter = SortEngine(engine)
            for ((polymorphic, expected) in listOf(false to listOf("a"), true to listOf("child", "a"))) {
                val source = engine.queryGetAll("Source", polymorphic)
                val sorted = sorter.sort("Target", "name", "Source", "target", source, true) as YTDBEntityIterable
                assertEquals(expected, names(sorted))
                assertEquals(polymorphic, sorted.polymorphic)
            }
        }
    }
    @Test fun `deleted target RID keeps all terminals empty before and after commit`() {
        fixture()
        fun checkEmpty(tx: YTDBStoreTransactionImpl, stale: YTDBEntityIterable) {
            val logical = stale.query
            assertEquals(emptyList(), names(stale))
            assertEquals(emptyList(), logical.start(tx.g()).use { it.toList() })
            assertEquals(0L, stale.size())
            assertEquals(0L, stale.count())
            assertTrue(stale.isEmpty)
            assertNull(stale.first)
            assertNull(stale.last)
            assertEquals(logical, stale.query)
        }
        val stale = inTx { tx -> lookup(target(tx)).also { tx.deleteEntity(target(tx).id); checkEmpty(tx, it) } }
        inTx { tx -> checkEmpty(tx, stale) }
    }
    @Test fun `target source stays fixed while unrelated population and adjacency grow separately`() {
        fixture()
        var previous = 3
        // MatchExecutionPlanner.THRESHOLD=100 permits small-class prefetch. Only the large case rejects it.
        for (population in listOf(3, 67, 151)) {
            (previous until population).toList().chunked(100).forEach { chunk ->
                inTx { tx -> chunk.forEach { tx.newEntity("Source").setProperty("name", "unrelated$it") } }
            }
            previous = population
            inTx { tx ->
                val target = target(tx)
                val query = lookup(target)
                val logical = query.query
                val access = measure(tx, query.traversal())
                assertEquals(match, access.matched)
                assertRoot(access, target)
                val (rows, plans) = captureLinkPlans(tx) { logical.start(tx.g()).use { it.toList().map { v -> v.value<String>("name") } } }
                assertTrue(plans.any { "FETCH FROM CLASS Source" in it }, plans.toString())
                if (population >= 150 && match) assertTrue(access.plans.none { "FETCH FROM CLASS Source" in it }, access.plans.toString())
                if (!match) {
                    val scan = measure(tx, logical.start(tx.g().withoutStrategies(GremlinToMatchStrategy::class.java)))
                    assertEquals(population + 1, scan.candidates, "Reference includes the source subclass")
                    assertEquals(emptyList(), scan.sourceIds)
                }
                assertEquals(listOf("a", "b", "c", "child"), access.result.sorted())
                assertEquals(rows.sorted(), access.result.sorted())
                assertEquals(logical, query.query)
                val adjacency = tx.g().V((target.id as YTDBEntityId).asOId()).`in`("target_link").use { it.toList().size }
                assertEquals(6, adjacency, "Controlled rows include a duplicate and a foreign source")
            }
        }
        inTx { tx ->
            val target = target(tx)
            repeat(8) { tx.newEntity("Source").apply { setProperty("name", "linked$it"); addLink("target", target) } }
            repeat(7) { tx.newEntity("Foreign").apply { setProperty("name", "foreign$it"); addLink("target", target) } }
            assertRoot(measure(tx, lookup(target).traversal()), target)
            assertEquals(12L, lookup(target).size())
            assertEquals(21, tx.g().V((target.id as YTDBEntityId).asOId()).`in`("target_link").use { it.toList().size })
        }
    }
}
