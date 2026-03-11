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
package jetbrains.exodus.entitystore.youtrackdb.gremlin

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import org.junit.Test

class GremlinBlockSimplifyTest {

    // --- Not: semantic duals ---

    @Test
    fun `Not of HasLink simplifies to HasNoLink`() {
        assertThat(Not(HasLink("sprint")).simplify()).isEqualTo(HasNoLink("sprint"))
    }

    @Test
    fun `Not of HasNoLink simplifies to HasLink`() {
        assertThat(Not(HasNoLink("sprint")).simplify()).isEqualTo(HasLink("sprint"))
    }

    @Test
    fun `Not of PropNull simplifies to PropNotNull`() {
        assertThat(Not(PropNull("priority")).simplify()).isEqualTo(PropNotNull("priority"))
    }

    @Test
    fun `Not of PropNotNull simplifies to PropNull`() {
        assertThat(Not(PropNotNull("priority")).simplify()).isEqualTo(PropNull("priority"))
    }

    // --- Not: existing cases still work ---

    @Test
    fun `Not of Not eliminates double negation`() {
        val x = PropEqual("status", "open")
        assertThat(Not(Not(x)).simplify()).isEqualTo(x)
    }

    @Test
    fun `Not of All simplifies to None`() {
        assertThat(Not(All).simplify()).isEqualTo(None)
    }

    @Test
    fun `Not of None simplifies to All`() {
        assertThat(Not(None).simplify()).isEqualTo(All)
    }

    // --- And: deduplication ---

    @Test
    fun `And with duplicate operands deduplicates`() {
        val x = PropEqual("status", "open")
        val result = And(x, x).simplify()
        assertThat(result).isEqualTo(x)
    }

    @Test
    fun `And with three operands two of which are duplicates deduplicates`() {
        val x = PropEqual("status", "open")
        val y = PropEqual("priority", "high")
        val result = And(listOf(x, y, x)).simplify()
        assertThat(result).isEqualTo(And(x, y))
    }

    // --- And: contradiction ---

    @Test
    fun `And of HasLink and HasNoLink same link is None`() {
        assertThat(And(HasLink("sprint"), HasNoLink("sprint")).simplify()).isEqualTo(None)
    }

    @Test
    fun `And of HasNoLink and HasLink same link is None`() {
        assertThat(And(HasNoLink("sprint"), HasLink("sprint")).simplify()).isEqualTo(None)
    }

    @Test
    fun `And of PropNull and PropNotNull same property is None`() {
        assertThat(And(PropNull("priority"), PropNotNull("priority")).simplify()).isEqualTo(None)
    }

    @Test
    fun `And of HasLink and HasNoLink different links is not simplified`() {
        val block = And(HasLink("sprint"), HasNoLink("assignee"))
        assertThat(block.simplify()).isNull()
    }

    @Test
    fun `And with Not(HasLink) simplified to HasNoLink triggers contradiction`() {
        // Not(HasLink) → HasNoLink via Not.simplify; And then sees HasLink + HasNoLink → None
        val block = And(HasLink("sprint"), Not(HasLink("sprint")))
        assertThat(block.simplify()).isEqualTo(None)
    }

    // --- Or: deduplication ---

    @Test
    fun `Or with duplicate operands deduplicates`() {
        val x = PropEqual("status", "open")
        val result = Or(x, x).simplify()
        assertThat(result).isEqualTo(x)
    }

    @Test
    fun `Or with three operands two of which are duplicates deduplicates`() {
        val x = PropEqual("status", "open")
        val y = PropEqual("priority", "high")
        val result = Or(listOf(x, y, x)).simplify()
        // O9 does not fire (different properties), dedup gives [x, y]
        assertThat(result).isEqualTo(Or(x, y))
    }

    // --- Or: tautology ---

    @Test
    fun `Or of HasLink and HasNoLink same link is All`() {
        assertThat(Or(HasLink("sprint"), HasNoLink("sprint")).simplify()).isEqualTo(All)
    }

    @Test
    fun `Or of HasNoLink and HasLink same link is All`() {
        assertThat(Or(HasNoLink("sprint"), HasLink("sprint")).simplify()).isEqualTo(All)
    }

    @Test
    fun `Or of PropNull and PropNotNull same property is All`() {
        assertThat(Or(PropNull("priority"), PropNotNull("priority")).simplify()).isEqualTo(All)
    }

    @Test
    fun `Or of HasLink and HasNoLink different links is not simplified`() {
        val block = Or(HasLink("sprint"), HasNoLink("assignee"))
        assertThat(block.simplify()).isNull()
    }

    @Test
    fun `Or with Not(HasNoLink) simplified to HasLink triggers tautology`() {
        // Not(HasNoLink) → HasLink via Not.simplify; Or then sees HasLink + HasNoLink → All
        val block = Or(HasNoLink("sprint"), Not(HasNoLink("sprint")))
        assertThat(block.simplify()).isEqualTo(All)
    }

    // --- Or: dedup interacts correctly with O9 ---

    @Test
    fun `Or with duplicate PropEqual deduplicates before O9 fires yielding single PropEqual`() {
        val x = PropEqual("status", "open")
        // Without dedup: O9 would produce PropWithin("status", ["open","open"])
        // With dedup: Or(x) → x
        assertThat(Or(x, x).simplify()).isEqualTo(x)
    }
}
