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

import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.LinkDirection.IN
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.LinkDirection.OUT
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryShape
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GremlinQueryShapeTest {

    // ---- helpers ----

    private fun issues(block: GremlinBlock = GremlinBlock.All) =
        GremlinQuery.Labeled(GremlinQuery.Where.of(block), "Issue")

    private fun projects(block: GremlinBlock = GremlinBlock.All) =
        GremlinQuery.Labeled(GremlinQuery.Where.of(block), "Project")

    private fun issuesInProject(srcBlock: GremlinBlock) =
        GremlinQuery.Labeled(
            GremlinQuery.FollowLink(projects(srcBlock), IN, "project"),
            "Issue"
        )

    // ---- simple condition shapes ----

    @Test
    fun `Where with PropEqual`() {
        val q = issues(GremlinBlock.PropEqual("status", "open"))
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(Where(PropEqual("status", ?)), "Issue")""")
    }

    @Test
    fun `different values produce same shape`() {
        val q1 = issues(GremlinBlock.PropEqual("status", "open"))
        val q2 = issues(GremlinBlock.PropEqual("status", "resolved"))
        assertThat(GremlinQueryShape.of(q1)).isEqualTo(GremlinQueryShape.of(q2))
    }

    @Test
    fun `different properties produce different shapes`() {
        val q1 = issues(GremlinBlock.PropEqual("status", "open"))
        val q2 = issues(GremlinBlock.PropEqual("priority", "open"))
        assertThat(GremlinQueryShape.of(q1)).isNotEqualTo(GremlinQueryShape.of(q2))
    }

    @Test
    fun `PropWithin`() {
        val q = issues(GremlinBlock.PropWithin("status", listOf("open", "in-progress")))
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(Where(PropWithin("status", ?)), "Issue")""")
    }

    @Test
    fun `PropInRange`() {
        val q = issues(GremlinBlock.PropInRange("estimate", 1, 8))
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(Where(PropInRange("estimate", ?, ?)), "Issue")""")
    }

    @Test
    fun `PropNull and PropNotNull`() {
        val q1 = issues(GremlinBlock.PropNull("assignee"))
        val q2 = issues(GremlinBlock.PropNotNull("assignee"))
        assertThat(GremlinQueryShape.of(q1)).isEqualTo("""Labeled(Where(PropNull("assignee")), "Issue")""")
        assertThat(GremlinQueryShape.of(q2)).isEqualTo("""Labeled(Where(PropNotNull("assignee")), "Issue")""")
    }

    @Test
    fun `HasLink and HasNoLink`() {
        val q1 = issues(GremlinBlock.HasLink("assignee"))
        val q2 = issues(GremlinBlock.HasNoLink("assignee"))
        assertThat(GremlinQueryShape.of(q1)).isEqualTo("""Labeled(Where(HasLink("assignee")), "Issue")""")
        assertThat(GremlinQueryShape.of(q2)).isEqualTo("""Labeled(Where(HasNoLink("assignee")), "Issue")""")
    }

    @Test
    fun `All and None`() {
        val q1 = issues(GremlinBlock.All)
        val q2 = issues(GremlinBlock.None)
        assertThat(GremlinQueryShape.of(q1)).isEqualTo("""Labeled(Where(All), "Issue")""")
        assertThat(GremlinQueryShape.of(q2)).isEqualTo("""Labeled(Where(None), "Issue")""")
    }

    // ---- compound block shapes ----

    @Test
    fun `And with two PropEqual`() {
        val block = GremlinBlock.And(GremlinBlock.PropEqual("status", "open"), GremlinBlock.PropEqual("priority", "critical"))
        val q = issues(block)
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(Where(And(PropEqual("status", ?), PropEqual("priority", ?))), "Issue")""")
    }

    @Test
    fun `Or with two PropEqual`() {
        // O9 coalesces same-property Or to PropWithin — use different properties to keep Or
        val block = GremlinBlock.Or(GremlinBlock.PropEqual("status", "open"), GremlinBlock.HasLink("assignee"))
        val q = issues(block)
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(Where(Or(PropEqual("status", ?), HasLink("assignee"))), "Issue")""")
    }

    @Test
    fun `Not wrapping HasLink`() {
        // Not(HasLink) simplifies to HasNoLink via Not.of()
        val block = GremlinBlock.Not.of(GremlinBlock.HasLink("assignee"))
        val q = issues(block)
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(Where(HasNoLink("assignee")), "Issue")""")
    }

    // ---- FollowLink shapes ----

    @Test
    fun `FollowLink IN`() {
        val q = issuesInProject(GremlinBlock.PropEqual("key", "ENG"))
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue")""")
    }

    @Test
    fun `FollowLink OUT`() {
        val parent = GremlinQuery.Labeled(
            GremlinQuery.FollowLink(issues(), OUT, "parent"),
            "Issue"
        )
        assertThat(GremlinQueryShape.of(parent))
            .isEqualTo("""Labeled(FollowLink(Labeled(Where(All), "Issue"), OUT, "parent"), "Issue")""")
    }

    @Test
    fun `different link names produce different shapes`() {
        val byProject  = issuesInProject(GremlinBlock.PropEqual("key", "ENG"))
        val byAssignee = GremlinQuery.Labeled(
            GremlinQuery.FollowLink(
                GremlinQuery.Labeled(GremlinQuery.Where.of(GremlinBlock.PropEqual("name", "Alice")), "Employee"),
                IN, "assignee"
            ), "Issue"
        )
        assertThat(GremlinQueryShape.of(byProject)).isNotEqualTo(GremlinQueryShape.of(byAssignee))
    }

    // ---- O7-fused AndThen shape ----

    @Test
    fun `O7-fused FollowLink + condition (AndThen)`() {
        val q = issuesInProject(GremlinBlock.PropEqual("key", "ENG"))
            .intersect(issues(GremlinBlock.PropEqual("status", "open")))
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo("""Labeled(AndThen(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), PropEqual("status", ?)), "Issue")""")
    }

    // ---- Aggregate shape ----

    @Test
    fun `Aggregate fallback shape`() {
        val left  = issuesInProject(GremlinBlock.PropEqual("key", "ENG"))
        val right = issuesInProject(GremlinBlock.PropEqual("key", "OPS"))
        // FollowLink \ FollowLink — falls to Aggregate
        val q = left.difference(right)
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo(
                """Aggregate(""" +
                """Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue"), """ +
                """Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue"))"""
            )
    }

    @Test
    fun `Aggregate left and right with same shape collapse to one group key`() {
        val left  = issuesInProject(GremlinBlock.PropEqual("key", "ENG"))
        val right = issuesInProject(GremlinBlock.PropEqual("key", "OPS"))
        val q1 = left.difference(right)
        val q2 = right.difference(left)
        // Both are Aggregate(FL[project], FL[project]) regardless of which side is ENG/OPS
        assertThat(GremlinQueryShape.of(q1)).isEqualTo(GremlinQueryShape.of(q2))
    }

    // ---- UnionAll / Order[Dedup] shape ----

    @Test
    fun `O4-fused union (Order with Dedup)`() {
        // O9 coalesces PropEqual("key","ENG") union PropEqual("key","OPS") → PropWithin("key",?)
        // so the source is a single Labeled(Where(PropWithin)), not UnionAll
        val q = issuesInProject(GremlinBlock.PropEqual("key", "ENG"))
            .union(issuesInProject(GremlinBlock.PropEqual("key", "OPS")))
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo(
                """Dedup(Labeled(FollowLink(Labeled(Where(PropWithin("key", ?)), "Project"), IN, "project"), "Issue"))"""
            )
    }

    @Test
    fun `plain UnionAll fallback`() {
        val q = issues(GremlinBlock.PropEqual("status", "open"))
            .union(issuesInProject(GremlinBlock.PropEqual("key", "ENG")))
        // O11 fires (cond union FL) — produces Labeled(Where(Or(...)))
        // GremlinBlock.andThen() is left-associative: AndThen(AndThen(OutLink, PropEqual), HasLabel)
        assertThat(GremlinQueryShape.of(q))
            .isEqualTo(
                """Labeled(Where(Or(PropEqual("status", ?), """ +
                """Where(AndThen(AndThen(OutLink("project"), PropEqual("key", ?)), HasLabel("Project"))))), "Issue")"""
            )
    }

    // ---- SortBy shape ----

    @Test
    fun `SortBy collapses sort key to ?`() {
        val byPriority = GremlinBlock.Sort(GremlinBlock.Sort.ByProp("priority"), GremlinBlock.SortDirection.ASC)
        val byEstimate = GremlinBlock.Sort(GremlinBlock.Sort.ByProp("estimate"), GremlinBlock.SortDirection.DESC)
        val q1 = GremlinQuery.SortBy(issues(), byPriority)
        val q2 = GremlinQuery.SortBy(issues(), byEstimate)
        // Sort always renders as Sort(‹inner›, ?) regardless of sort key
        assertThat(GremlinQueryShape.of(q1)).isEqualTo(GremlinQueryShape.of(q2))
        assertThat(GremlinQueryShape.of(q1))
            .isEqualTo("""Sort(Labeled(Where(All), "Issue"), ?)""")
    }
}
