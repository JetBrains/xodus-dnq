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
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryCollector
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy.CASCADE
import kotlinx.dnq.link.OnDeletePolicy.CLEAR
import kotlinx.dnq.link.OnDeletePolicy.FAIL
import kotlinx.dnq.query.toList
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/**
 * Correctness tests for onTargetDelete constraints when the entity being deleted is
 * referenced by instances of multiple distinct source types via the same link name.
 *
 * This is the "N-subtype fan-out" scenario described in XD-1263: when [target] is deleted,
 * DNQ processes one (sourceType, linkName) pair per source entity type, each of which
 * triggers a separate findLinks query. These tests verify that all source types are handled
 * correctly regardless of how the underlying queries are batched.
 *
 * Entities: one [PolyTarget] is referenced by five independent source types
 * ([PolySub1]..[PolySub5]), all via a link named "target".
 */
class OnTargetDeletePolymorphicTest : DBTest() {

    // ---- entity model --------------------------------------------------------

    class PolyTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PolyTarget>()
    }

    // Five independent source types, each linking to PolyTarget with onTargetDelete = CASCADE
    class CascadeSub1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CascadeSub1>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = CASCADE)
    }
    class CascadeSub2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CascadeSub2>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = CASCADE)
    }
    class CascadeSub3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CascadeSub3>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = CASCADE)
    }
    class CascadeSub4(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CascadeSub4>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = CASCADE)
    }
    class CascadeSub5(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<CascadeSub5>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = CASCADE)
    }

    // Five independent source types, each linking to PolyTarget with onTargetDelete = CLEAR
    class ClearSub1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearSub1>()
        var target: PolyTarget? by xdLink0_1(PolyTarget, onTargetDelete = CLEAR)
    }
    class ClearSub2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearSub2>()
        var target: PolyTarget? by xdLink0_1(PolyTarget, onTargetDelete = CLEAR)
    }
    class ClearSub3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearSub3>()
        var target: PolyTarget? by xdLink0_1(PolyTarget, onTargetDelete = CLEAR)
    }
    class ClearSub4(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearSub4>()
        var target: PolyTarget? by xdLink0_1(PolyTarget, onTargetDelete = CLEAR)
    }
    class ClearSub5(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearSub5>()
        var target: PolyTarget? by xdLink0_1(PolyTarget, onTargetDelete = CLEAR)
    }

    // Entity model for multiple-link-names scenario: two source types reach the same target
    // via different link names ("primary" and "secondary"), both with onTargetDelete = CASCADE.
    class MultiLinkTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MultiLinkTarget>()
    }
    class PrimaryLinkSub(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<PrimaryLinkSub>()
        var primary: MultiLinkTarget by xdLink1(MultiLinkTarget, onTargetDelete = CASCADE)
    }
    class SecondaryLinkSub(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<SecondaryLinkSub>()
        var secondary: MultiLinkTarget by xdLink1(MultiLinkTarget, onTargetDelete = CASCADE)
    }

    // Entity model for transitive cascade scenarios:
    //   TransRoot → TransMid1..3 (via "root", CASCADE) → TransLeaf{N}_1..3 (via "mid", CASCADE)
    //
    // Leaf types have no instances in most tests — they exist only to register incoming
    // association metadata, which is sufficient to trigger per-type DB queries in the pre-fix code.
    class TransRoot(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransRoot>()
    }
    class TransMid1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransMid1>()
        var root: TransRoot by xdLink1(TransRoot, onTargetDelete = CASCADE)
    }
    class TransMid2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransMid2>()
        var root: TransRoot by xdLink1(TransRoot, onTargetDelete = CASCADE)
    }
    class TransMid3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransMid3>()
        var root: TransRoot by xdLink1(TransRoot, onTargetDelete = CASCADE)
    }
    class TransLeaf1a(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf1a>()
        var mid: TransMid1 by xdLink1(TransMid1, onTargetDelete = CASCADE)
    }
    class TransLeaf1b(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf1b>()
        var mid: TransMid1 by xdLink1(TransMid1, onTargetDelete = CASCADE)
    }
    class TransLeaf1c(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf1c>()
        var mid: TransMid1 by xdLink1(TransMid1, onTargetDelete = CASCADE)
    }
    class TransLeaf2a(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf2a>()
        var mid: TransMid2 by xdLink1(TransMid2, onTargetDelete = CASCADE)
    }
    class TransLeaf2b(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf2b>()
        var mid: TransMid2 by xdLink1(TransMid2, onTargetDelete = CASCADE)
    }
    class TransLeaf2c(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf2c>()
        var mid: TransMid2 by xdLink1(TransMid2, onTargetDelete = CASCADE)
    }
    class TransLeaf3a(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf3a>()
        var mid: TransMid3 by xdLink1(TransMid3, onTargetDelete = CASCADE)
    }
    class TransLeaf3b(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf3b>()
        var mid: TransMid3 by xdLink1(TransMid3, onTargetDelete = CASCADE)
    }
    class TransLeaf3c(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<TransLeaf3c>()
        var mid: TransMid3 by xdLink1(TransMid3, onTargetDelete = CASCADE)
    }

    // Three independent source types linking to PolyTarget with onTargetDelete = FAIL.
    // Deleting the target while any of these sources exist must throw ConstraintsValidationException.
    class FailSub1(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailSub1>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = FAIL)
    }
    class FailSub2(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailSub2>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = FAIL)
    }
    class FailSub3(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailSub3>()
        var target: PolyTarget by xdLink1(PolyTarget, onTargetDelete = FAIL)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(
            PolyTarget,
            CascadeSub1, CascadeSub2, CascadeSub3, CascadeSub4, CascadeSub5,
            ClearSub1, ClearSub2, ClearSub3, ClearSub4, ClearSub5,
            MultiLinkTarget, PrimaryLinkSub, SecondaryLinkSub,
            TransRoot, TransMid1, TransMid2, TransMid3,
            TransLeaf1a, TransLeaf1b, TransLeaf1c,
            TransLeaf2a, TransLeaf2b, TransLeaf2c,
            TransLeaf3a, TransLeaf3b, TransLeaf3c,
            FailSub1, FailSub2, FailSub3,
        )
    }

    // ---- tests ---------------------------------------------------------------

    /**
     * When a target entity is deleted, all source instances of every type with
     * onTargetDelete=CASCADE must also be deleted — not just sources of the first type.
     */
    @Test
    fun `onTargetDelete CASCADE cascades across all five source types`() {
        val target = transactional {
            val t = PolyTarget.new()
            CascadeSub1.new { target = t }
            CascadeSub2.new { target = t }
            CascadeSub3.new { target = t }
            CascadeSub4.new { target = t }
            CascadeSub5.new { target = t }
            t
        }

        transactional { target.delete() }

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            assertThat(CascadeSub1.all().toList()).isEmpty()
            assertThat(CascadeSub2.all().toList()).isEmpty()
            assertThat(CascadeSub3.all().toList()).isEmpty()
            assertThat(CascadeSub4.all().toList()).isEmpty()
            assertThat(CascadeSub5.all().toList()).isEmpty()
        }
    }

    /**
     * Multiple source instances per source type: each of the five types contributes
     * three instances. All 15 sources must be cascade-deleted when the target goes away.
     */
    @Test
    fun `onTargetDelete CASCADE handles multiple instances per source type`() {
        val target = transactional {
            val t = PolyTarget.new()
            repeat(3) { CascadeSub1.new { target = t } }
            repeat(3) { CascadeSub2.new { target = t } }
            repeat(3) { CascadeSub3.new { target = t } }
            repeat(3) { CascadeSub4.new { target = t } }
            repeat(3) { CascadeSub5.new { target = t } }
            t
        }

        transactional { target.delete() }

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            assertThat(CascadeSub1.all().toList()).isEmpty()
            assertThat(CascadeSub2.all().toList()).isEmpty()
            assertThat(CascadeSub3.all().toList()).isEmpty()
            assertThat(CascadeSub4.all().toList()).isEmpty()
            assertThat(CascadeSub5.all().toList()).isEmpty()
        }
    }

    /**
     * When a target entity is deleted, links from all five source types with
     * onTargetDelete=CLEAR must be nulled out — not just sources of the first type.
     * The source entities themselves must survive.
     */
    @Test
    fun `onTargetDelete CLEAR clears link across all five source types`() {
        val target = transactional { PolyTarget.new() }
        val s1 = transactional { ClearSub1.new { this.target = target } }
        val s2 = transactional { ClearSub2.new { this.target = target } }
        val s3 = transactional { ClearSub3.new { this.target = target } }
        val s4 = transactional { ClearSub4.new { this.target = target } }
        val s5 = transactional { ClearSub5.new { this.target = target } }

        transactional { target.delete() }

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            // All source instances survive
            assertThat(ClearSub1.all().toList()).hasSize(1)
            assertThat(ClearSub2.all().toList()).hasSize(1)
            assertThat(ClearSub3.all().toList()).hasSize(1)
            assertThat(ClearSub4.all().toList()).hasSize(1)
            assertThat(ClearSub5.all().toList()).hasSize(1)
            // All links are cleared
            assertThat(s1.target).isNull()
            assertThat(s2.target).isNull()
            assertThat(s3.target).isNull()
            assertThat(s4.target).isNull()
            assertThat(s5.target).isNull()
        }
    }

    /**
     * Only the sources that actually referenced the deleted target are affected.
     * Sources pointing to a surviving target must be untouched.
     */
    @Test
    fun `onTargetDelete CLEAR does not affect sources pointing to a different target`() {
        val (targetToDelete, otherTarget) = transactional {
            PolyTarget.new() to PolyTarget.new()
        }
        val (affected, unaffected) = transactional {
            ClearSub1.new { target = targetToDelete } to ClearSub1.new { target = otherTarget }
        }

        transactional { targetToDelete.delete() }

        transactional {
            assertThat(affected.target).isNull()
            assertThat(unaffected.target).isEqualTo(otherTarget)
        }
    }

    /**
     * When CASCADE and CLEAR subtypes share the same link name, deleting the target must
     * apply the correct policy to each: CASCADE sources are deleted, CLEAR sources survive
     * with their link nulled.
     *
     * After the XD-1263 fix, all subtypes sharing a link name receive the same pre-fetched
     * source list from a single untyped query; the in-memory type dispatch must route each
     * source to its own policy without confusion.
     */
    @Test
    fun `onTargetDelete CASCADE and CLEAR coexist correctly when sharing the same link name`() {
        val target = transactional { PolyTarget.new() }
        transactional {
            CascadeSub1.new { this.target = target }
            CascadeSub2.new { this.target = target }
        }
        val c1 = transactional { ClearSub1.new { this.target = target } }
        val c2 = transactional { ClearSub2.new { this.target = target } }

        transactional { target.delete() }

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            // CASCADE sources are deleted
            assertThat(CascadeSub1.all().toList()).isEmpty()
            assertThat(CascadeSub2.all().toList()).isEmpty()
            // CLEAR sources survive with nulled links
            assertThat(ClearSub1.all().toList()).hasSize(1)
            assertThat(ClearSub2.all().toList()).hasSize(1)
            assertThat(c1.target).isNull()
            assertThat(c2.target).isNull()
        }
    }

    /**
     * When a target is referenced by source types via different link names, deleting the
     * target must cascade-delete sources regardless of which link name they used.
     *
     * After the XD-1263 fix, incoming associations are grouped by link name and one query
     * is issued per distinct name. This test verifies that no group is silently dropped —
     * sources reached via the second link name must be processed just as sources via the first.
     */
    @Test
    fun `onTargetDelete CASCADE works across multiple distinct link names`() {
        val target = transactional {
            val t = MultiLinkTarget.new()
            PrimaryLinkSub.new { primary = t }
            SecondaryLinkSub.new { secondary = t }
            t
        }

        transactional { target.delete() }

        transactional {
            assertThat(MultiLinkTarget.all().toList()).isEmpty()
            assertThat(PrimaryLinkSub.all().toList()).isEmpty()
            assertThat(SecondaryLinkSub.all().toList()).isEmpty()
        }
    }

    /**
     * Transitive polymorphic cascade: deleting the root must cascade through every mid-level
     * subtype and then down to their leaf-level subtypes.
     *
     * The existing [nested onTargetDelete=CASCADE] test in OnTargetDeleteCascadeTest covers a
     * single-type chain. This test covers the same depth but with multiple subtypes at each
     * level, which is the scenario where the XD-1263 fan-out problem compounds across levels.
     */
    @Test
    fun `onTargetDelete CASCADE cascades transitively through multiple subtypes at each level`() {
        val root = transactional {
            val r = TransRoot.new()
            val m1 = TransMid1.new { root = r }
            val m2 = TransMid2.new { root = r }
            val m3 = TransMid3.new { root = r }
            TransLeaf1a.new { mid = m1 }
            TransLeaf1b.new { mid = m1 }
            TransLeaf2a.new { mid = m2 }
            TransLeaf3a.new { mid = m3 }
            TransLeaf3b.new { mid = m3 }
            r
        }

        transactional { root.delete() }

        transactional {
            assertThat(TransRoot.all().toList()).isEmpty()
            assertThat(TransMid1.all().toList()).isEmpty()
            assertThat(TransMid2.all().toList()).isEmpty()
            assertThat(TransMid3.all().toList()).isEmpty()
            assertThat(TransLeaf1a.all().toList()).isEmpty()
            assertThat(TransLeaf1b.all().toList()).isEmpty()
            assertThat(TransLeaf2a.all().toList()).isEmpty()
            assertThat(TransLeaf3a.all().toList()).isEmpty()
            assertThat(TransLeaf3b.all().toList()).isEmpty()
        }
    }

    // ---- query-count baseline (Phase 0c / XD-1263) ---------------------------

    /**
     * Measures how many findLinks DB queries (matched by "FollowLink" in the Gremlin shape)
     * are issued when deleting an entity referenced by instances of multiple distinct source
     * types via the same link name.
     *
     * The model has 10 source types (5 CASCADE + 5 CLEAR), all linked via "target".
     * Two separate code paths fire these queries:
     *
     * 1. processOnDeleteConstraints (EntityOperations.remove, 2 phases):
     *    Source types for "target" link: 5 CASCADE + 5 CLEAR + 3 FAIL = 13
     *    BEFORE fix: 13 source types × 2 = 26 typed Labeled(FollowLink) queries
     *    AFTER  fix:  1 link name   × 2 =  2 untyped FollowLink queries
     *
     * 2. checkIncomingLinks (constraint validation — not in scope for XD-1263):
     *    13 source types × 1 call = 13 typed Labeled(FollowLink) queries (unchanged)
     *
     * Total BEFORE: 39  |  Total AFTER: 15
     *
     * Note: the Gremlin optimizer rewrites ByIds+InLink+HasLabel into
     * Labeled(FollowLink(ByIds(?), IN, linkName), type), and ByIds+InLink into
     * FollowLink(ByIds(?), IN, linkName), so both typed and untyped queries match "FollowLink".
     */
    @Test
    fun `findLinks query count for multi-subtype onTargetDelete - baseline`() {
        val target = transactional { PolyTarget.new() }
        transactional {
            CascadeSub1.new { this.target = target }
            CascadeSub2.new { this.target = target }
            CascadeSub3.new { this.target = target }
            CascadeSub4.new { this.target = target }
            CascadeSub5.new { this.target = target }
        }

        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()

        transactional { target.delete() }

        val findLinksCount = GremlinQueryCollector.countSince(before) { "FollowLink" in it }

        // Breakdown of FollowLink queries during target.delete():
        //
        // Source types registered for "target" link: 5 CASCADE + 5 CLEAR + 3 FAIL = 13
        //
        // processOnDeleteConstraints (EntityOperations.remove, 2 phases):
        //   BEFORE fix: 13 source types × 2 phases = 26 typed Labeled(FollowLink) queries
        //   AFTER  fix:  1 link name   × 2 phases =  2 untyped FollowLink queries
        //
        // checkIncomingLinks (constraint validation, unchanged):
        //   13 source types × 1 call = 13 typed Labeled(FollowLink) queries
        //
        // Total BEFORE: 39  |  Total AFTER: 15
        assertThat(findLinksCount).isEqualTo(15)
    }

    /**
     * Stress baseline showing the query fan-out under a two-level transitive cascade with
     * multiple subtypes at each level.
     *
     * Setup: 3 Mid subtypes × 18 instances each, 3 Leaf subtypes per Mid (no Leaf instances).
     * Leaf types register incoming-association metadata without requiring actual rows — the
     * pre-fix code fires one typed DB query per (sourceType, linkName) pair regardless.
     *
     * Query breakdown (pre-fix):
     *
     *   processOnDeleteConstraints for TransRoot (2 phases):
     *     3 Mid types × 2 = 6
     *
     *   processOnDeleteConstraints for each of 54 Mid instances (2 phases × 3 Leaf types):
     *     54 × 3 × 2 = 324
     *
     *   checkIncomingLinks for TransRoot:
     *     3  (one per Mid type)
     *
     *   checkIncomingLinks for each of 54 Mid instances (3 Leaf types each):
     *     54 × 3 = 162
     *
     *   Total BEFORE: 6 + 324 + 3 + 162 = 495
     *
     * After the XD-1263 fix, processOnDeleteConstraints issues one untyped query per distinct
     * linkName per invocation ("root" for TransRoot, "mid" for each Mid instance):
     *
     *   processOnDeleteConstraints TransRoot (2 phases × 1 linkName):     2
     *   processOnDeleteConstraints 54 Mid instances (2 phases × 1 linkName): 108
     *   checkIncomingLinks (unchanged):                                    165
     *
     *   Total AFTER: 275
     *
     * Update the assertion below from 495 to 275 after applying the Phase 1 fix.
     */
    @Test
    fun `findLinks query count for transitive multi-subtype cascade - baseline`() {
        val root = transactional { TransRoot.new() }
        transactional {
            repeat(18) { TransMid1.new { this.root = root } }
            repeat(18) { TransMid2.new { this.root = root } }
            repeat(18) { TransMid3.new { this.root = root } }
        }

        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()

        transactional { root.delete() }

        val findLinksCount = GremlinQueryCollector.countSince(before) { "FollowLink" in it }

        assertThat(findLinksCount).isEqualTo(275)
    }

    // ---- onTargetDelete FAIL tests -------------------------------------------

    /**
     * When a target entity is deleted while sources with onTargetDelete=FAIL still exist,
     * the deletion must throw [ConstraintsValidationException] and leave all entities intact.
     *
     * Three FailSub types link to the same PolyTarget. All must individually prevent deletion.
     */
    @Test
    fun `onTargetDelete FAIL throws ConstraintsValidationException when sources are present`() {
        val target = transactional {
            val t = PolyTarget.new()
            FailSub1.new { this.target = t }
            FailSub2.new { this.target = t }
            FailSub3.new { this.target = t }
            t
        }

        assertFailsWith<ConstraintsValidationException> {
            transactional { target.delete() }
        }

        transactional {
            // All entities still exist after the blocked deletion
            assertThat(PolyTarget.all().toList()).hasSize(1)
            assertThat(FailSub1.all().toList()).hasSize(1)
            assertThat(FailSub2.all().toList()).hasSize(1)
            assertThat(FailSub3.all().toList()).hasSize(1)
        }
    }

    /**
     * When both FAIL and CASCADE sources exist, FAIL takes precedence and blocks deletion.
     * Once all FAIL sources are removed, deleting the target succeeds and CASCADE sources are gone.
     */
    @Test
    fun `onTargetDelete FAIL and CASCADE coexist - FAIL blocks deletion while any FAIL source exists`() {
        val target = transactional {
            val t = PolyTarget.new()
            CascadeSub1.new { this.target = t }
            CascadeSub2.new { this.target = t }
            FailSub1.new { this.target = t }
            t
        }

        // FAIL source prevents deletion even though CASCADE sources are present
        assertFailsWith<ConstraintsValidationException> {
            transactional { target.delete() }
        }

        // Remove all FAIL sources
        transactional {
            FailSub1.all().toList().forEach { it.delete() }
        }

        // Now deletion succeeds and CASCADE sources are also gone
        transactional { target.delete() }

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            assertThat(CascadeSub1.all().toList()).isEmpty()
            assertThat(CascadeSub2.all().toList()).isEmpty()
            assertThat(FailSub1.all().toList()).isEmpty()
        }
    }

    // ---- concurrent / replay tests (scenarios 7 and 8) ----------------------

    /**
     * Scenario 7: Multi-subtype cascade delete under transaction replay.
     *
     * Two threads each delete a separate PolyTarget entity that has sources of multiple subtypes.
     * Thread B waits inside its transaction until Thread A commits, which forces Thread B's
     * transaction into a NeedRetryException. On replay, the cascade must still process all
     * source types correctly.
     *
     * If processOnDeleteConstraints uses a stale transactionInternal after the replay replaces
     * it with a fresh transaction, some sources may be silently missed.
     */
    @Test
    fun `multi-subtype cascade delete completes correctly under transaction replay`() {
        val target1 = transactional {
            val t = PolyTarget.new()
            CascadeSub1.new { this.target = t }
            CascadeSub2.new { this.target = t }
            CascadeSub3.new { this.target = t }
            t
        }
        val target2 = transactional {
            val t = PolyTarget.new()
            CascadeSub1.new { this.target = t }
            CascadeSub2.new { this.target = t }
            CascadeSub3.new { this.target = t }
            t
        }

        val bothStarted = CountDownLatch(2)
        val firstCommitted = CountDownLatch(1)

        val t1 = thread {
            store.transactional {
                bothStarted.countDown()
                bothStarted.await()
                target1.delete()
            }
            firstCommitted.countDown()
        }

        val t2 = thread {
            store.transactional {
                bothStarted.countDown()
                bothStarted.await()
                target2.delete()
                firstCommitted.await() // keep tx open until T1 commits, forcing a replay
            }
        }

        t1.join()
        t2.join()

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            assertThat(CascadeSub1.all().toList()).isEmpty()
            assertThat(CascadeSub2.all().toList()).isEmpty()
            assertThat(CascadeSub3.all().toList()).isEmpty()
        }
    }

    /**
     * Scenario 8: New source committed between the original delete attempt and its replay.
     *
     * Thread A opens a transaction to delete the target (which already has existing sources).
     * While Thread A's transaction is open, Thread B commits a brand-new source pointing to the
     * same target. Thread A then hits NeedRetryException and replays. On replay, the untyped
     * query must return the fresh snapshot that includes Thread B's source — and that source
     * must also be cascade-deleted.
     */
    @Test
    fun `new source committed during replay window is also cascade-deleted`() {
        val target = transactional {
            val t = PolyTarget.new()
            CascadeSub1.new { this.target = t }
            CascadeSub2.new { this.target = t }
            t
        }

        // Thread A deletes the target; inner Thread B commits a new source concurrently,
        // causing Thread A to retry. On retry Thread A must also cascade-delete the new source.
        transactional {
            target.delete()
            thread {
                transactional {
                    CascadeSub3.new { this.target = target }
                }
            }.join()
        }

        transactional {
            assertThat(PolyTarget.all().toList()).isEmpty()
            assertThat(CascadeSub1.all().toList()).isEmpty()
            assertThat(CascadeSub2.all().toList()).isEmpty()
            assertThat(CascadeSub3.all().toList()).isEmpty()
        }
    }
}
