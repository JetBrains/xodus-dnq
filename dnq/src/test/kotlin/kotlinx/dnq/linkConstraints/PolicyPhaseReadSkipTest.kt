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
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Deletion policy passes must not load links whose policies cannot act in that phase:
 * incoming FAIL never acts (validation enforces it), incoming CLEAR and outgoing CLEAR act only
 * in the mutation phase, CASCADE acts in both.
 */
class PolicyPhaseReadSkipTest : DBTest() {
    class FailTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailTarget>()
    }

    class FailOnlySource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<FailOnlySource>()
        var failLink by xdLink0_1(FailTarget, onTargetDelete = OnDeletePolicy.FAIL)
    }

    class ClearTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearTarget>()
        var linkOnRemove by xdBooleanProp()
        override fun destructor() {
            if (linkOnRemove) ClearOnlySource.new { clearLink = this@ClearTarget }
        }
    }

    class ClearOnlySource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearOnlySource>()
        var clearLink by xdLink0_1(ClearTarget, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class MixTarget(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MixTarget>()
    }

    class MixFailSource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MixFailSource>()
        var mixLink by xdLink0_1(MixTarget, onTargetDelete = OnDeletePolicy.FAIL)
    }

    class MixClearSource(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<MixClearSource>()
        var mixLink by xdLink0_1(MixTarget, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    class ItemRoot(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ItemRoot>()
        val items by xdLink0_N(Item, onDelete = OnDeletePolicy.CLEAR)
    }

    open class Item(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Item>()
    }

    class ItemA(entity: Entity) : Item(entity) {
        companion object : XdNaturalEntityType<ItemA>()
    }

    class ItemB(entity: Entity) : Item(entity) {
        companion object : XdNaturalEntityType<ItemB>()
    }

    class KidRoot(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<KidRoot>()
        val kids by xdLink0_N(Kid::root, onDelete = OnDeletePolicy.CLEAR)
    }

    open class Kid(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Kid>() {
            val removed = mutableListOf<String>()
        }
        var name by xdRequiredStringProp()
        var root: KidRoot? by xdLink0_1(KidRoot::kids, onTargetDelete = OnDeletePolicy.CASCADE)
        override fun destructor() {
            removed.add(name)
        }
    }

    class KidA(entity: Entity) : Kid(entity) {
        companion object : XdNaturalEntityType<KidA>()
    }

    class KidB(entity: Entity) : Kid(entity) {
        companion object : XdNaturalEntityType<KidB>()
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(
            FailTarget, FailOnlySource, ClearTarget, ClearOnlySource, MixTarget, MixFailSource, MixClearSource,
            ItemRoot, Item, ItemA, ItemB, KidRoot, Kid, KidA, KidB
        )
        Kid.removed.clear()
    }

    private fun followLinkSince(before: Map<String, Int>, direction: String, linkName: String): Int =
        GremlinQueryCollector.countSince(before) { "FollowLink" in it && direction in it && linkName in it }

    @Test
    fun `FAIL-only incoming group is read only by validation`() {
        val target = transactional { FailTarget.new() }
        val source = transactional { FailOnlySource.new { failLink = target } }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        assertFailsWith<ConstraintsValidationException> { transactional { target.delete() } }
        // No policy-phase read; one typed validation read.
        assertThat(followLinkSince(before, "IN", "failLink")).isEqualTo(1)
        transactional {
            assertThat(FailTarget.all().toList()).containsExactly(target)
            assertThat(FailOnlySource.all().toList().single()).isEqualTo(source)
            assertThat(source.failLink).isEqualTo(target)
        }
    }

    @Test
    fun `CLEAR-only incoming group is read only in the mutation phase`() {
        val target = transactional { ClearTarget.new() }
        val source = transactional { ClearOnlySource.new { clearLink = target } }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { target.delete() }
        // Destructor phase skipped; mutation phase reads once; the target is empty at validation.
        assertThat(followLinkSince(before, "IN", "clearLink")).isEqualTo(1)
        transactional {
            assertThat(ClearTarget.all().toList()).isEmpty()
            assertThat(source.clearLink).isNull()
        }
    }

    @Test
    fun `CLEAR-only source linked by a destructor is cleared by the mutation phase read`() {
        val target = transactional { ClearTarget.new { linkOnRemove = true } }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { target.delete() }
        assertThat(followLinkSince(before, "IN", "clearLink")).isEqualTo(1)
        transactional {
            assertThat(ClearTarget.all().toList()).isEmpty()
            assertThat(ClearOnlySource.all().toList().single().clearLink).isNull()
        }
    }

    @Test
    fun `mixed FAIL and CLEAR group is read in mutation phase and validation and still fails`() {
        val target = transactional { MixTarget.new() }
        val (fail, clear) = transactional {
            MixFailSource.new { mixLink = target } to MixClearSource.new { mixLink = target }
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        assertFailsWith<ConstraintsValidationException> { transactional { target.delete() } }
        // Mutation-phase read (1) + typed validation reads for the two source types (2).
        assertThat(followLinkSince(before, "IN", "mixLink")).isEqualTo(3)
        transactional {
            assertThat(MixTarget.all().toList()).containsExactly(target)
            assertThat(fail.mixLink).isEqualTo(target)
            assertThat(clear.mixLink).isEqualTo(target)
        }
    }

    @Test
    fun `CLEAR-only outgoing link is read only in the mutation phase`() {
        val (root, first, second) = transactional {
            ItemA.new() // distinct local ids; keeps mixed-type adjacency on the query-backed path
            val root = ItemRoot.new()
            val first = ItemA.new()
            val second = ItemB.new()
            root.items.add(first)
            root.items.add(second)
            Triple(root, first, second)
        }
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { root.delete() }
        assertThat(followLinkSince(before, "OUT", "items")).isEqualTo(1)
        transactional {
            assertThat(ItemRoot.all().toList()).isEmpty()
            assertThat(Item.all().toList()).containsAtLeast(first, second)
        }
    }

    @Test
    fun `outgoing CLEAR with opposite-end CASCADE still reads in both phases and cascades`() {
        val root = transactional {
            KidA.new { name = "pad" } // distinct local ids across types
            val root = KidRoot.new()
            root.kids.add(KidA.new { name = "a" })
            root.kids.add(KidB.new { name = "b" })
            root
        }
        Kid.removed.clear()
        GremlinQueryCollector.enableForTests()
        val before = GremlinQueryCollector.snapshot()
        transactional { root.delete() }
        assertThat(followLinkSince(before, "OUT", "kids")).isEqualTo(2)
        assertThat(Kid.removed).containsExactly("a", "b")
        transactional {
            assertThat(KidRoot.all().toList()).isEmpty()
            assertThat(Kid.all().toList().map { it.name }).containsExactly("pad")
        }
    }
}
