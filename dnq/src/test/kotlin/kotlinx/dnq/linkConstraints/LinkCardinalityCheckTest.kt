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
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.database.exceptions.CardinalityViolationException
import jetbrains.exodus.database.exceptions.ConstraintsValidationException
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import kotlinx.dnq.link.OnDeletePolicy
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Cardinality validation only distinguishes none, one and more than one target, so it reads a
 * bounded number of targets. The boundaries between those counts decide validity for every
 * cardinality; the high-degree cases check that a link with many targets stays valid. That the
 * read really stops early is pinned by `YTDBEntityTest`.
 */
class LinkCardinalityCheckTest : DBTest() {
    class Target(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Target>()
    }

    /** `[1]` */
    class ExactlyOne(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ExactlyOne>()
        var link by xdLink1(Target)
    }

    /** `[0..1]` */
    class AtMostOne(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<AtMostOne>()
        var link by xdLink0_1(Target)
    }

    /** `[1..n]` */
    class AtLeastOne(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<AtLeastOne>()
        val links by xdLink1_N(Target)
    }

    /** `[1..n]`, every target deletion clears the link. */
    class ClearedAtLeastOne(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<ClearedAtLeastOne>()
        val links by xdLink1_N(Target, onTargetDelete = OnDeletePolicy.CLEAR)
    }

    /** `[0..n]` */
    class AnyNumber(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<AnyNumber>()
        val links by xdLink0_N(Target)
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(Target, ExactlyOne, AtMostOne, AtLeastOne, ClearedAtLeastOne, AnyNumber)
    }

    private fun assertCardinalityViolation(linkName: String, block: () -> Unit) {
        val failure = assertFailsWith<ConstraintsValidationException> { block() }
        assertThat(failure.causes.filterIsInstance<CardinalityViolationException>().map { it.message })
            .hasSize(1)
        assertThat(failure.causes.single().message).contains(".$linkName]")
    }

    /**
     * Adds a second target behind DNQ's back: the single-valued association API refuses it, and
     * the untracked edge is only checked when the link is otherwise marked as changed.
     */
    private fun XdEntity.addRawLink(linkName: String, target: Target) {
        (entity as TransientEntity).entity.addLink(linkName, (target.entity as TransientEntity).entity)
    }

    // ---- [1] -------------------------------------------------------------------------------

    @Test
    fun `exactly one requires one target`() {
        assertCardinalityViolation("link") {
            transactional { ExactlyOne.new { } }
        }
        transactional { ExactlyOne.new { link = Target.new() } }
        assertThat(transactional { ExactlyOne.all().toList().size }).isEqualTo(1)
    }

    @Test
    fun `exactly one rejects two targets`() {
        assertCardinalityViolation("link") {
            transactional {
                ExactlyOne.new {
                    link = Target.new()
                    addRawLink("link", Target.new())
                }
            }
        }
    }

    @Test
    fun `exactly one of a saved entity rejects a cleared and a doubled link`() {
        val holder = transactional { ExactlyOne.new { link = Target.new() } }
        assertCardinalityViolation("link") {
            transactional { holder.entity.deleteLinks("link") }
        }
        assertCardinalityViolation("link") {
            transactional {
                holder.link = Target.new()
                holder.addRawLink("link", Target.new())
            }
        }
        // replacing the single target keeps the cardinality
        transactional { holder.link = Target.new() }
    }

    // ---- [0..1] ----------------------------------------------------------------------------

    @Test
    fun `at most one rejects two targets of a new entity`() {
        assertCardinalityViolation("link") {
            transactional {
                AtMostOne.new {
                    link = Target.new()
                    addRawLink("link", Target.new())
                }
            }
        }
    }

    @Test
    fun `at most one accepts none and one and rejects two`() {
        transactional { AtMostOne.new { } }
        val holder = transactional { AtMostOne.new { link = Target.new() } }
        transactional { holder.link = null }
        transactional { holder.link = Target.new() }
        assertCardinalityViolation("link") {
            transactional {
                holder.link = Target.new()
                holder.addRawLink("link", Target.new())
            }
        }
    }

    // ---- [1..n] ----------------------------------------------------------------------------

    @Test
    fun `at least one requires a target`() {
        assertCardinalityViolation("links") {
            transactional { AtLeastOne.new { } }
        }
        val holder = transactional { AtLeastOne.new { links.add(Target.new()) } }
        assertCardinalityViolation("links") {
            transactional { holder.links.clear() }
        }
    }

    @Test
    fun `at least one accepts a high degree link and the removal down to one target`() {
        val holder = transactional {
            AtLeastOne.new {
                repeat(HIGH_DEGREE) { links.add(Target.new()) }
            }
        }
        transactional {
            val targets = holder.links.toList()
            assertThat(targets).hasSize(HIGH_DEGREE)
            targets.drop(1).forEach { holder.links.remove(it) }
        }
        transactional { assertThat(holder.links.toList()).hasSize(1) }
        assertCardinalityViolation("links") {
            transactional { holder.links.clear() }
        }
    }

    @Test
    fun `at least one is satisfied by a target created and linked in the same transaction`() {
        val holder = transactional { AtLeastOne.new { links.add(Target.new()) } }
        transactional {
            val existing = holder.links.toList().single()
            holder.links.remove(existing)
            holder.links.add(Target.new())
        }
        transactional { assertThat(holder.links.toList()).hasSize(1) }
    }

    @Test
    fun `at least one is violated when every linked target is deleted and its link cleared`() {
        val holder = transactional {
            ClearedAtLeastOne.new {
                repeat(3) { links.add(Target.new()) }
            }
        }
        // one target of three: fine
        transactional { holder.links.toList().first().delete() }
        transactional { assertThat(holder.links.toList()).hasSize(2) }
        // deleting all the remaining targets clears every link
        assertCardinalityViolation("links") {
            transactional { holder.links.toList().forEach { it.delete() } }
        }
        transactional { assertThat(holder.links.toList()).hasSize(2) }
    }

    // ---- [0..n] ----------------------------------------------------------------------------

    @Test
    fun `any number of targets is accepted`() {
        val holder = transactional { AnyNumber.new { } }
        transactional { repeat(HIGH_DEGREE) { holder.links.add(Target.new()) } }
        transactional { holder.links.clear() }
    }

    companion object {
        private const val HIGH_DEGREE = 50
    }
}
