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
package kotlinx.dnq.query

import com.jetbrains.teamsys.dnq.database.PersistentEntityIterableWrapper
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityStore
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import org.junit.Test
import kotlin.test.*

/** Public queries use the transient wrapper. Gradle selects the MATCH mode for this suite. */
class ReverseLinkEqualityTest : DBTest() {
    class Target(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Target>()
        var name by xdRequiredStringProp()
    }
    open class Source(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Source>()
        var name by xdRequiredStringProp()
        var target by xdLink0_1(Target, onTargetDelete = CLEAR)
    }
    class ChildSource(entity: Entity) : Source(entity) {
        companion object : XdNaturalEntityType<ChildSource>()
    }
    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.registerNodes(Target, Source, ChildSource)
    }
    private fun <T : XdEntity> raw(query: XdQuery<T>) =
        (query.entityIterable as EntityIterable).unwrap() as YTDBEntityIterable

    /** Missing execution metadata keeps the same logical condition on its class-rooted baseline. */
    private fun <T : XdEntity> baseline(query: XdQuery<T>): XdQuery<T> {
        val source = raw(query)
        return PersistentEntityIterableWrapper(store, YTDBEntityIterable.query(
            store.persistentStore as YTDBEntityStore, source.query, source.polymorphic
        )).asQuery(query.entityType)
    }
    private fun <T : XdEntity> assertLogical(query: XdQuery<T>, link: String, target: XdEntity) {
        val source = raw(query)
        val expected = GremlinQuery.Labeled(GremlinQuery.Where(
            GremlinBlock.HasLinkTo(link, (target.entityId as YTDBEntityId).asOId())
        ), query.entityType.entityType)
        assertIs<PersistentEntityIterableWrapper>(query.entityIterable)
        assertEquals(expected, source.query)
        source.traversal().use { traversal ->
            val steps = traversal.asAdmin().bytecode.stepInstructions
            assertEquals(listOf((target.entityId as YTDBEntityId).asOId()), steps.first().arguments.toList())
            assertEquals(listOf("V", "hasLabel", "in", "hasLabel", "dedup"), steps.map { it.operator })
        }
        query.toList()
        assertEquals(expected, source.query)
    }
    private fun <T : XdEntity> assertMembers(query: XdQuery<T>, vararg expected: T) {
        repeat(2) {
            assertEquals(expected.toSet(), query.toList().toSet())
            assertEquals(expected.size, query.size())
            assertEquals(expected.isEmpty(), query.isEmpty)
            assertEquals(expected.isNotEmpty(), query.isNotEmpty)
            val reference = baseline(query)
            assertEquals(reference.toList().toSet(), query.toList().toSet())
            assertEquals(reference.size(), query.size())
            if (expected.size <= 1) {
                assertEquals(reference.firstOrNull(), query.firstOrNull())
                assertEquals(reference.lastOrNull(), query.lastOrNull())
            }
        }
    }
    private fun <R> assertNoExecution(tx: YTDBStoreTransactionImpl, construct: () -> R): R {
        var finished = 0
        val graphTx = (tx.g().graph as YTDBGraphInternal).tx()
        val listener = object : QueryMetricsListener {
            override fun queryFinished(details: QueryMetricsListener.QueryDetails, startedAtMillis: Long, executionTimeNanos: Long) {
                finished++
            }
        }
        graphTx.withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT).withQueryListener(listener)
        try {
            val result = construct()
            assertEquals(0, finished, "Construction must not execute a query")
            assertTrue(tx.activeYtdbSession().activeQueries.isEmpty())
            return result
        } finally {
            graphTx.withQueryListener(QueryMetricsListener.NO_OP)
        }
    }
    @Test fun `public entry points retain the logical condition and select a target rooted traversal`() {
        val pair = transactional {
            val target = Target.new { name = "target" }
            val source = Source.new { name = "source"; this.target = target }
            source to target
        }
        transactional { session ->
            val queries = assertNoExecution(session.transactionInternal as YTDBStoreTransactionImpl) {
                listOf(Source.query(Source::target eq pair.second), Source.filter { it.target eq pair.second })
            }
            for (query in queries) {
                assertLogical(query, "target", pair.second)
                assertMembers(query, pair.first)
            }
            val user = User.new { login = "user"; skill = 1 }
            val group = RootGroup.new { name = "root" }
            val collection = assertNoExecution(session.transactionInternal as YTDBStoreTransactionImpl) {
                Group.query(Group::users contains user)
            }
            assertLogical(collection, "users", user)
            assertMembers(collection)
            group.users.add(user)
            assertMembers(collection, group)
            group.users.remove(user)
            assertMembers(collection)
            assertTrue(user.isNew)
            assertTrue(group.isNew)
        }
    }
    @Test fun `lazy queries follow temporary RIDs and repeated link mutations without a flush`() {
        val stored = transactional {
            val first = Target.new { name = "first" }
            val second = Target.new { name = "second" }
            Triple(Source.new { name = "stored" }, first, second)
        }
        transactional { session ->
            val tracker = session.transientChangesTracker
            val freshTarget = Target.new { name = "fresh" }
            val freshSource = Source.new { name = "fresh source" }
            val targetRid = (freshTarget.entityId as YTDBEntityId).asOId()
            val sourceRid = (freshSource.entityId as YTDBEntityId).asOId()
            assertFalse(targetRid.isPersistent)
            assertFalse(sourceRid.isPersistent)
            val (fresh, first, second) = assertNoExecution(session.transactionInternal as YTDBStoreTransactionImpl) {
                Triple(Source.query(Source::target eq freshTarget), Source.filter { it.target eq stored.second },
                    Source.query(Source::target eq stored.third))
            }
            fun check(freshMembers: List<Source>, firstMembers: List<Source>, secondMembers: List<Source>) {
                assertMembers(fresh, *freshMembers.toTypedArray())
                assertMembers(first, *firstMembers.toTypedArray())
                assertMembers(second, *secondMembers.toTypedArray())
                assertSame(tracker, session.transientChangesTracker)
                assertTrue(freshTarget.isNew)
                assertTrue(freshSource.isNew)
                assertEquals(targetRid, (freshTarget.entityId as YTDBEntityId).asOId())
                assertEquals(sourceRid, (freshSource.entityId as YTDBEntityId).asOId())
                assertFalse(targetRid.isPersistent)
                assertFalse(sourceRid.isPersistent)
            }
            freshSource.target = freshTarget
            stored.first.target = freshTarget
            check(listOf(freshSource, stored.first), emptyList(), emptyList())
            freshSource.target = stored.second
            stored.first.target = stored.second
            check(emptyList(), listOf(freshSource, stored.first), emptyList())
            freshSource.target = stored.third
            stored.first.target = stored.third
            check(emptyList(), emptyList(), listOf(freshSource, stored.first))
            freshSource.target = null
            stored.first.target = null
            check(emptyList(), emptyList(), emptyList())
            freshSource.target = freshTarget
            check(listOf(freshSource), emptyList(), emptyList())
        }
    }
    @Test fun `query construction is lazy and observes links set in a later transaction`() {
        val pair = transactional {
            val target = Target.new { name = "target" }
            Source.new { name = "source" } to target
        }
        val queries = transactional { session ->
            assertNull(pair.first.target)
            assertNoExecution(session.transactionInternal as YTDBStoreTransactionImpl) {
                listOf(Source.query(Source::target eq pair.second), Source.filter { it.target eq pair.second })
            }
        }
        transactional {
            pair.first.target = pair.second
            for (query in queries) assertMembers(query, pair.first)
        }
    }
    @Test fun `subtype difference retains the unlinked subtype and the linked base group`() {
        val groups = transactional {
            val user = User.new { login = "user"; skill = 1 }
            val root = RootGroup.new { name = "root" }
            val linked = NestedGroup.new { name = "linked"; owner = user; parentGroup = root }
            val unlinked = NestedGroup.new { name = "unlinked"; owner = user; parentGroup = root }
            root.users.add(user)
            linked.users.add(user)
            listOf(root, linked, unlinked) to user
        }
        transactional { session ->
            val (root, linked, unlinked) = groups.first
            val result = Group.queryOf(root, linked, unlinked) exclude NestedGroup.query(NestedGroup::users contains groups.second)
            assertEquals(setOf(unlinked, root), result.toList().toSet())
            val source = raw(result)
            source.traversal().use { actual -> source.query.start((session.transactionInternal as YTDBStoreTransactionImpl).g()).use { reference ->
                assertEquals(reference.asAdmin().bytecode, actual.asAdmin().bytecode)
            } }
        }
    }
    @Test fun `wrapped linked sort preserves exact and polymorphic source types`() {
        val pair = transactional {
            val later = Target.new { name = "z" }
            val earlier = Target.new { name = "a" }
            Source.new { name = "base"; target = later } to ChildSource.new { name = "child"; target = earlier }
        }
        transactional {
            for ((polymorphic, expected) in listOf(false to listOf(pair.first), true to listOf(pair.second, pair.first))) {
                val source = Source.all(polymorphic = polymorphic)
                assertIs<PersistentEntityIterableWrapper>(source.entityIterable)
                val sorted = source.sortedBy(Source::target, Target::name)
                assertEquals(expected, sorted.toList())
                assertEquals(polymorphic, raw(sorted).polymorphic)
            }
        }
    }
    private fun deletionFixture() = transactional {
        val target = Target.new { name = "target" }
        Source.new { name = "source"; this.target = target } to target
    }
    private fun assertDeletionTerminals(query: XdQuery<Source>, member: Source) {
        val reference = baseline(query)
        // Capture exceptions too. A transiently removed source can reach a terminal wrapper.
        fun outcome(action: () -> Any?): Any? = try { action() } catch (failure: Exception) {
            failure.javaClass.name to failure.message
        }
        for ((name, terminal) in listOf<Pair<String, (XdQuery<Source>) -> Any?>>(
            "iteration" to { it.toList().map { entity -> entity.entityId }.toSet() },
            "size" to { it.size() }, "empty" to { it.isEmpty }, "nonempty" to { it.isNotEmpty },
            "first" to { it.firstOrNull()?.entityId }, "last" to { it.lastOrNull()?.entityId },
            "contains" to { it.contains(member) }, "indexOf" to { it.indexOf(member) }
        )) assertEquals(outcome { terminal(reference) }, outcome { terminal(query) }, name)
    }
    @Test fun `public source deletion agrees with each class rooted baseline terminal`() {
        val (source, target) = deletionFixture()
        val query = transactional { Source.query(Source::target eq target) }
        transactional {
            assertDeletionTerminals(query, source)
            source.delete()
            assertTrue(source.isRemoved)
            assertDeletionTerminals(query, source)
        }
        transactional { assertDeletionTerminals(query, source); assertMembers(query) }
    }
    @Test fun `public target deletion agrees with each class rooted baseline terminal`() {
        val (source, target) = deletionFixture()
        val query = transactional { Source.query(Source::target eq target) }
        transactional {
            assertDeletionTerminals(query, source)
            target.delete()
            assertTrue(target.isRemoved)
            assertDeletionTerminals(query, source)
        }
        transactional { assertDeletionTerminals(query, source); assertMembers(query) }
    }
}
