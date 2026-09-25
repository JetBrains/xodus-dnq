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
package kotlinx.dnq.linkConstraints

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.ReadonlyTransientEntity
import com.jetbrains.teamsys.dnq.database.TransientEntityImpl
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.EntityChangeType
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.TransientEntityChange
import jetbrains.exodus.database.TransientStoreSession
import jetbrains.exodus.database.TransientStoreSessionListener
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryCollector
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/** Compare the one-way raw absence proof with both real translated incoming query shapes. */
class IncomingDeletionReadTest : DBTest() {
    class Target(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Target>()
        var linkOnRemove by xdBooleanProp()
        override fun destructor() {
            if (linkOnRemove) ClearSource.new { target = this@Target }
        }
    }

    class FailSource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailSource>()
        var target by xdLink0_1(Target, onTargetDelete = OnDeletePolicy.FAIL)
    }

    class ClearSource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearSource>()
        var target by xdLink0_1(Target, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class CascadeSource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CascadeSource>()
        var target by xdLink0_1(Target, onTargetDelete = OnDeletePolicy.CASCADE)
    }

    class Counter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Counter>()
        var value by xdRequiredIntProp()
    }

    class OrphanParent(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<OrphanParent>()
        val children by xdChildren0_N(OrphanChild::parent)
    }

    class OrphanChild(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<OrphanChild>()
        var parent: OrphanParent by xdParent(OrphanParent::children)
    }

    class OrphanFailRef(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<OrphanFailRef>()
        var target by xdLink0_1(OrphanChild, onTargetDelete = OnDeletePolicy.FAIL)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Target, FailSource, ClearSource, CascadeSource, Counter,
            OrphanParent, OrphanChild, OrphanFailRef)
    }

    // internal is deliberately not widened to a public DNQ API for a test in another module.
    private fun absence(wrapper: TransientEntityImpl): Boolean {
        val method = TransientEntityImpl::class.java.declaredMethods.single {
            it.name.startsWith("hasNoIncomingLinksForDeletion") && it.parameterCount == 0
        }
        method.isAccessible = true
        return method.invoke(wrapper) as Boolean
    }

    private fun compare(target: Target, expectedEmpty: Boolean) {
        val session = (target.entity as TransientEntity).store.threadSessionOrThrow
        val txn = session.transactionInternal as YTDBStoreTransaction
        val raw = target.entity as TransientEntity
        // Snapshot the raw proof before either translated query can read this transaction.
        val rawEmpty = absence(target.entity as TransientEntityImpl)
        assertThat(rawEmpty).isEqualTo(expectedEmpty)
        // Exercise the original Gremlin paths, not just counts or a parallel hand-coded probe.
        val untyped = session.createPersistentEntityIterableWrapper(
            txn.findLinksUntyped(raw, "target")
        ).toList()
        val typed = listOf(FailSource, ClearSource, CascadeSource).flatMap { type ->
            session.findLinks(type.entityType, raw, "target").toList()
        }
        if (expectedEmpty) {
            assertThat(untyped).isEmpty()
            assertThat(typed).isEmpty()
        } else {
            assertThat(untyped).isNotEmpty()
            assertThat(typed).isNotEmpty()
        }
    }

    private fun incomingSince(before: Map<String, Int>): Int =
        GremlinQueryCollector.countSince(before) { "FollowLink" in it && "IN" in it && "target" in it }

    @Test fun `committed empty target skips both policy phases and validation`() {
        val target = transactional { Target.new() }
        transactional { compare(target, true) }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { target.delete() }
        assertThat(incomingSince(before)).isEqualTo(0)
        transactional { assertThat(Target.all().toList()).isEmpty() }
    }

    @Test fun `new and unflushed links reject absence and removed links restore it`() {
        transactional {
            val target = Target.new()
            compare(target, true)
            val source = FailSource.new { this.target = target }
            compare(target, false)
            source.target = null
            compare(target, true)
        }
        val target = transactional { Target.new() }
        transactional {
            val source = ClearSource.new { this.target = target }
            compare(target, false)
            source.target = null
            compare(target, true)
        }
    }

    @Test fun `readonly snapshot wrapper keeps original query dispatch`() {
        val target = transactional { Target.new() }
        transactional {
            val ordinary = target.entity as TransientEntityImpl
            assertThat(absence(ordinary)).isTrue()
            val snapshot = ReadonlyTransientEntity(ordinary.entity, ordinary.store)
            assertThat(absence(snapshot)).isFalse()
        }
    }

    @Test fun `before flush listener adds a FAIL source after empty deletion phases`() {
        val target = transactional { Target.new() }
        var added = false
        val listener = object : TransientStoreSessionListener {
            override fun beforeFlushBeforeConstraints(
                session: TransientStoreSession, changedEntities: Set<TransientEntityChange>
            ) {
                if (!added && changedEntities.any {
                    it.changeType == EntityChangeType.REMOVE && it.transientEntity.id == target.entityId
                }) {
                    added = true
                    // The Xd setter refuses to reattach a logically removed target. The
                    // transient entity API accepts its still-live vertex and tracks the link.
                    (FailSource.new().entity as TransientEntity).setLink("target", target.entity)
                }
            }
            override fun flushed(session: TransientStoreSession, changedEntities: Set<TransientEntityChange>) {}
            override fun afterConstraintsFail(
                session: TransientStoreSession, exceptions: Set<DataIntegrityViolationException>
            ) {}
        }
        store.addListener(listener)
        try {
            assertFailsWith<ConstraintsValidationException> { transactional { target.delete() } }
            assertThat(added).isTrue()
        } finally {
            store.removeListener(listener)
        }
        transactional {
            assertThat(Target.all().toList()).hasSize(1)
            assertThat(FailSource.all().toList()).isEmpty()
        }
    }

    @Test fun `populated FAIL target falls back and retains source and edge`() {
        val target = transactional { Target.new() }
        transactional { FailSource.new { this.target = target }; compare(target, false) }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        assertFailsWith<ConstraintsValidationException> { transactional { target.delete() } }
        assertThat(incomingSince(before)).isAtLeast(3)
        transactional {
            assertThat(Target.all().toList()).hasSize(1)
            assertThat(FailSource.all().toList().single().target).isEqualTo(target)
        }
    }

    @Test fun `direct transient deletion checks incoming FAIL at original validation`() {
        val target = transactional { Target.new() }
        val source = transactional { FailSource.new { this.target = target } }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        assertFailsWith<ConstraintsValidationException> {
            transactional { (target.entity as TransientEntity).delete() }
        }
        assertThat(incomingSince(before)).isAtLeast(1)
        transactional {
            assertThat(Target.all().toList()).containsExactly(target)
            assertThat(FailSource.all().toList()).containsExactly(source)
            assertThat(source.target).isEqualTo(target)
        }
    }

    @Test fun `orphan removal checks incoming FAIL at original validation`() {
        val (parent, child, source) = transactional {
            val parent = OrphanParent.new()
            val child = OrphanChild.new { this.parent = parent }
            val source = OrphanFailRef.new { target = child }
            Triple(parent, child, source)
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        assertFailsWith<ConstraintsValidationException> {
            transactional {
                parent.children.remove(child)
                // The orphan is still live here. removeOrphans marks it removed at validation.
                assertThat((child.entity as TransientEntity).isRemoved).isFalse()
            }
        }
        assertThat(incomingSince(before)).isAtLeast(1)
        transactional {
            assertThat(OrphanParent.all().toList()).containsExactly(parent)
            assertThat(OrphanChild.all().toList()).containsExactly(child)
            assertThat(OrphanFailRef.all().toList()).containsExactly(source)
            assertThat(parent.children.toList()).containsExactly(child)
            assertThat(child.parent).isEqualTo(parent)
            assertThat(source.target).isEqualTo(child)
        }
    }

    @Test fun `populated CLEAR and CASCADE preserve policies and persistent state`() {
        val target = transactional { Target.new() }
        transactional {
            ClearSource.new { this.target = target }
            CascadeSource.new { this.target = target }
            compare(target, false)
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional {
            target.delete()
            // After CASCADE clears its outgoing edge, check the raw proof before both queries.
            compare(target, true)
        }
        assertThat(incomingSince(before)).isAtLeast(2)
        transactional {
            assertThat(Target.all().toList()).isEmpty()
            assertThat(ClearSource.all().toList().single().target).isNull()
            assertThat(CascadeSource.all().toList()).isEmpty()
        }
    }

    @Test fun `retry validation sees winner's new FAIL reference`() {
        val (target, counter) = transactional { Target.new() to Counter.new { value = 0 } }
        val started = CyclicBarrier(2)
        val deleted = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        val winnerError = AtomicReference<Throwable?>()
        val loserError = AtomicReference<Throwable?>()
        val winner = thread {
            try {
                transactional {
                    started.await(30, TimeUnit.SECONDS)
                    counter.value = 1
                    check(deleted.await(30, TimeUnit.SECONDS))
                    FailSource.new { this.target = target }
                }
            } catch (failure: Throwable) {
                winnerError.set(failure)
            } finally {
                winnerCommitted.countDown()
            }
        }
        val loser = thread {
            try {
                transactional {
                    started.await(30, TimeUnit.SECONDS)
                    counter.value = 2
                    // The post-retry constraint failure triggers a second recovery replay.
                    // Its new wrapper must be healed so the original constraint error survives.
                    Counter.new { value = 3 }
                    try { target.delete() } finally { deleted.countDown() }
                    check(winnerCommitted.await(30, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) {
                loserError.set(failure)
            } finally {
                deleted.countDown()
            }
        }
        winner.join(45_000)
        loser.join(45_000)
        assertThat(winner.isAlive).isFalse()
        assertThat(loser.isAlive).isFalse()
        assertThat(winnerError.get()).isNull()
        assertThat(loserError.get()).isInstanceOf(ConstraintsValidationException::class.java)
        transactional {
            assertThat(Target.all().toList()).hasSize(1)
            assertThat(FailSource.all().toList().single().target).isEqualTo(target)
            assertThat(Counter.all().toList()).containsExactly(counter)
        }
    }

    @Test fun `real intermediate flush refreshes incoming proof after concurrent link commit`() {
        val (target, counter) = transactional { Target.new() to Counter.new { value = 0 } }
        val emptyObserved = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val writerSource = AtomicReference<FailSource?>()
        val readerError = AtomicReference<Throwable?>()
        val writerError = AtomicReference<Throwable?>()
        val reader = thread {
            try {
                transactional { session ->
                    // Keep this exact wrapper across the real commit and replacement transaction.
                    val wrapper = target.entity as TransientEntityImpl
                    assertThat(absence(wrapper)).isTrue()
                    val beforeFlushTx = session.transactionInternal
                    counter.value = 1 // An empty flush would leave the old transaction in place.
                    emptyObserved.countDown()
                    check(writerFinished.await(30, TimeUnit.SECONDS))
                    session.flush()

                    // Collect both original query results even if the raw answer is surprising.
                    val rawEmpty = absence(wrapper)
                    val txn = session.transactionInternal as YTDBStoreTransaction
                    val untypedIds = session.createPersistentEntityIterableWrapper(
                        txn.findLinksUntyped(wrapper, "target")
                    ).toList().map { it.id }
                    val typedIds = session.findLinks(
                        FailSource.entityType, wrapper, "target"
                    ).toList().map { it.id }

                    assertThat(session.transactionInternal).isNotSameInstanceAs(beforeFlushTx)
                    assertThat(rawEmpty).isFalse()
                    val sourceId = checkNotNull(writerSource.get()).entityId
                    assertThat(untypedIds).containsExactly(sourceId)
                    assertThat(typedIds).containsExactly(sourceId)
                }
            } catch (failure: Throwable) {
                readerError.set(failure)
            } finally {
                emptyObserved.countDown()
            }
        }
        val writer = thread {
            try {
                check(emptyObserved.await(30, TimeUnit.SECONDS))
                writerSource.set(transactional { FailSource.new { this.target = target } })
            } catch (failure: Throwable) {
                writerError.set(failure)
            } finally {
                writerFinished.countDown()
            }
        }
        reader.join(45_000)
        writer.join(45_000)
        assertThat(reader.isAlive).isFalse()
        assertThat(writer.isAlive).isFalse()
        assertThat(writerError.get()).isNull()
        assertThat(readerError.get()).isNull()
        transactional {
            assertThat(Target.all().toList()).containsExactly(target)
            assertThat(Counter.all().toList().single().value).isEqualTo(1)
            assertThat(FailSource.all().toList().single().target).isEqualTo(target)
        }
    }

    @Test fun `destructor adds an unflushed incoming source before phase reads`() {
        val target = transactional { Target.new { linkOnRemove = true } }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { target.delete() }
        assertThat(incomingSince(before)).isAtLeast(1)
        transactional {
            assertThat(Target.all().toList()).isEmpty()
            assertThat(ClearSource.all().toList().single().target).isNull()
        }
    }
}
