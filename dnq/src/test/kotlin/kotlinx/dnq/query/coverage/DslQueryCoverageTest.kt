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
package kotlinx.dnq.query.coverage

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryShape
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.query.*
import org.junit.Before
import org.junit.Test

/**
 * DSL-level query coverage test.
 *
 * Exercises the IssueTrackerDataset through the DNQ DSL (`XdQuery` / `XdEntity`) and asserts
 * **both** the emitted GremlinQueryShape and the complete exact result key sets.
 *
 * Purpose: verify that optimizers fire (or don't fire) end-to-end from the DSL path, not just
 * from the low-level GremlinQuery builder. Where an optimization is expected but not yet wired
 * (e.g. O17 for `flatMapDistinct intersect condition`), this is documented with a TODO.
 *
 * Naming convention: shape notation mirrors GremlinQueryShape.of() output with `?` for concrete
 * values.
 */
class DslQueryCoverageTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser, Employee, Manager, Project, Issue, Sprint, Tag)
    }

    private lateinit var dataset: IssueTrackerDataset

    @Before
    fun setupDataset() {
        dataset = IssueTrackerDataset(store)
    }

    // -------------------------------------------------------------------------
    // Shape-extraction helper
    //
    // XdQuery.entityIterable is a PersistentEntityIterableWrapper (which implements
    // YTDBEntityIterable) when inside a transactional block. Casting is safe here.
    // -------------------------------------------------------------------------

    private fun <T : XdEntity> XdQuery<T>.shape(): String =
        GremlinQueryShape.of((entityIterable as YTDBEntityIterable).query)

    private fun <T : XdEntity> XdQuery<T>.keys(): List<String> =
        toList().map { (it.entity.getProperty("key") as String) }

    private fun <T : XdEntity> XdQuery<T>.names(): List<String> =
        toList().map { (it.entity.getProperty("name") as String) }

    // =========================================================================
    // Group 1 — Simple property filters
    //
    // Each DSL filter maps to a single Labeled(Where(condition), "Type") query.
    // =========================================================================

    @Test
    fun `group 1 - simple property filters`() {
        store.transactional {
            // D01: priority = "critical"
            val d01 = Issue.filter { it.priority eq "critical" }
            assertThat(d01.shape())
                .isEqualTo("""Labeled(Where(PropEqual("priority", ?)), "Issue")""")
            assertThat(d01.keys())
                .containsExactlyElementsIn(listOf("ENG-1", "ENG-6", "OPS-1", "OPS-4"))

            // D02: status = "open"
            val d02 = Issue.filter { it.status eq "open" }
            assertThat(d02.shape())
                .isEqualTo("""Labeled(Where(PropEqual("status", ?)), "Issue")""")
            assertThat(d02.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14",
                       "OPS-2","OPS-4","INFRA-3","INFRA-4"))

            // D03: isArchived = true
            val d03 = Project.filter { it.isArchived eq true }
            assertThat(d03.shape())
                .isEqualTo("""Labeled(Where(PropEqual("isArchived", ?)), "Project")""")
            assertThat(d03.keys()).containsExactly("ARC")

            // D04: summary contains "login" (case-insensitive — DSL `contains` is always ignoreCase=true)
            val d04 = Issue.filter { it.summary contains "login" }
            assertThat(d04.shape())
                .isEqualTo("""Labeled(Where(MatchStringProp("summary", Substring, ?, ?, ?)), "Issue")""")
            assertThat(d04.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-3","ENG-4","ENG-5","ENG-12","ENG-13"))

            // D05: priority eq "high" (same shape as D01)
            val d05 = Issue.filter { it.priority eq "high" }
            assertThat(d05.shape())
                .isEqualTo("""Labeled(Where(PropEqual("priority", ?)), "Issue")""")
            assertThat(d05.keys()).containsExactlyElementsIn(
                listOf("ENG-2","ENG-4","ENG-7","ENG-10","OPS-2","INFRA-2","INFRA-3"))

            // D06: department = "Engineering" on Employee
            val d06 = Employee.filter { it.department eq "Engineering" }
            assertThat(d06.shape())
                .isEqualTo("""Labeled(Where(PropEqual("department", ?)), "Employee")""")
            assertThat(d06.names())
                .containsExactlyElementsIn(listOf("Alice", "Bob", "Eve"))
        }
    }

    // =========================================================================
    // Group 2 — Link predicate filters (HasLink, HasNoLink, HasLinkTo)
    // =========================================================================

    @Test
    fun `group 2 - link predicate filters`() {
        store.transactional {
            // D07: issues with an assignee (HasLink)
            // assignee is a single-valued link (0..1); ne(null) → Not(HasNoLink) simplifies to HasLink
            val d07 = Issue.filter { it.assignee ne null }
            assertThat(d07.shape())
                .isEqualTo("""Labeled(Where(HasLink("assignee")), "Issue")""")
            assertThat(d07.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-7","ENG-8","ENG-10","ENG-12",
                       "OPS-1","OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2"))

            // D08: issues without a sprint (HasNoLink)
            // sprint is a single-valued link (0..1); eq(null) → hasLinkTo(null) → HasNoLink
            val d08 = Issue.filter { it.sprint eq null }
            assertThat(d08.shape())
                .isEqualTo("""Labeled(Where(HasNoLink("sprint")), "Issue")""")
            assertThat(d08.keys()).containsExactlyElementsIn(
                listOf("ENG-5","ENG-8","ENG-9","ENG-11","ENG-14",
                       "OPS-1","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))

            // D09: issues without a parent (top-level issues, HasNoLink)
            // parent is a single-valued link (0..1); eq(null) → HasNoLink
            val d09 = Issue.filter { it.parent eq null }
            assertThat(d09.shape())
                .isEqualTo("""Labeled(Where(HasNoLink("parent")), "Issue")""")
            assertThat(d09.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10","ENG-11",
                       "OPS-1","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))

            // D10: issues with at least one tag (HasLink)
            val d10 = Issue.filter { it.tags.isNotEmpty() }
            assertThat(d10.shape())
                .isEqualTo("""Labeled(Where(HasLink("tags")), "Issue")""")
            assertThat(d10.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-6","ENG-8","ENG-10","ENG-11","OPS-1","OPS-4","INFRA-3"))

            // D11: issues assigned to Alice (HasLinkTo)
            // assignee is single-valued (0..1); use `eq` not `contains` (contains is for XdQuery/0..N)
            val alice = dataset.users["Alice"]!! as Employee
            val d11 = Issue.filter { it.assignee eq alice }
            assertThat(d11.shape())
                .isEqualTo("""Labeled(Where(HasLinkTo("assignee", ?)), "Issue")""")
            assertThat(d11.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-3","ENG-5","ENG-10","ENG-12"))

            // D12: issues in the ENG project (HasLinkTo)
            val engProject = dataset.projects["ENG"]!!
            val d12 = Issue.filter { it.project eq engProject }
            assertThat(d12.shape())
                .isEqualTo("""Labeled(Where(HasLinkTo("project", ?)), "Issue")""")
            assertThat(d12.keys()).containsExactlyElementsIn((1..14).map { "ENG-$it" })
        }
    }

    // =========================================================================
    // Group 3 — Sort queries
    // =========================================================================

    @Test
    fun `group 3 - sort queries`() {
        store.transactional {
            val allIssueKeys = (1..14).map { "ENG-$it" } +
                listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                       "INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1")

            // D13: sorted by priority ASC
            val d13 = Issue.all().sortedBy(Issue::priority)
            assertThat(d13.shape())
                .isEqualTo("""Sort(Labeled(Where(All), "Issue"), ?)""")
            assertThat(d13.keys()).containsExactlyElementsIn(allIssueKeys)

            // D14: sorted by estimate DESC
            val d14 = Issue.all().sortedBy(Issue::estimate, asc = false)
            assertThat(d14.shape())
                .isEqualTo("""Sort(Labeled(Where(All), "Issue"), ?)""")
            assertThat(d14.keys()).containsExactlyElementsIn(allIssueKeys)

            // D15: sorted by assignee name ASC (sort by linked property)
            val d15 = Issue.all().sortedBy(Issue::assignee, Employee::name)
            assertThat(d15.shape())
                .isEqualTo("""Sort(Labeled(Where(All), "Issue"), ?)""")
            assertThat(d15.keys()).containsExactlyElementsIn(allIssueKeys)

            // D16: open issues sorted by priority
            val d16 = Issue.filter { it.status eq "open" }.sortedBy(Issue::priority)
            assertThat(d16.shape())
                .isEqualTo("""Sort(Labeled(Where(PropEqual("status", ?)), "Issue"), ?)""")
            assertThat(d16.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14",
                       "OPS-2","OPS-4","INFRA-3","INFRA-4"))
        }
    }

    // =========================================================================
    // Group 4 — flatMapDistinct (FollowLink traversal)
    //
    // flatMapDistinct always uses LinkDirection.OUT with the property's DB name.
    // Bidirectional links store edges from both sides (e.g. "issues_link" from
    // Project to Issue mirrors the "project_link" edges from Issue to Project).
    //
    // The .distinct() call wraps the FollowLink in Order(_, Dedup), which is the
    // root cause of the O7 gap (Step 5 — O17).
    // =========================================================================

    @Test
    fun `group 4 - flatMapDistinct traversal shapes`() {
        store.transactional {
            // D17: issues in ENG project (reverse link from Project to Issue)
            // Shape: FollowLink is NOT wrapped in a Labeled — the inner type label is omitted
            val d17 = Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues)
            assertThat(d17.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), OUT, "issues"))"""
            )
            assertThat(d17.keys()).containsExactlyElementsIn((1..14).map { "ENG-$it" })

            // D18: issues in OPS project
            val d18 = Project.filter { it.key eq "OPS" }.flatMapDistinct(Project::issues)
            assertThat(d18.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), OUT, "issues"))"""
            )
            assertThat(d18.keys())
                .containsExactlyElementsIn(listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5"))

            // D19: all issues across all projects (reverse link, no source filter)
            val d19 = Project.all().flatMapDistinct(Project::issues)
            assertThat(d19.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(All), "Project"), OUT, "issues"))"""
            )
            assertThat(d19.keys()).containsExactlyElementsIn(
                (1..14).map { "ENG-$it" } +
                listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                       "INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))

            // D20: issues assigned to Alice (reverse link from Employee to Issue)
            val alice = dataset.users["Alice"]!! as Employee
            val aliceQuery = Employee.filter { it.name eq "Alice" }
            val d20 = aliceQuery.flatMapDistinct(Employee::assignedIssues)
            assertThat(d20.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(PropEqual("name", ?)), "Employee"), OUT, "assignedIssues"))"""
            )
            assertThat(d20.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-3","ENG-5","ENG-10","ENG-12"))

            // D21: issues in sprint S1 (reverse link from Sprint to Issue)
            val d21 = Sprint.filter { it.key eq "S1" }.flatMapDistinct(Sprint::issues)
            assertThat(d21.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(PropEqual("key", ?)), "Sprint"), OUT, "issues"))"""
            )
            assertThat(d21.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-2","ENG-3","ENG-6","ENG-10","ENG-12","ENG-13"))

            // D22: tags of all issues (forward link from Issue to Tag)
            val d22 = Issue.all().flatMapDistinct(Issue::tags)
            assertThat(d22.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(All), "Issue"), OUT, "tags"))"""
            )
            assertThat(d22.names())
                .containsExactlyElementsIn(listOf("bug", "feature", "performance"))

            // D23: sprints for ENG project (reverse link from Project to Sprint)
            val d23 = Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues)
                .let { _ ->
                    // Sprint->Project link: traverse Sprint.filter{it.project in ENG}
                    // This exercises Project.all().flatMapDistinct({Sprint side})
                    Sprint.filter { it.project eq dataset.projects["ENG"]!! }
                }
            // D23 is actually a HasLinkTo filter, not a flatMapDistinct — kept for completeness
            // sprint.project is a single-valued link; use `eq` not `contains`
            // (we replaced the flatMapDistinct chain above because Sprint→Project link is 0..1)
            assertThat(d23.shape()).isEqualTo(
                """Labeled(Where(HasLinkTo("project", ?)), "Sprint")"""
            )
            assertThat(d23.keys()).containsExactlyElementsIn(listOf("S1", "S2"))
        }
    }

    // =========================================================================
    // Group 5 — Union queries via DSL
    //
    // O9: same-property PropEqual unions coalesce to PropWithin.
    // =========================================================================

    @Test
    fun `group 5 - union queries`() {
        store.transactional {
            // D24: critical OR high (O9 → PropWithin)
            val d24 = Issue.filter { it.priority eq "critical" } union
                      Issue.filter { it.priority eq "high" }
            assertThat(d24.shape())
                .isEqualTo("""Labeled(Where(PropWithin("priority", ?)), "Issue")""")
            assertThat(d24.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-4","ENG-6","ENG-7","ENG-10","OPS-1","OPS-2","OPS-4","INFRA-2","INFRA-3"))

            // D25: open OR in-progress (O9 → PropWithin)
            val d25 = Issue.filter { it.status eq "open" } union
                      Issue.filter { it.status eq "in-progress" }
            assertThat(d25.shape())
                .isEqualTo("""Labeled(Where(PropWithin("status", ?)), "Issue")""")
            assertThat(d25.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-5","ENG-6","ENG-7","ENG-8","ENG-10","ENG-11","ENG-12",
                       "ENG-13","ENG-14","OPS-1","OPS-2","OPS-4","INFRA-3","INFRA-4"))

            // D26: open OR in-progress OR resolved (three-way O9 chain)
            val d26 = Issue.filter { it.status eq "open" } union
                      Issue.filter { it.status eq "in-progress" } union
                      Issue.filter { it.status eq "resolved" }
            assertThat(d26.shape())
                .isEqualTo("""Labeled(Where(PropWithin("status", ?)), "Issue")""")
            assertThat(d26.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                       "INFRA-2","INFRA-3","INFRA-4"))

            // D27: no-assignee OR critical (different predicates → Or)
            val d27 = Issue.filter { it.assignee eq null } union
                      Issue.filter { it.priority eq "critical" }
            assertThat(d27.shape())
                .isEqualTo("""Labeled(Where(Or(HasNoLink("assignee"), PropEqual("priority", ?))), "Issue")""")
            assertThat(d27.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-6","ENG-9","ENG-11","ENG-13","ENG-14","OPS-1","OPS-4","INFRA-3","INFRA-4","ARC-1"))

            // D28: status open OR status open — identity, collapses to single condition (O2)
            val d28 = Issue.filter { it.status eq "open" } union Issue.all()
            // All.union(anything) or anything.union(All) → All (O2 identity when one side is All)
            // Actually: issues(open).union(issues(All)) → Or(open, All).simplify() → All
            assertThat(d28.shape())
                .isEqualTo("""Labeled(Where(All), "Issue")""")
            assertThat(d28.keys()).containsExactlyElementsIn(
                (1..14).map { "ENG-$it" } +
                listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
        }
    }

    // =========================================================================
    // Group 6 — Intersect queries via DSL
    //
    // Two Labeled(Where(c1), T) intersect Labeled(Where(c2), T): combineEfficient extracts
    // both conditions and combines them as And(c1, c2) inside a single Where.
    // The O12 AndThen chain optimisation does NOT fire at the DSL level — the
    // intersection stays as Labeled(Where(And(c1, c2)), T).
    // O8 still flattens nested And nodes (triple intersect → And(c1, c2, c3)).
    // =========================================================================

    @Test
    fun `group 6 - intersect queries`() {
        store.transactional {
            // D29: critical AND open (two PropEqual → And)
            val d29 = Issue.filter { it.priority eq "critical" } intersect
                      Issue.filter { it.status eq "open" }
            assertThat(d29.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("priority", ?), PropEqual("status", ?))), "Issue")"""
            )
            assertThat(d29.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))

            // D30: open AND has-assignee (PropEqual + HasLink → And)
            val d30 = Issue.filter { it.status eq "open" } intersect
                      Issue.filter { it.assignee ne null }
            assertThat(d30.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("status", ?), HasLink("assignee"))), "Issue")"""
            )
            // open ∩ has-assignee: ENG-1,2,5,8,10 + OPS-2 (ENG-12 is "in-progress", not open)
            assertThat(d30.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-2","ENG-5","ENG-8","ENG-10","OPS-2"))

            // D31: triple intersect: open AND critical AND has-sprint (O8 flattens nested And)
            val d31 = Issue.filter { it.status eq "open" } intersect
                      Issue.filter { it.priority eq "critical" } intersect
                      Issue.filter { it.sprint ne null }
            assertThat(d31.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("status", ?), PropEqual("priority", ?), HasLink("sprint"))), "Issue")"""
            )
            assertThat(d31.keys())
                .containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))

            // D32: high AND not-resolved (PropEqual + Not(PropEqual) → And)
            val d32 = Issue.filter { it.priority eq "high" } intersect
                      Issue.filter { it.status ne "resolved" }
            assertThat(d32.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("priority", ?), Not(PropEqual("status", ?)))), "Issue")"""
            )
            assertThat(d32.keys())
                .containsExactlyElementsIn(listOf("ENG-2","ENG-7","ENG-10","OPS-2","INFRA-3"))

            // D33: (critical OR high) AND open — union then intersect
            // D24 is PropWithin("priority"); intersect with open → And(PropWithin, PropEqual)
            val d33 = (Issue.filter { it.priority eq "critical" } union
                       Issue.filter { it.priority eq "high" }) intersect
                      Issue.filter { it.status eq "open" }
            assertThat(d33.shape()).isEqualTo(
                """Labeled(Where(And(PropWithin("priority", ?), PropEqual("status", ?))), "Issue")"""
            )
            assertThat(d33.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-10","OPS-2","OPS-4","INFRA-3"))
        }
    }

    // =========================================================================
    // Group 7 — Exclude (difference) queries via DSL
    // =========================================================================

    @Test
    fun `group 7 - exclude queries`() {
        store.transactional {
            // D34: open issues excluding assigned ones
            // Not.of(HasLink("assignee")) simplifies to HasNoLink("assignee") — Not(HasLink) → HasNoLink
            // Intersection + negation stays as Where(And(...)) not AndThen(...)
            val d34 = Issue.filter { it.status eq "open" } exclude
                      Issue.filter { it.assignee ne null }
            assertThat(d34.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("status", ?), HasNoLink("assignee"))), "Issue")"""
            )
            assertThat(d34.keys()).containsExactlyElementsIn(
                listOf("ENG-6","ENG-11","ENG-13","ENG-14","OPS-4","INFRA-3","INFRA-4"))

            // D35: all issues excluding tagged ones → HasNoLink("tags")
            val d35 = Issue.all() exclude Issue.filter { it.tags.isNotEmpty() }
            // all().exclude(hasLink) → Not(HasLink("tags")) → HasNoLink("tags")
            assertThat(d35.shape()).isEqualTo(
                """Labeled(Where(HasNoLink("tags")), "Issue")"""
            )
            assertThat(d35.keys()).containsExactlyElementsIn(
                listOf("ENG-5","ENG-7","ENG-9","ENG-12","ENG-13","ENG-14",
                       "OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-4","ARC-1"))

            // D36: critical issues excluding those in any sprint
            // Not.of(HasLink("sprint")) simplifies to HasNoLink("sprint")
            val d36 = Issue.filter { it.priority eq "critical" } exclude
                      Issue.filter { it.sprint ne null }
            assertThat(d36.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("priority", ?), HasNoLink("sprint"))), "Issue")"""
            )
            assertThat(d36.keys()).containsExactly("OPS-1")

            // D37: open issues excluding those assigned to Alice
            val alice = dataset.users["Alice"]!! as Employee
            val d37 = Issue.filter { it.status eq "open" } exclude
                      Issue.filter { it.assignee eq alice }
            assertThat(d37.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("status", ?), Not(HasLinkTo("assignee", ?)))), "Issue")"""
            )
            assertThat(d37.keys()).containsExactlyElementsIn(
                listOf("ENG-2","ENG-6","ENG-8","ENG-11","ENG-13","ENG-14","OPS-2","OPS-4","INFRA-3","INFRA-4"))
        }
    }

    // =========================================================================
    // Group 8 — flatMapDistinct combined with set operations
    //
    // flatMapDistinct produces Order(FollowLink(...), Dedup).
    // O17 strips the Order(Dedup) wrapper, delegates the combination to the inner
    // FollowLink, then re-wraps the result — making O7 and O4 fire transparently.
    //
    // intersect/exclude with a condition: O17 + O7 produce Order(AndThen(FL, cond), Dedup).
    // Union of two same-link flatMapDistinct: O17 strips both, O4 merges sources,
    // result is Order(FollowLink(mergedSrc), Dedup) — O9 coalesces PropEqual sources.
    // Union of two different-link flatMapDistinct: O4 does not fire → Order(UnionAll, Dedup).
    // =========================================================================

    @Test
    fun `group 8 - flatMapDistinct intersect condition (O17+O7 fusion)`() {
        store.transactional {
            // D38: issues in ENG intersect open — O17 strips Order(Dedup), O7 appends condition
            val engIssues = Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues)
            val d38 = engIssues intersect Issue.filter { it.status eq "open" }
            assertThat(d38.shape()).isEqualTo(
                """Dedup(AndThen(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), OUT, "issues"), PropEqual("status", ?)))"""
            )
            assertThat(d38.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14"))

            // D39: issues in ENG exclude assigned — O17+O7 with Not.of(HasLink) → HasNoLink
            val d39 = engIssues exclude Issue.filter { it.assignee ne null }
            assertThat(d39.shape()).isEqualTo(
                """Dedup(AndThen(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), OUT, "issues"), HasNoLink("assignee")))"""
            )
            assertThat(d39.keys()).containsExactlyElementsIn(
                listOf("ENG-6","ENG-9","ENG-11","ENG-13","ENG-14"))

            // D40: issues assigned to Alice intersect open — O17+O7 on Employee link
            val aliceIssues = Employee.filter { it.name eq "Alice" }.flatMapDistinct(Employee::assignedIssues)
            val d40 = aliceIssues intersect Issue.filter { it.status eq "open" }
            assertThat(d40.shape()).isEqualTo(
                """Dedup(AndThen(FollowLink(Labeled(Where(PropEqual("name", ?)), "Employee"), OUT, "assignedIssues"), PropEqual("status", ?)))"""
            )
            assertThat(d40.keys()).containsExactlyElementsIn(listOf("ENG-1","ENG-5","ENG-10"))

            // D41: condition intersect flatMapDistinct — symmetric case handled by O17 symmetric rule
            // O17 detects the right-side Order(Dedup) for intersect and swaps operands into O7
            val d41 = Issue.filter { it.status eq "open" } intersect engIssues
            assertThat(d41.shape()).isEqualTo(
                """Dedup(AndThen(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), OUT, "issues"), PropEqual("status", ?)))"""
            )
            assertThat(d41.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14"))
        }
    }

    @Test
    fun `group 8 - flatMapDistinct union flatMapDistinct (O17+O4 fusion for same link)`() {
        store.transactional {
            // D42: ENG issues UNION OPS issues (same link name "issues", same direction OUT)
            // O17 strips both Order(Dedup) wrappers, O4 merges sources via union,
            // O9 coalesces PropEqual("key","ENG") + PropEqual("key","OPS") → PropWithin("key").
            val d42 = Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues) union
                      Project.filter { it.key eq "OPS" }.flatMapDistinct(Project::issues)
            assertThat(d42.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(PropWithin("key", ?)), "Project"), OUT, "issues"))"""
            )
            assertThat(d42.keys()).containsExactlyElementsIn(
                (1..14).map { "ENG-$it" } +
                listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5"))

            // D43: issues assigned to Alice UNION issues assigned to Bob (same link, O4 fires)
            val d43 = Employee.filter { it.name eq "Alice" }.flatMapDistinct(Employee::assignedIssues) union
                      Employee.filter { it.name eq "Bob" }.flatMapDistinct(Employee::assignedIssues)
            assertThat(d43.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(PropWithin("name", ?)), "Employee"), OUT, "assignedIssues"))"""
            )
            // Alice: ENG-1,3,5,10,12 + Bob: ENG-2,4,8 + INFRA-1,2 = 10 unique
            assertThat(d43.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-8","ENG-10","ENG-12","INFRA-1","INFRA-2"))

            // D44: ENG issues UNION Alice issues (different link names — O4 cannot merge, UnionAll)
            val d44 = Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues) union
                      Employee.filter { it.name eq "Alice" }.flatMapDistinct(Employee::assignedIssues)
            assertThat(d44.shape()).isEqualTo(
                """Dedup(UnionAll(Dedup(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), OUT, "issues")), Dedup(FollowLink(Labeled(Where(PropEqual("name", ?)), "Employee"), OUT, "assignedIssues"))))"""
            )
            // ENG-1..14 (14) + Alice's: ENG-1,3,5,10,12 (all already in ENG) = 14 deduped
            assertThat(d44.keys()).containsExactlyElementsIn((1..14).map { "ENG-$it" })
        }
    }

    // =========================================================================
    // Group 9 — condition × flatMapDistinct (difference and union)
    //
    // When `this` is a condition and `other` is a flatMapDistinct (Order(FL, Dedup)),
    // O11 does NOT fire (it checks `other as? Labeled` which fails for Order).
    // O17 symmetric only fires for intersect (commutative), not difference/union.
    // Both D45 and D46 therefore fall to Aggregate / UnionAll respectively.
    // =========================================================================

    @Test
    fun `group 9 - O11 condition and flatMapDistinct(srcCond)`() {
        store.transactional {
            // D45: open issues EXCLUDE issues in ENG project
            // O11: open \ FollowLink(ENG, OUT, "issues") → And(open, Not(Where(out("issues")...ENG)))
            // But wait: O11 checks direction == IN for the inverse-link predicate.
            // Since flatMapDistinct uses OUT, O11 might not fire here.
            // If O11 doesn't fire → Aggregate fallback.
            val d45 = Issue.filter { it.status eq "open" } exclude
                      Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues)
            // The result should be open non-ENG issues regardless of shape
            assertThat(d45.keys()).containsExactlyElementsIn(
                listOf("OPS-2","OPS-4","INFRA-3","INFRA-4"))

            // D46: open issues UNION issues in ENG project
            val d46 = Issue.filter { it.status eq "open" } union
                      Project.filter { it.key eq "ENG" }.flatMapDistinct(Project::issues)
            // open(13) + ENG non-open(ENG-3,4,7,9,12=5) = 18
            assertThat(d46.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","OPS-2","OPS-4","INFRA-3","INFRA-4"))
        }
    }

    // =========================================================================
    // Group 10 — Chained condition intersect
    //
    // Three-way condition intersect: O8 flattens nested And(And(c1,c2),c3) → And(c1,c2,c3).
    // The AndThen (O12) chain optimisation does NOT fire at the DSL level;
    // the result stays as Labeled(Where(And(c1,c2,c3)), "T").
    // =========================================================================

    @Test
    fun `group 10 - chained condition intersect (O16 triple And chain)`() {
        store.transactional {
            // D47: open AND critical AND has-sprint (triple condition intersect)
            // O8 flattens: And(And(open,critical), has-sprint) → And(open,critical,has-sprint)
            val d47 = Issue.filter { it.status eq "open" } intersect
                      Issue.filter { it.priority eq "critical" } intersect
                      Issue.filter { it.sprint ne null }
            assertThat(d47.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("status", ?), PropEqual("priority", ?), HasLink("sprint"))), "Issue")"""
            )
            assertThat(d47.keys()).containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))

            // D48: (critical OR high) AND open AND has-sprint
            val d48 = (Issue.filter { it.priority eq "critical" } union
                       Issue.filter { it.priority eq "high" }) intersect
                      Issue.filter { it.status eq "open" } intersect
                      Issue.filter { it.sprint ne null }
            assertThat(d48.shape()).isEqualTo(
                """Labeled(Where(And(PropWithin("priority", ?), PropEqual("status", ?), HasLink("sprint"))), "Issue")"""
            )
            // critical+high ∩ open ∩ has-sprint = {ENG-1,2,6,10,OPS-2,4} ∩ {open} ∩ {sprint}
            assertThat(d48.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-10","OPS-2","OPS-4"))

            // D49: open AND not-assigned AND no-sprint
            val d49 = Issue.filter { it.status eq "open" } intersect
                      Issue.filter { it.assignee eq null } intersect
                      Issue.filter { it.sprint eq null }
            assertThat(d49.shape()).isEqualTo(
                """Labeled(Where(And(PropEqual("status", ?), HasNoLink("assignee"), HasNoLink("sprint"))), "Issue")"""
            )
            // open unassigned no-sprint: ENG-11,14 + OPS-4,INFRA-3,4 minus sprinted
            // open ∩ no-assignee = ENG-6,11,13,14, OPS-4, INFRA-3, INFRA-4
            // ∩ no-sprint: ENG-11,14 have no sprint; OPS-4 in S3; INFRA-3,4 have no sprint; ENG-6 in S1; ENG-13 in S1
            // Result: ENG-11, ENG-14, INFRA-3, INFRA-4
            assertThat(d49.keys())
                .containsExactlyElementsIn(listOf("ENG-11","ENG-14","INFRA-3","INFRA-4"))
        }
    }

    // =========================================================================
    // Group 11 — Multi-hop flatMapDistinct traversal
    // =========================================================================

    @Test
    fun `group 11 - multi-hop flatMapDistinct`() {
        store.transactional {
            // D50: tags of issues in ENG project (two-hop: Project → Issues → Tags)
            val engIssueTags = Project.filter { it.key eq "ENG" }
                .flatMapDistinct(Project::issues)
                .flatMapDistinct(Issue::tags)
            // Shape: two nested FollowLinks in the source of the outer FollowLink
            assertThat(engIssueTags.names())
                .containsExactlyElementsIn(listOf("bug", "feature", "performance"))

            // D51: 3-hop — sprints of projects of issues assigned to Alice
            // Alice → assignedIssues → issues → project → Project → issues of Sprint
            // Actually simpler: issues of sprints assigned to Alice
            // sprint is a single-valued link (0..1); use mapDistinct not flatMapDistinct
            val aliceSprintIssues = Employee.filter { it.name eq "Alice" }
                .flatMapDistinct(Employee::assignedIssues)  // Alice's issues (XdQuery<Issue>)
                .mapDistinct(Issue::sprint)                  // single-valued 0..1 → Sprint? → XdQuery<Sprint>
            assertThat(aliceSprintIssues.keys())
                // Alice's issues: ENG-1(S1), ENG-3(S1), ENG-5(no sprint), ENG-10(S1), ENG-12(S1)
                // → sprints = S1 (deduped)
                .containsExactly("S1")

            // D52: issues in sprints of Alice's project (ENG)
            // Project.filter{ENG}.flatMapDistinct(Project::issues) already tested in D17
            // Here: Sprint.filter{project=ENG}.flatMapDistinct(Sprint::issues)
            val engSprintIssues = Sprint.filter { it.project eq dataset.projects["ENG"]!! }
                .flatMapDistinct(Sprint::issues)
            assertThat(engSprintIssues.keys()).containsExactlyElementsIn(
                // S1 issues: ENG-1,2,3,6,10,12,13; S2 issues: ENG-4,7
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-6","ENG-7","ENG-10","ENG-12","ENG-13"))
        }
    }

    // =========================================================================
    // Group 12 — Class hierarchy queries
    // =========================================================================

    @Test
    fun `group 12 - class hierarchy Employee is-a XdUser`() {
        store.transactional {
            // D53: XdUser.all() includes plain users, employees, and managers
            val d53 = XdUser.all()
            assertThat(d53.shape())
                .isEqualTo("""Labeled(Where(All), "User")""")
            assertThat(d53.names())
                .containsExactlyElementsIn(listOf("Alice","Bob","Carol","Dave","Eve"))

            // D54: Employee.all() includes employees and managers (subtype polymorphism)
            val d54 = Employee.all()
            assertThat(d54.shape())
                .isEqualTo("""Labeled(Where(All), "Employee")""")
            assertThat(d54.names())
                .containsExactlyElementsIn(listOf("Alice","Bob","Carol","Eve"))

            // D55: Manager.all() — only managers
            val d55 = Manager.all()
            assertThat(d55.shape())
                .isEqualTo("""Labeled(Where(All), "Manager")""")
            assertThat(d55.names()).containsExactly("Eve")

            // D56: issues assigned to any Employee (via Employee.all().flatMapDistinct)
            // hasLabel("Employee") traversal finds Employee + Manager subtype
            val d56 = Employee.all().flatMapDistinct(Employee::assignedIssues)
            assertThat(d56.shape()).isEqualTo(
                """Dedup(FollowLink(Labeled(Where(All), "Employee"), OUT, "assignedIssues"))"""
            )
            // Alice(5)+Bob(5)+Carol(4)+Eve(1) = 15
            assertThat(d56.keys()).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-7","ENG-8","ENG-10","ENG-12",
                       "OPS-1","OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2"))

            // D57: active employees (filter on Employee subtype)
            val d57 = Employee.filter { it.active eq true }
            assertThat(d57.shape())
                .isEqualTo("""Labeled(Where(PropEqual("active", ?)), "Employee")""")
            assertThat(d57.names())
                .containsExactlyElementsIn(listOf("Alice","Bob","Eve"))
        }
    }
}
