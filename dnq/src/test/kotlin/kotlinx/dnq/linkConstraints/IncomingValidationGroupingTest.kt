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
import com.jetbrains.teamsys.dnq.database.threadSessionOrThrow
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryCollector
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.FAIL
import kotlinx.dnq.link.OnDeletePolicy.FAIL_PER_ENTITY
import kotlinx.dnq.link.OnDeletePolicy.FAIL_PER_TYPE
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/**
 * Deletion validation reads incoming links once per link name and dispatches the sources to the
 * `(source type, link name)` pairs in memory. The reported violations must be the ones the
 * one-typed-read-per-pair validation produced.
 */
class IncomingValidationGroupingTest : DBTest() {

    // ---- three unrelated source types share the link name "target" -----------

    class SharedTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SharedTarget>()
    }

    class RefP(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RefP>()
        var tag by xdStringProp()
        var target by xdLink0_1(SharedTarget, onTargetDelete = FAIL_PER_ENTITY { "P-${it.toXd<RefP>().tag}" })
    }

    class RefQ(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RefQ>()
        var target by xdLink0_1(SharedTarget, onTargetDelete = FAIL_PER_TYPE { sources, hasMore ->
            "Q:${sources.size}:$hasMore"
        })
    }

    class RefR(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RefR>()
        var target by xdLink0_1(SharedTarget, onTargetDelete = FAIL)
    }

    // ---- one link name is shared by two types, another is used by one type ----

    class LabelTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<LabelTarget>()
    }

    class RefX(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RefX>()
        var first by xdLink0_1(LabelTarget, onTargetDelete = FAIL_PER_TYPE { sources, _ -> "X.first:${sources.size}" })
        var second by xdLink0_1(LabelTarget, onTargetDelete = FAIL_PER_TYPE { sources, _ -> "X.second:${sources.size}" })
    }

    class RefY(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<RefY>()
        var first by xdLink0_1(LabelTarget, onTargetDelete = FAIL_PER_TYPE { sources, _ -> "Y.first:${sources.size}" })
    }

    // ---- the link is declared on a type with a subtype and on an unrelated type

    class HierTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<HierTarget>()
    }

    open class HierRef(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<HierRef>()
        var tag by xdStringProp()
        var hier by xdLink0_1(HierTarget, onTargetDelete = FAIL_PER_ENTITY { "H-${it.toXd<HierRef>().tag}" })
    }

    class HierSub(entity: Entity) : HierRef(entity) {
        companion object : XdNaturalEntityType<HierSub>()
    }

    class HierOther(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<HierOther>()
        var hier by xdLink0_1(HierTarget, onTargetDelete = FAIL_PER_ENTITY { "OTHER-REF" })
    }

    class Counter(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Counter>()
        var value by xdRequiredIntProp()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(
            SharedTarget, RefP, RefQ, RefR,
            LabelTarget, RefX, RefY,
            HierTarget, HierRef, HierSub, HierOther,
            Counter
        )
    }

    private fun violationMessage(block: () -> Unit): String =
        assertFailsWith<ConstraintsValidationException>(block = block).causes.single().message!!

    private fun followLinkStarts(before: Map<String, Int>) =
        GremlinQueryCollector.countSince(before) { "FollowLink" in it }

    @Test
    fun `violations of every populated source type sharing a link name are reported`() {
        val target = transactional {
            val t = SharedTarget.new()
            RefP.new { this.target = t; tag = "p1" }
            RefQ.new { this.target = t }
            RefQ.new { this.target = t }
            RefR.new { this.target = t }
            t
        }

        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        val message = violationMessage { transactional { target.delete() } }

        assertThat(message).contains("P-p1")
        assertThat(message).contains("Q:2:false")
        assertThat(message).contains("target for {")
        // FAIL sources are not read by the policy phases; validation reads the shared name once.
        assertThat(followLinkStarts(before)).isEqualTo(1)
        transactional {
            assertThat(SharedTarget.all().toList()).containsExactly(target)
            assertThat(RefP.all().toList()).hasSize(1)
            assertThat(RefQ.all().toList()).hasSize(2)
            assertThat(RefR.all().toList()).hasSize(1)
        }
    }

    @Test
    fun `types without live sources are not reported`() {
        val target = transactional {
            val t = SharedTarget.new()
            RefQ.new { this.target = t }
            t
        }

        val message = violationMessage { transactional { target.delete() } }

        assertThat(message).contains("Q:1:false")
        assertThat(message).doesNotContain("P-")
        assertThat(message).doesNotContain("target for {")
    }

    @Test
    fun `only ten causes per type are reported and each type overflows independently`() {
        val target = transactional {
            val t = SharedTarget.new()
            repeat(12) { RefP.new { this.target = t; tag = "p$it" } }
            repeat(13) { RefQ.new { this.target = t } }
            repeat(3) { RefR.new { this.target = t } }
            t
        }

        val message = violationMessage { transactional { target.delete() } }

        // RefP and RefQ overflow, RefR does not.
        assertThat(Regex("P-p\\d+").findAll(message).count()).isEqualTo(10)
        assertThat(message).contains("Q:10:true")
        assertThat(message).contains("and more...")
    }

    @Test
    fun `sources removed or unlinked in the transaction do not violate`() {
        val (target, p, q, r) = transactional {
            val t = SharedTarget.new()
            listOf(
                t,
                RefP.new { this.target = t; tag = "p" },
                RefQ.new { this.target = t },
                RefR.new { this.target = t }
            )
        }

        // Every source is gone or unlinked before validation.
        transactional {
            (p as RefP).delete()
            (q as RefQ).target = null
            (r as RefR).target = null
            (target as SharedTarget).delete()
        }
        transactional { assertThat(SharedTarget.all().toList()).isEmpty() }
    }

    @Test
    fun `a remaining source still blocks deletion when the others are removed or unlinked`() {
        val target = transactional {
            val t = SharedTarget.new()
            RefP.new { this.target = t; tag = "p" }
            RefQ.new { this.target = t }
            RefR.new { this.target = t }
            t
        }

        val message = violationMessage {
            transactional {
                RefP.all().toList().single().delete()
                RefQ.all().toList().single().target = null
                target.delete()
            }
        }

        assertThat(message).contains("target for {")
        assertThat(message).doesNotContain("P-")
        assertThat(message).doesNotContain("Q:")
        transactional {
            assertThat(SharedTarget.all().toList()).containsExactly(target)
            assertThat(RefP.all().toList()).hasSize(1)
            assertThat(RefQ.all().toList().single().target).isNotNull()
        }
    }

    @Test
    fun `each link name is read separately and a shared name is read once`() {
        val target = transactional {
            val t = LabelTarget.new()
            RefX.new { first = t; second = t }
            RefY.new { first = t }
            t
        }

        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        val message = violationMessage { transactional { target.delete() } }

        assertThat(message).contains("X.first:1")
        assertThat(message).contains("X.second:1")
        assertThat(message).contains("Y.first:1")
        // "first" (RefX, RefY) is one untyped read and "second" (RefX only) keeps its typed read.
        assertThat(followLinkStarts(before)).isEqualTo(2)
    }

    @Test
    fun `a source is not attributed to a sibling type sharing its link name`() {
        val target = transactional {
            val t = LabelTarget.new()
            RefX.new { second = t }
            t
        }

        val message = violationMessage { transactional { target.delete() } }

        assertThat(message).contains("X.second:1")
        assertThat(message).doesNotContain("first")
    }

    @Test
    fun `sources of a subtype are reported through the supertype link`() {
        val target = transactional {
            val t = HierTarget.new()
            HierSub.new { hier = t; tag = "sub" }
            t
        }

        val message = violationMessage { transactional { target.delete() } }

        assertThat(message).contains("H-sub")
        assertThat(message).doesNotContain("OTHER-REF")
    }

    @Test
    fun `supertype subtype and unrelated sources sharing a link name are all reported`() {
        val target = transactional {
            val t = HierTarget.new()
            HierRef.new { hier = t; tag = "base" }
            HierSub.new { hier = t; tag = "sub" }
            HierOther.new { hier = t }
            t
        }

        val message = violationMessage { transactional { target.delete() } }

        assertThat(message).contains("H-base")
        assertThat(message).contains("H-sub")
        assertThat(message).contains("OTHER-REF")
    }

    @Test
    fun `subtype source is reported under each pair whose type covers it`() {
        val target = transactional {
            val t = HierTarget.new()
            HierSub.new { hier = t; tag = "sub" }
            HierRef.new { hier = t; tag = "base" }
            t
        }

        val message = violationMessage { transactional { target.delete() } }

        // Whatever pairs the model registers, the base instance is reported once per pair whose
        // type covers it and the subtype instance at least as often; never through an unrelated pair.
        val base = Regex("H-base").findAll(message).count()
        val sub = Regex("H-sub").findAll(message).count()
        assertThat(base).isEqualTo(1)
        assertThat(sub).isAtLeast(1)
        assertThat(message).doesNotContain("OTHER-REF")
    }

    @Test
    fun `subtype source unlinked in the transaction is not reported through any pair`() {
        val target = transactional {
            val t = HierTarget.new()
            HierSub.new { hier = t; tag = "sub" }
            HierRef.new { hier = t; tag = "base" }
            t
        }

        transactional {
            HierSub.all().toList().single().hier = null
            HierRef.all().toList().single { it.tag == "base" }.hier = null
            target.delete()
        }

        transactional { assertThat(HierTarget.all().toList()).isEmpty() }
    }

    @Test
    fun `the eleventh live source is what marks a report as overflowing`() {
        fun reportedFor(sources: Int, unlinkedInTransaction: Int = 0): String {
            val target = transactional {
                val t = SharedTarget.new()
                repeat(sources) { RefQ.new { this.target = t } }
                t
            }
            val message = violationMessage {
                transactional {
                    RefQ.all().toList().take(unlinkedInTransaction).forEach { it.target = null }
                    target.delete()
                }
            }
            transactional {
                RefQ.all().toList().forEach { it.delete() }
                target.delete()
            }
            return message
        }

        assertThat(reportedFor(10)).contains("Q:10:false")
        assertThat(reportedFor(11)).contains("Q:10:true")
        // Unlinked sources are filtered out before they are counted.
        assertThat(reportedFor(11, unlinkedInTransaction = 1)).contains("Q:10:false")
    }

    @Test
    fun `every type overflowing stops the shared read after one query`() {
        val target = transactional {
            val t = SharedTarget.new()
            repeat(12) { RefP.new { this.target = t; tag = "p$it" } }
            repeat(12) { RefQ.new { this.target = t } }
            repeat(12) { RefR.new { this.target = t } }
            t
        }

        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        val message = violationMessage { transactional { target.delete() } }

        assertThat(Regex("P-p\\d+").findAll(message).count()).isEqualTo(10)
        assertThat(message).contains("Q:10:true")
        assertThat(message).contains("and more...")
        assertThat(followLinkStarts(before)).isEqualTo(1)
    }

    @Test
    fun `violations are reported in the order of the incoming associations metadata`() {
        val target = transactional {
            val t = SharedTarget.new()
            RefP.new { this.target = t; tag = "p" }
            RefQ.new { this.target = t }
            RefR.new { this.target = t }
            t
        }

        val marker = mapOf(
            RefP.entityType to "P-p",
            RefQ.entityType to "Q:1:false",
            RefR.entityType to "target for {"
        )
        val metaData = store.modelMetaData!!
        val expectedOrder = metaData.getEntityMetaData(SharedTarget.entityType)!!
            .getIncomingAssociations(metaData).keys.map { marker.getValue(it) }

        val message = violationMessage { transactional { target.delete() } }

        assertThat(expectedOrder.sortedBy { message.indexOf(it) }).containsExactlyElementsIn(expectedOrder).inOrder()
        assertThat(expectedOrder.map { message.indexOf(it) }).doesNotContain(-1)
    }

    @Test
    fun `untyped read visits the sources of a type in the order of its typed read`() {
        val target = transactional {
            val t = SharedTarget.new()
            repeat(15) {
                RefP.new { this.target = t; tag = "p$it" }
                RefQ.new { this.target = t }
            }
            t
        }

        transactional {
            val entity = target.entity as TransientEntity
            val session = entity.store.threadSessionOrThrow
            val transaction = session.transactionInternal as YTDBStoreTransaction
            val untyped = session.createPersistentEntityIterableWrapper(
                transaction.findLinksUntyped(entity, "target")
            ).toList().filter { it.type == RefQ.entityType }.map { it.id }
            val typed = session.findLinks(RefQ.entityType, entity, "target").toList().map { it.id }

            assertThat(typed).hasSize(15)
            assertThat(untyped).containsExactlyElementsIn(typed).inOrder()
        }
    }

    @Test
    fun `direct transient deletion validates grouped incoming links`() {
        val target = transactional {
            val t = SharedTarget.new()
            RefR.new { this.target = t }
            t
        }

        val message = violationMessage {
            transactional { (target.entity as TransientEntity).delete() }
        }

        assertThat(message).contains("target for {")
        transactional { assertThat(SharedTarget.all().toList()).containsExactly(target) }
    }

    @Test
    fun `retry validation sees a grouped source committed by the winner`() {
        val (target, counter) = transactional { SharedTarget.new() to Counter.new { value = 0 } }
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
                    RefQ.new { this.target = target }
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
            assertThat(SharedTarget.all().toList()).containsExactly(target)
            assertThat(RefQ.all().toList().single().target).isEqualTo(target)
        }
    }
}
