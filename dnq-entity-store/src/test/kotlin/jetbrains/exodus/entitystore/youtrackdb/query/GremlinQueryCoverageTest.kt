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

import com.google.common.truth.Truth.assertThat
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.ByIds
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.FollowLink
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.Labeled
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.LinkDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.NestedCondition
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.ReversedOrder
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.SortBy
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.Where
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph
import org.junit.Test

/**
 * Coverage test for GremlinQuery / GremlinBlock query building.
 *
 * Data model:
 *
 *   User          (name: String, email: String, active: Boolean)
 *   └── Employee  (+ department: String, salary: Long)
 *       └── Manager (+ reportsCount: Int)
 *
 *   Project  (name: String, key: String, isArchived: Boolean)
 *   Issue    (summary: String, priority: String, status: String, estimate: Int)
 *   Tag      (name: String, color: String)
 *   Sprint   (name: String, state: String, velocity: Int)
 *
 * Links:
 *   Issue  --project-->  Project
 *   Issue  --assignee--> User
 *   Issue  --reporter--> Employee
 *   Issue  --tags-->     Tag      (multi-value)
 *   Issue  --sprint-->   Sprint
 *   Issue  --parent-->   Issue    (self-referential; subtasks)
 *   Project --lead-->    Employee
 *   Sprint  --project--> Project
 *
 * No real database is used. Tests assert the Gremlin string produced by each query
 * using GroovyTranslator on top of EmptyGraph.
 */
class GremlinQueryCoverageTest {

    private val queryTranslator = GroovyTranslator.of("g")
    private val gs = EmptyGraph.instance().traversal()

    private fun GremlinQuery.toGremlin(): String =
        queryTranslator.translate(start(gs).asAdmin().bytecode).script

    // -------------------------------------------------------------------------
    // Model helpers — thin wrappers to keep query definitions readable
    // -------------------------------------------------------------------------

    private fun issues(condition: GremlinBlock = All) = Labeled(Where.of(condition), "Issue")
    private fun projects(condition: GremlinBlock = All) = Labeled(Where.of(condition), "Project")
    private fun sprints(condition: GremlinBlock = All) = Labeled(Where.of(condition), "Sprint")
    private fun users(condition: GremlinBlock = All) = Labeled(Where.of(condition), "User")
    private fun employees(condition: GremlinBlock = All) = Labeled(Where.of(condition), "Employee")

    // Representative RIDs used as stand-ins for specific entity IDs
    private val userRid    = RID.of(10, 1)
    private val userRid2   = RID.of(10, 2)
    private val projectRid = RID.of(20, 1)
    private val projectRid2 = RID.of(20, 2)
    private val issueRid1  = RID.of(30, 1)
    private val issueRid2  = RID.of(30, 2)
    private val issueRid3  = RID.of(30, 3)
    private val tagRid     = RID.of(40, 1)
    private val sprintRid  = RID.of(50, 1)
    private val sprintRid2 = RID.of(50, 2)

    // FollowLink helpers — "issues reachable via link from source entities matching cond"
    // Direction is always IN: source vertices have the outgoing link, we traverse it backwards.
    private fun issuesInProject(cond: GremlinBlock = All) =
        Labeled(FollowLink(projects(cond), LinkDirection.IN, "project"), "Issue")

    private fun issuesAssignedTo(cond: GremlinBlock = All) =
        Labeled(FollowLink(employees(cond), LinkDirection.IN, "assignee"), "Issue")

    private fun issuesInSprint(cond: GremlinBlock = All) =
        Labeled(FollowLink(sprints(cond), LinkDirection.IN, "sprint"), "Issue")

    // Sort blocks reused across tests
    private val byPriority = Sort(Sort.ByProp("priority"), SortDirection.ASC)
    private val byEstimate = Sort(Sort.ByProp("estimate"), SortDirection.DESC)
    private val byAssigneeName = Sort(Sort.ByLinked("assignee", "name"), SortDirection.ASC)

    private val byPriorityGremlin =
        """.order().by(__.values("priority").count(),Order.desc).by(__.values("priority").fold(),Order.asc)"""
    private val byEstimateGremlin =
        """.order().by(__.values("estimate").count(),Order.desc).by(__.values("estimate").fold(),Order.desc)"""
    private val byAssigneeNameGremlin =
        """.order().by(__.out("assignee_link").values("name").count(),Order.desc).by(__.out("assignee_link").values("name").fold(),Order.asc)"""

    // =========================================================================
    // Group 1 — Simple property queries
    // =========================================================================

    @Test
    fun `group 1 - simple property queries`() {

        // Q01: Issues with priority = "critical"
        val q01 = issues(PropEqual("priority", "critical"))
        println("[Q01 critical issues] query  : $q01")
        println("[Q01 critical issues] gremlin: ${q01.toGremlin()}")
        assertThat(q01.toGremlin())
            .isEqualTo("""g.V().has("priority","critical").hasLabel("Issue")""")

        // Q02: Issues with status = "open"
        val q02 = issues(PropEqual("status", "open"))
        println("[Q02 open issues] query  : $q02")
        println("[Q02 open issues] gremlin: ${q02.toGremlin()}")
        assertThat(q02.toGremlin())
            .isEqualTo("""g.V().has("status","open").hasLabel("Issue")""")

        // Q03: Issues where estimate is in range [1, 8]
        val q03 = issues(PropInRange("estimate", 1, 8))
        println("[Q03 estimate in range] query  : $q03")
        println("[Q03 estimate in range] gremlin: ${q03.toGremlin()}")
        assertThat(q03.toGremlin())
            .isEqualTo("""g.V().has("estimate",P.gte((int) 1).and(P.lte((int) 8))).hasLabel("Issue")""")

        // Q04: Projects that are archived (isArchived = true)
        val q04 = projects(PropEqual("isArchived", true))
        println("[Q04 archived projects] query  : $q04")
        println("[Q04 archived projects] gremlin: ${q04.toGremlin()}")
        assertThat(q04.toGremlin())
            .isEqualTo("""g.V().has("isArchived",true).hasLabel("Project")""")

        // Q05: Employees in the "Engineering" department
        val q05 = employees(PropEqual("department", "Engineering"))
        println("[Q05 engineering employees] query  : $q05")
        println("[Q05 engineering employees] gremlin: ${q05.toGremlin()}")
        assertThat(q05.toGremlin())
            .isEqualTo("""g.V().has("department","Engineering").hasLabel("Employee")""")

        // Q06: Issues with priority within ["critical", "high"]
        val q06 = issues(PropWithin("priority", listOf("critical", "high")))
        println("[Q06 priority within critical,high] query  : $q06")
        println("[Q06 priority within critical,high] gremlin: ${q06.toGremlin()}")
        assertThat(q06.toGremlin())
            .isEqualTo("""g.V().has("priority",P.within(["critical", "high"])).hasLabel("Issue")""")

        // Q07: Issues where summary contains "login" (substring, case-insensitive)
        val q07 = issues(MatchStringProp("summary", StringCompare.Substring, "login", isCollection = false, caseSensitive = false))
        println("[Q07 summary contains login] query  : $q07")
        println("[Q07 summary contains login] gremlin: ${q07.toGremlin()}")
        assertThat(q07.toGremlin())
            .isEqualTo("""g.V().where(__.values("summary").toLower().is(TextP.containing("login"))).hasLabel("Issue")""")

        // Q08: Issues where summary starts with "Bug:" (prefix, case-insensitive)
        // The value is lowercased in the predicate since caseSensitive = false.
        val q08 = issues(MatchStringProp("summary", StringCompare.Prefix, "Bug:", isCollection = false, caseSensitive = false))
        println("[Q08 summary starts with Bug:] query  : $q08")
        println("[Q08 summary starts with Bug:] gremlin: ${q08.toGremlin()}")
        assertThat(q08.toGremlin())
            .isEqualTo("""g.V().where(__.values("summary").toLower().is(TextP.startingWith("bug:"))).hasLabel("Issue")""")

        // Q09: Issues where summary ends with "crash" (suffix, case-insensitive)
        val q09 = issues(MatchStringProp("summary", StringCompare.Suffix, "crash", isCollection = false, caseSensitive = false))
        println("[Q09 summary ends with crash] query  : $q09")
        println("[Q09 summary ends with crash] gremlin: ${q09.toGremlin()}")
        assertThat(q09.toGremlin())
            .isEqualTo("""g.V().where(__.values("summary").toLower().is(TextP.endingWith("crash"))).hasLabel("Issue")""")

        // Q10: Active users (active = true) — User label with a boolean property filter
        val q10 = users(PropEqual("active", true))
        println("[Q10 active users] query  : $q10")
        println("[Q10 active users] gremlin: ${q10.toGremlin()}")
        assertThat(q10.toGremlin())
            .isEqualTo("""g.V().has("active",true).hasLabel("User")""")
    }

    // =========================================================================
    // Group 2 — Link-based queries
    // =========================================================================

    @Test
    fun `group 2 - link-based queries`() {

        // Q11: Issues that have an assignee (HasLink)
        val q11 = issues(HasLink("assignee"))
        println("[Q11 issues with assignee] query  : $q11")
        println("[Q11 issues with assignee] gremlin: ${q11.toGremlin()}")
        assertThat(q11.toGremlin())
            .isEqualTo("""g.V().where(__.out("assignee_link")).hasLabel("Issue")""")

        // Q12: Issues with no assignee (HasNoLink)
        val q12 = issues(HasNoLink("assignee"))
        println("[Q12 issues without assignee] query  : $q12")
        println("[Q12 issues without assignee] gremlin: ${q12.toGremlin()}")
        assertThat(q12.toGremlin())
            .isEqualTo("""g.V().not(__.out("assignee_link")).hasLabel("Issue")""")

        // Q13: Issues with no sprint (HasNoLink)
        val q13 = issues(HasNoLink("sprint"))
        println("[Q13 issues without sprint] query  : $q13")
        println("[Q13 issues without sprint] gremlin: ${q13.toGremlin()}")
        assertThat(q13.toGremlin())
            .isEqualTo("""g.V().not(__.out("sprint_link")).hasLabel("Issue")""")

        // Q14: Issues that are subtasks (have a parent link)
        val q14 = issues(HasLink("parent"))
        println("[Q14 subtask issues] query  : $q14")
        println("[Q14 subtask issues] gremlin: ${q14.toGremlin()}")
        assertThat(q14.toGremlin())
            .isEqualTo("""g.V().where(__.out("parent_link")).hasLabel("Issue")""")

        // Q15: Issues that are top-level (no parent link)
        val q15 = issues(HasNoLink("parent"))
        println("[Q15 top-level issues] query  : $q15")
        println("[Q15 top-level issues] gremlin: ${q15.toGremlin()}")
        assertThat(q15.toGremlin())
            .isEqualTo("""g.V().not(__.out("parent_link")).hasLabel("Issue")""")

        // Q16: Issues that have at least one tag (HasLink)
        val q16 = issues(HasLink("tags"))
        println("[Q16 issues with tags] query  : $q16")
        println("[Q16 issues with tags] gremlin: ${q16.toGremlin()}")
        assertThat(q16.toGremlin())
            .isEqualTo("""g.V().where(__.out("tags_link")).hasLabel("Issue")""")

        // Q17: Issues assigned to a specific user (HasLinkTo by RID)
        val q17 = issues(HasLinkTo("assignee", userRid))
        println("[Q17 issues assigned to user] query  : $q17")
        println("[Q17 issues assigned to user] gremlin: ${q17.toGremlin()}")
        assertThat(q17.toGremlin())
            .isEqualTo("""g.V().where(__.out("assignee_link").hasId(#10:1)).hasLabel("Issue")""")

        // Q18: Issues in a specific project (HasLinkTo by RID)
        val q18 = issues(HasLinkTo("project", projectRid))
        println("[Q18 issues in project] query  : $q18")
        println("[Q18 issues in project] gremlin: ${q18.toGremlin()}")
        assertThat(q18.toGremlin())
            .isEqualTo("""g.V().where(__.out("project_link").hasId(#20:1)).hasLabel("Issue")""")
    }

    // =========================================================================
    // Group 3 — ByIds queries
    // =========================================================================

    @Test
    fun `group 3 - ByIds queries`() {

        // Q19: Fetch two specific issues by RID
        val q19 = ByIds(listOf(issueRid1, issueRid2))
        println("[Q19 fetch by ids] query  : $q19")
        println("[Q19 fetch by ids] gremlin: ${q19.toGremlin()}")
        assertThat(q19.toGremlin())
            .isEqualTo("""g.V(#30:1,#30:2)""")

        // Q20: ByIds union ByIds — merge two RID sets
        // combineEfficient handles ByIds+ByIds → ByIds(union of ids)
        val q20 = ByIds(listOf(issueRid1)).union(ByIds(listOf(issueRid2)))
        println("[Q20 byids union byids] query  : $q20")
        println("[Q20 byids union byids] gremlin: ${q20.toGremlin()}")
        assertThat(q20.toGremlin())
            .isEqualTo("""g.V(#30:1,#30:2)""")

        // Q21: ByIds intersect ByIds — common RIDs only
        // issueRid2 is in both sets; result is ByIds([issueRid2])
        val q21 = ByIds(listOf(issueRid1, issueRid2)).intersect(ByIds(listOf(issueRid2, issueRid3)))
        println("[Q21 byids intersect byids] query  : $q21")
        println("[Q21 byids intersect byids] gremlin: ${q21.toGremlin()}")
        assertThat(q21.toGremlin())
            .isEqualTo("""g.V(#30:2)""")

        // Q22: ByIds difference ByIds — first set minus second
        // issueRid1 remains after removing issueRid2
        val q22 = ByIds(listOf(issueRid1, issueRid2)).difference(ByIds(listOf(issueRid2)))
        println("[Q22 byids difference byids] query  : $q22")
        println("[Q22 byids difference byids] gremlin: ${q22.toGremlin()}")
        assertThat(q22.toGremlin())
            .isEqualTo("""g.V(#30:1)""")
    }

    // =========================================================================
    // Group 4 — Sort and slice queries
    // =========================================================================

    @Test
    fun `group 4 - sort and slice queries`() {

        // Q23: All issues sorted by priority ascending
        val q23 = SortBy(issues(), byPriority)
        println("[Q23 issues sorted by priority] query  : $q23")
        println("[Q23 issues sorted by priority] gremlin: ${q23.toGremlin()}")
        assertThat(q23.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue")$byPriorityGremlin""")

        // Q24: All issues sorted by estimate descending
        val q24 = SortBy(issues(), byEstimate)
        println("[Q24 issues sorted by estimate desc] query  : $q24")
        println("[Q24 issues sorted by estimate desc] gremlin: ${q24.toGremlin()}")
        assertThat(q24.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue")$byEstimateGremlin""")

        // Q25: Issues sorted by assignee name (sort by linked property)
        val q25 = SortBy(issues(), byAssigneeName)
        println("[Q25 issues sorted by assignee name] query  : $q25")
        println("[Q25 issues sorted by assignee name] gremlin: ${q25.toGremlin()}")
        assertThat(q25.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue")$byAssigneeNameGremlin""")

        // Q26: All issues, skip 10 (pagination offset)
        // then(Skip(10)) → Slice.of(issues(), Skip(10)) → Slice(issues(), Skip(10))
        val q26 = issues().then(Skip(10))
        println("[Q26 issues skip 10] query  : $q26")
        println("[Q26 issues skip 10] gremlin: ${q26.toGremlin()}")
        assertThat(q26.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue").skip(10L)""")

        // Q27: All issues, limit 20 (page size)
        val q27 = issues().then(Limit(20))
        println("[Q27 issues limit 20] query  : $q27")
        println("[Q27 issues limit 20] gremlin: ${q27.toGremlin()}")
        assertThat(q27.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue").limit(20L)""")

        // Q28: All issues, skip 10, limit 5 (pagination window — slice composition)
        // Slice.of(Slice.of(issues(), Skip(10)), Limit(5)) collapses inner slices
        // into a single Slice with combined block: Skip(10).andThen(Limit(5))
        val q28 = issues().then(Skip(10)).then(Limit(5))
        println("[Q28 issues skip 10 limit 5] query  : $q28")
        println("[Q28 issues skip 10 limit 5] gremlin: ${q28.toGremlin()}")
        assertThat(q28.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue").skip(10L).limit(5L)""")

        // Q29: Last 5 issues (tail)
        val q29 = issues().then(Tail(5))
        println("[Q29 last 5 issues] query  : $q29")
        println("[Q29 last 5 issues] gremlin: ${q29.toGremlin()}")
        assertThat(q29.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue").tail(5L)""")
    }

    // =========================================================================
    // Group 5 — Union queries
    // =========================================================================

    @Test
    fun `group 5 - union queries`() {

        // Q30: Critical OR high priority issues
        // O9: both PropEqual on same property → PropWithin
        val q30 = issues(PropEqual("priority", "critical"))
            .union(issues(PropEqual("priority", "high")))
        println("[Q30 critical or high] query  : $q30")
        println("[Q30 critical or high] gremlin: ${q30.toGremlin()}")
        assertThat(q30.toGremlin())
            .isEqualTo("""g.V().has("priority",P.within(["critical", "high"])).hasLabel("Issue")""")

        // Q31: Open OR in-progress issues
        // O9: both PropEqual on same property → PropWithin
        val q31 = issues(PropEqual("status", "open"))
            .union(issues(PropEqual("status", "in-progress")))
        println("[Q31 open or in-progress] query  : $q31")
        println("[Q31 open or in-progress] gremlin: ${q31.toGremlin()}")
        assertThat(q31.toGremlin())
            .isEqualTo("""g.V().has("status",P.within(["open", "in-progress"])).hasLabel("Issue")""")

        // Q32: Issues with no assignee OR with critical priority
        val q32 = issues(HasNoLink("assignee"))
            .union(issues(PropEqual("priority", "critical")))
        println("[Q32 unassigned or critical] query  : $q32")
        println("[Q32 unassigned or critical] gremlin: ${q32.toGremlin()}")
        assertThat(q32.toGremlin())
            .isEqualTo("""g.V().or(__.not(__.out("assignee_link")),__.has("priority","critical")).hasLabel("Issue")""")

        // Q33: Issues in project A OR project B (two HasLinkTo predicates)
        val q33 = issues(HasLinkTo("project", projectRid))
            .union(issues(HasLinkTo("project", projectRid2)))
        println("[Q33 issues in project A or B] query  : $q33")
        println("[Q33 issues in project A or B] gremlin: ${q33.toGremlin()}")
        assertThat(q33.toGremlin())
            .isEqualTo("""g.V().or(__.where(__.out("project_link").hasId(#20:1)),__.where(__.out("project_link").hasId(#20:2))).hasLabel("Issue")""")

        // Q34: Issues assigned to user A OR user B (ByIds union, both sets single-element)
        val q34 = ByIds(listOf(issueRid1)).union(ByIds(listOf(issueRid2)))
        println("[Q34 byids union] query  : $q34")
        println("[Q34 byids union] gremlin: ${q34.toGremlin()}")
        assertThat(q34.toGremlin())
            .isEqualTo("""g.V(#30:1,#30:2)""")

        // Q35: Open OR in-progress OR resolved — three-way union
        // First union: O9 → PropWithin("status", [open, in-progress])
        // Second union: PropWithin ∪ PropEqual same property → O9 extends → PropWithin("status", [open, in-progress, resolved])
        val q35 = issues(PropEqual("status", "open"))
            .union(issues(PropEqual("status", "in-progress")))
            .union(issues(PropEqual("status", "resolved")))
        println("[Q35 open or in-progress or resolved] query  : $q35")
        println("[Q35 open or in-progress or resolved] gremlin: ${q35.toGremlin()}")
        assertThat(q35.toGremlin())
            .isEqualTo("""g.V().has("status",P.within(["open", "in-progress", "resolved"])).hasLabel("Issue")""")

        // Q36: Issues in sprint A OR issues with no sprint
        val q36 = issues(HasLinkTo("sprint", sprintRid))
            .union(issues(HasNoLink("sprint")))
        println("[Q36 in sprint A or no sprint] query  : $q36")
        println("[Q36 in sprint A or no sprint] gremlin: ${q36.toGremlin()}")
        assertThat(q36.toGremlin())
            .isEqualTo("""g.V().or(__.where(__.out("sprint_link").hasId(#50:1)),__.not(__.out("sprint_link"))).hasLabel("Issue")""")

        // Q37: Subtasks OR issues matching "Bug:" prefix
        val q37 = issues(HasLink("parent"))
            .union(issues(MatchStringProp("summary", StringCompare.Prefix, "Bug:", isCollection = false, caseSensitive = false)))
        println("[Q37 subtasks or bug prefix] query  : $q37")
        println("[Q37 subtasks or bug prefix] gremlin: ${q37.toGremlin()}")
        assertThat(q37.toGremlin())
            .isEqualTo("""g.V().or(__.where(__.out("parent_link")),__.where(__.values("summary").toLower().is(TextP.startingWith("bug:")))).hasLabel("Issue")""")

        // Q38: SortBy(open issues).union(SortBy(critical issues)) — O3 drops both sorts for union
        val q38 = SortBy(issues(PropEqual("status", "open")), byPriority)
            .union(SortBy(issues(PropEqual("priority", "critical")), byEstimate))
        println("[Q38 union of two sorted queries strips both sorts] query  : $q38")
        println("[Q38 union of two sorted queries strips both sorts] gremlin: ${q38.toGremlin()}")
        assertThat(q38.toGremlin())
            .isEqualTo("""g.V().or(__.has("status","open"),__.has("priority","critical")).hasLabel("Issue")""")

        // Q39: SortBy(open).union(unresolved) — O3 strips left sort; O9 coalesces same-property PropEquals
        val q39 = SortBy(issues(PropEqual("status", "open")), byPriority)
            .union(issues(PropEqual("status", "resolved")))
        println("[Q39 sorted union unsorted strips left sort] query  : $q39")
        println("[Q39 sorted union unsorted strips left sort] gremlin: ${q39.toGremlin()}")
        assertThat(q39.toGremlin())
            .isEqualTo("""g.V().has("status",P.within(["open", "resolved"])).hasLabel("Issue")""")
    }

    // =========================================================================
    // Group 6 — Intersect queries
    // =========================================================================

    @Test
    fun `group 6 - intersect queries`() {

        // Q40: Critical AND open issues — combineEfficient produces And condition
        val q40 = issues(PropEqual("priority", "critical"))
            .intersect(issues(PropEqual("status", "open")))
        println("[Q40 critical and open] query  : $q40")
        println("[Q40 critical and open] gremlin: ${q40.toGremlin()}")
        assertThat(q40.toGremlin())
            .isEqualTo("""g.V().and(__.has("priority","critical"),__.has("status","open")).hasLabel("Issue")""")

        // Q41: Open issues that are also in a sprint (condition AND HasLink)
        val q41 = issues(PropEqual("status", "open"))
            .intersect(issues(HasLink("sprint")))
        println("[Q41 open and in sprint] query  : $q41")
        println("[Q41 open and in sprint] gremlin: ${q41.toGremlin()}")
        assertThat(q41.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.where(__.out("sprint_link"))).hasLabel("Issue")""")

        // Q42: Issues with assignee AND with at least one tag
        val q42 = issues(HasLink("assignee"))
            .intersect(issues(HasLink("tags")))
        println("[Q42 has assignee and has tags] query  : $q42")
        println("[Q42 has assignee and has tags] gremlin: ${q42.toGremlin()}")
        assertThat(q42.toGremlin())
            .isEqualTo("""g.V().and(__.where(__.out("assignee_link")),__.where(__.out("tags_link"))).hasLabel("Issue")""")

        // Q43: High-estimate AND high-priority issues
        val q43 = issues(PropInRange("estimate", 5, 8))
            .intersect(issues(PropEqual("priority", "high")))
        println("[Q43 high estimate and high priority] query  : $q43")
        println("[Q43 high estimate and high priority] gremlin: ${q43.toGremlin()}")
        assertThat(q43.toGremlin())
            .isEqualTo("""g.V().and(__.has("estimate",P.gte((int) 5).and(P.lte((int) 8))),__.has("priority","high")).hasLabel("Issue")""")

        // Q44: SortBy(all issues, priority).intersect(open issues) — O3 preserves left sort for intersect
        val q44 = SortBy(issues(), byPriority)
            .intersect(issues(PropEqual("status", "open")))
        println("[Q44 sorted intersect unsorted preserves left sort] query  : $q44")
        println("[Q44 sorted intersect unsorted preserves left sort] gremlin: ${q44.toGremlin()}")
        assertThat(q44.toGremlin())
            .isEqualTo("""g.V().has("status","open").hasLabel("Issue")$byPriorityGremlin""")

        // Q45: SortBy(all, priority).intersect(SortBy(high priority, estimate))
        // O3: right sort stripped, left sort preserved; inner intersect: All AND has("priority","high") = has("priority","high")
        val q45 = SortBy(issues(), byPriority)
            .intersect(SortBy(issues(PropEqual("priority", "high")), byEstimate))
        println("[Q45 sorted intersect sorted strips right, preserves left] query  : $q45")
        println("[Q45 sorted intersect sorted strips right, preserves left] gremlin: ${q45.toGremlin()}")
        assertThat(q45.toGremlin())
            .isEqualTo("""g.V().has("priority","high").hasLabel("Issue")$byPriorityGremlin""")

        // Q46: ByIds intersect condition — specific issues that are also open
        // ByIds.asBlock() returns IdWithin([...]) which is a valid CONDITION block,
        // so extractCondition succeeds for both sides → combineEfficient produces And(IdWithin, PropEqual)
        // with the label from the right operand.
        val q46 = ByIds(listOf(issueRid1, issueRid2))
            .intersect(issues(PropEqual("status", "open")))
        println("[Q46 byids intersect condition] query  : $q46")
        println("[Q46 byids intersect condition] gremlin: ${q46.toGremlin()}")
        assertThat(q46.toGremlin())
            .isEqualTo("""g.V().and(__.hasId(P.within([#30:1, #30:2])),__.has("status","open")).hasLabel("Issue")""")

        // Q47: Triple intersect: critical AND open AND in-sprint
        // Step 1: critical.intersect(open) → And([critical, open])
        // Step 2: And([critical,open]).intersect(in-sprint) → combineBlocks produces
        //   And([And([critical,open]), sprint]); simplify() flattens → And([critical, open, sprint])
        val q47 = issues(PropEqual("priority", "critical"))
            .intersect(issues(PropEqual("status", "open")))
            .intersect(issues(HasLink("sprint")))
        println("[Q47 critical and open and in-sprint] query  : $q47")
        println("[Q47 critical and open and in-sprint] gremlin: ${q47.toGremlin()}")
        assertThat(q47.toGremlin())
            .isEqualTo("""g.V().and(__.has("priority","critical"),__.has("status","open"),__.where(__.out("sprint_link"))).hasLabel("Issue")""")

        // Q48: Open issues intersected with issues in Engineering project
        // "issues in Engineering project" = NestedCondition following project → lead link
        // Here we use HasLinkTo for the project directly.
        val q48 = issues(PropEqual("status", "open"))
            .intersect(issues(HasLinkTo("project", projectRid)))
        println("[Q48 open and in project] query  : $q48")
        println("[Q48 open and in project] gremlin: ${q48.toGremlin()}")
        assertThat(q48.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.where(__.out("project_link").hasId(#20:1))).hasLabel("Issue")""")

        // Q49: Unresolved issues that are also unassigned (two HasNoLink conditions)
        val q49 = issues(HasNoLink("assignee"))
            .intersect(issues(HasNoLink("sprint")))
        println("[Q49 unassigned and no sprint] query  : $q49")
        println("[Q49 unassigned and no sprint] gremlin: ${q49.toGremlin()}")
        assertThat(q49.toGremlin())
            .isEqualTo("""g.V().and(__.not(__.out("assignee_link")),__.not(__.out("sprint_link"))).hasLabel("Issue")""")
    }

    // =========================================================================
    // Group 7 — Difference queries
    // =========================================================================

    @Test
    fun `group 7 - difference queries`() {

        // Q50: Open issues NOT assigned to a specific user
        // combineEfficient: And(open, Not(HasLinkTo))
        val q50 = issues(PropEqual("status", "open"))
            .difference(issues(HasLinkTo("assignee", userRid)))
        println("[Q50 open not assigned to user] query  : $q50")
        println("[Q50 open not assigned to user] gremlin: ${q50.toGremlin()}")
        assertThat(q50.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.not(__.where(__.out("assignee_link").hasId(#10:1)))).hasLabel("Issue")""")

        // Q51: Critical issues NOT in any sprint
        val q51 = issues(PropEqual("priority", "critical"))
            .difference(issues(HasLink("sprint")))
        println("[Q51 critical not in sprint] query  : $q51")
        println("[Q51 critical not in sprint] gremlin: ${q51.toGremlin()}")
        assertThat(q51.toGremlin())
            .isEqualTo("""g.V().and(__.has("priority","critical"),__.not(__.where(__.out("sprint_link")))).hasLabel("Issue")""")

        // Q52: Issues in project A NOT marked as subtasks
        val q52 = issues(HasLinkTo("project", projectRid))
            .difference(issues(HasLink("parent")))
        println("[Q52 in project not subtasks] query  : $q52")
        println("[Q52 in project not subtasks] gremlin: ${q52.toGremlin()}")
        assertThat(q52.toGremlin())
            .isEqualTo("""g.V().and(__.where(__.out("project_link").hasId(#20:1)),__.not(__.where(__.out("parent_link")))).hasLabel("Issue")""")

        // Q53: High-priority issues NOT resolved
        val q53 = issues(PropEqual("priority", "high"))
            .difference(issues(PropEqual("status", "resolved")))
        println("[Q53 high priority not resolved] query  : $q53")
        println("[Q53 high priority not resolved] gremlin: ${q53.toGremlin()}")
        assertThat(q53.toGremlin())
            .isEqualTo("""g.V().and(__.has("priority","high"),__.not(__.has("status","resolved"))).hasLabel("Issue")""")

        // Q54: All issues NOT tagged with a specific tag (by RID)
        val q54 = issues()
            .difference(issues(HasLinkTo("tags", tagRid)))
        println("[Q54 issues not tagged with bug tag] query  : $q54")
        println("[Q54 issues not tagged with bug tag] gremlin: ${q54.toGremlin()}")
        // All.difference(condition): combineBlocks(All, HasLinkTo) → Not(HasLinkTo)
        assertThat(q54.toGremlin())
            .isEqualTo("""g.V().not(__.where(__.out("tags_link").hasId(#40:1))).hasLabel("Issue")""")

        // Q55: SortBy(open issues, priority).difference(assigned issues) — O3 preserves left sort
        val q55 = SortBy(issues(PropEqual("status", "open")), byPriority)
            .difference(issues(HasLink("assignee")))
        println("[Q55 sorted difference preserves sort] query  : $q55")
        println("[Q55 sorted difference preserves sort] gremlin: ${q55.toGremlin()}")
        assertThat(q55.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.not(__.where(__.out("assignee_link")))).hasLabel("Issue")$byPriorityGremlin""")

        // Q56: ByIds difference condition — specific issues that are not open
        // ByIds.asBlock() returns IdWithin([...]) which is a valid CONDITION block,
        // so extractCondition succeeds → combineEfficient produces And(IdWithin, Not(PropEqual))
        // with the label from the right operand. No Aggregate fallback.
        val q56 = ByIds(listOf(issueRid1, issueRid2))
            .difference(issues(PropEqual("status", "open")))
        println("[Q56 byids difference condition] query  : $q56")
        println("[Q56 byids difference condition] gremlin: ${q56.toGremlin()}")
        assertThat(q56.toGremlin())
            .isEqualTo("""g.V().and(__.hasId(P.within([#30:1, #30:2])),__.not(__.has("status","open"))).hasLabel("Issue")""")

        // Q57: Open issues NOT in a specific project
        val q57 = issues(PropEqual("status", "open"))
            .difference(issues(HasLinkTo("project", projectRid)))
        println("[Q57 open not in project] query  : $q57")
        println("[Q57 open not in project] gremlin: ${q57.toGremlin()}")
        assertThat(q57.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.not(__.where(__.out("project_link").hasId(#20:1)))).hasLabel("Issue")""")
    }

    // =========================================================================
    // Group 8 — Complex combined queries
    // =========================================================================

    @Test
    fun `group 8 - complex combined queries`() {

        // Q58: (Critical OR high) AND open
        // Step 1: O9 → PropWithin("priority", [critical, high])
        // Step 2: .intersect(open) → And([PropWithin("priority",[c,h]), open])
        val q58 = issues(PropEqual("priority", "critical"))
            .union(issues(PropEqual("priority", "high")))
            .intersect(issues(PropEqual("status", "open")))
        println("[Q58 (critical or high) and open] query  : $q58")
        println("[Q58 (critical or high) and open] gremlin: ${q58.toGremlin()}")
        assertThat(q58.toGremlin())
            .isEqualTo("""g.V().and(__.has("priority",P.within(["critical", "high"])),__.has("status","open")).hasLabel("Issue")""")

        // Q59: (Critical AND open) OR (high AND in-progress) — two intersects unioned
        // Step 1: a = critical.intersect(open) → And(critical, open)
        // Step 2: b = high.intersect(in-progress) → And(high, in-progress)
        // Step 3: a.union(b) → Or(And(critical,open), And(high,in-progress))
        val q59 = issues(PropEqual("priority", "critical"))
            .intersect(issues(PropEqual("status", "open")))
            .union(
                issues(PropEqual("priority", "high"))
                    .intersect(issues(PropEqual("status", "in-progress")))
            )
        println("[Q59 (critical and open) or (high and in-progress)] query  : $q59")
        println("[Q59 (critical and open) or (high and in-progress)] gremlin: ${q59.toGremlin()}")
        assertThat(q59.toGremlin())
            .isEqualTo("""g.V().or(__.and(__.has("priority","critical"),__.has("status","open")),__.and(__.has("priority","high"),__.has("status","in-progress"))).hasLabel("Issue")""")

        // Q60: (Critical OR high) AND NOT resolved AND in-sprint
        // Step 1: O9 → PropWithin("priority", [critical, high])
        // Step 2: .difference(resolved) → And([PropWithin, Not(resolved)])
        // Step 3: .intersect(sprint) → And([And([PropWithin,Not]), sprint]);
        //   O8 flattens outer And → And([PropWithin, Not(resolved), sprint])
        val q60 = issues(PropEqual("priority", "critical"))
            .union(issues(PropEqual("priority", "high")))
            .difference(issues(PropEqual("status", "resolved")))
            .intersect(issues(HasLink("sprint")))
        println("[Q60 (critical or high) not resolved and in-sprint] query  : $q60")
        println("[Q60 (critical or high) not resolved and in-sprint] gremlin: ${q60.toGremlin()}")
        assertThat(q60.toGremlin())
            .isEqualTo("""g.V().and(__.has("priority",P.within(["critical", "high"])),__.not(__.has("status","resolved")),__.where(__.out("sprint_link"))).hasLabel("Issue")""")

        // Q61: Open issues in project A UNION open issues in project B, minus assigned issues
        // Step 1: openInA = open.intersect(inProjectA) → And(open, HasLinkTo(project,A))
        // Step 2: openInB = open.intersect(inProjectB) → And(open, HasLinkTo(project,B))
        // Step 3: openInA.union(openInB) → Or(And(open,A), And(open,B))
        // Step 4: .difference(hasAssignee) → And(Or(...), Not(HasLink(assignee)))
        val q61 = issues(PropEqual("status", "open"))
            .intersect(issues(HasLinkTo("project", projectRid)))
            .union(
                issues(PropEqual("status", "open"))
                    .intersect(issues(HasLinkTo("project", projectRid2)))
            )
            .difference(issues(HasLink("assignee")))
        println("[Q61 open in A or B minus assigned] query  : $q61")
        println("[Q61 open in A or B minus assigned] gremlin: ${q61.toGremlin()}")
        assertThat(q61.toGremlin())
            .isEqualTo("""g.V().and(__.or(__.and(__.has("status","open"),__.where(__.out("project_link").hasId(#20:1))),__.and(__.has("status","open"),__.where(__.out("project_link").hasId(#20:2)))),__.not(__.where(__.out("assignee_link")))).hasLabel("Issue")""")

        // Q62: Cascaded 3-way union with fallback — all three operands are Labeled conditions,
        // so combineEfficient succeeds at each step, building nested Or conditions.
        // skip(1).union(skip(2)).union(skip(3)) from the plan becomes Slice queries;
        // Slice vs Labeled → combineEfficient returns null → UnionAll with O1 flattening.
        val q62 = issues().then(Skip(1))
            .union(issues().then(Skip(2)))
            .union(issues().then(Skip(3)))
        println("[Q62 cascaded union with slices triggers O1 flattening] query  : $q62")
        println("[Q62 cascaded union with slices triggers O1 flattening] gremlin: ${q62.toGremlin()}")
        // All three are Slice queries → combineEfficient fails → UnionAll fallback.
        // The first union produces Order(UnionAll([skip1, skip2]), Dedup).
        // The second union: flatSubqueries detects Order(UnionAll, Dedup) and flattens to [skip1, skip2],
        // then produces UnionAll([skip1, skip2, skip3]).then(Dedup).
        assertThat(q62.toGremlin())
            .isEqualTo("""g.union(__.V().hasLabel("Issue").skip(1L),__.V().hasLabel("Issue").skip(2L),__.V().hasLabel("Issue").skip(3L)).dedup()""")

        // Q63: (Sorted open) intersect (Sorted critical) — both sorted same key
        // O3: left sort preserved, right sort stripped; inner intersect: And(open, critical)
        val q63 = SortBy(issues(PropEqual("status", "open")), byPriority)
            .intersect(SortBy(issues(PropEqual("priority", "critical")), byPriority))
        println("[Q63 sorted intersect same key preserves left sort] query  : $q63")
        println("[Q63 sorted intersect same key preserves left sort] gremlin: ${q63.toGremlin()}")
        assertThat(q63.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.has("priority","critical")).hasLabel("Issue")$byPriorityGremlin""")

        // Q64: (Open AND critical) UNION (high AND in-progress) — same as Q59, verified separately
        // Both sides are efficient intersects; union of them also uses combineEfficient
        val q64 = issues(PropEqual("status", "open"))
            .intersect(issues(PropEqual("priority", "critical")))
            .union(
                issues(PropEqual("status", "in-progress"))
                    .intersect(issues(PropEqual("priority", "high")))
            )
        println("[Q64 (open and critical) union (in-progress and high)] query  : $q64")
        println("[Q64 (open and critical) union (in-progress and high)] gremlin: ${q64.toGremlin()}")
        assertThat(q64.toGremlin())
            .isEqualTo("""g.V().or(__.and(__.has("status","open"),__.has("priority","critical")),__.and(__.has("status","in-progress"),__.has("priority","high"))).hasLabel("Issue")""")

        // Q65: Open in sprint A minus open in sprint B (two intersects, then difference)
        // Step 1: openInA = And([open, HasLinkTo(sprint,A)])
        // Step 2: openInB = And([open, HasLinkTo(sprint,B)])
        // Step 3: openInA.difference(openInB) → combineBlocks produces
        //   And([And([open,sprintA]), Not(And([open,sprintB]))]); simplify() flattens outer And
        //   → And([open, sprintA, Not(And([open,sprintB]))])
        val q65 = issues(PropEqual("status", "open"))
            .intersect(issues(HasLinkTo("sprint", sprintRid)))
            .difference(
                issues(PropEqual("status", "open"))
                    .intersect(issues(HasLinkTo("sprint", sprintRid2)))
            )
        println("[Q65 open in sprint A minus open in sprint B] query  : $q65")
        println("[Q65 open in sprint A minus open in sprint B] gremlin: ${q65.toGremlin()}")
        assertThat(q65.toGremlin())
            .isEqualTo("""g.V().and(__.has("status","open"),__.where(__.out("sprint_link").hasId(#50:1)),__.not(__.and(__.has("status","open"),__.where(__.out("sprint_link").hasId(#50:2))))).hasLabel("Issue")""")

        // Q66: Issues whose project's lead is in Engineering
        // Uses NestedCondition to traverse Issue → project → lead and filter by department.
        // NestedCondition(["project", "lead"], Where.of(PropEqual("department","Engineering")))
        // produces: Where(OutLink("project").andThen(OutLink("lead")).andThen(PropEqual("department","Engineering")))
        // which renders as: where(__.out("project_link").out("lead_link").has("department","Engineering"))
        val q66 = Labeled(
            NestedCondition(
                listOf("project", "lead"),
                Where.of(PropEqual("department", "Engineering"))
            ),
            "Issue"
        )
        println("[Q66 issues whose project lead is in Engineering] query  : $q66")
        println("[Q66 issues whose project lead is in Engineering] gremlin: ${q66.toGremlin()}")
        assertThat(q66.toGremlin())
            .isEqualTo("""g.V().where(__.out("project_link").out("lead_link").has("department","Engineering")).hasLabel("Issue")""")

        // Q67: All issues sorted by priority UNION all issues sorted by estimate
        // O3: union of two SortBy queries — both sorts are dropped, result is unsorted.
        // The inner union is All.union(All) = All (O2 identity: a==b → a).
        // So the result is just issues() with no sort.
        val q67 = SortBy(issues(), byPriority)
            .union(SortBy(issues(), byEstimate))
        println("[Q67 sorted by priority union sorted by estimate drops both sorts] query  : $q67")
        println("[Q67 sorted by priority union sorted by estimate drops both sorts] gremlin: ${q67.toGremlin()}")
        assertThat(q67.toGremlin())
            .isEqualTo("""g.V().hasLabel("Issue")""")
    }

    // =========================================================================
    // Group 9 — Aggregate fallback queries
    //
    // Aggregate fires when combineEfficient returns null. This happens when neither
    // O7 nor extractCondition can handle both operands.
    //
    // O7 handles: FollowLink ∩/\ Condition and Condition ∩ FollowLink (Q68–Q70, Q80, Q83, Q85, Q86).
    // Remaining Aggregate cases:
    //   - condition \ FollowLink (Q71, Q84): starting set is the condition side — not rewritable
    //   - FollowLink ∩/\ FollowLink (Q72, Q73): both sides are traversals
    //   - Slice ∩/\ anything (Q74–Q76): order-dependent, can't push condition before skip/limit
    //   - UnionAll ∩/\ condition (Q77–Q78): can't push condition into union branches
    //   - ReversedOrder ∩ condition (Q79): can't push condition before fold/reverse/unfold
    //   - Labeled(AndThen(FollowLink,cond)) ∩/\ condition (Q81, Q82): O7 fires on inner,
    //     outer falls to Aggregate because AndThen is not Condition
    //
    // Gremlin shape:
    //   g.{right}.aggregate("aggr_N").fold().{left_via_continueTraversal}.where(P.within/without("aggr_N"))
    // =========================================================================

    @Test
    fun `group 9 - aggregate fallback queries`() {

        // ------------------------------------------------------------------
        // FollowLink on left
        // ------------------------------------------------------------------

        // Q68: FollowLink(left) ∩ condition(right)
        // Issues in ENG project, filtered to only the open ones.
        // O7: condition appended directly to the FollowLink traversal — no Aggregate.
        val q68 = issuesInProject(PropEqual("key", "ENG"))
            .intersect(issues(PropEqual("status", "open")))
        println("[Q68 followlink-left intersect condition-right] query  : $q68")
        println("[Q68 followlink-left intersect condition-right] gremlin: ${q68.toGremlin()}")
        assertThat(q68.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").has("status","open").hasLabel("Issue")"""
        )

        // Q69: FollowLink(left) \ condition(right)
        // Issues in ENG project, minus assigned ones.
        // O7: Not(condition) appended to FollowLink traversal — no Aggregate.
        val q69 = issuesInProject(PropEqual("key", "ENG"))
            .difference(issues(HasLink("assignee")))
        println("[Q69 followlink-left difference condition-right] query  : $q69")
        println("[Q69 followlink-left difference condition-right] gremlin: ${q69.toGremlin()}")
        assertThat(q69.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").not(__.where(__.out("assignee_link"))).hasLabel("Issue")"""
        )

        // Q70: condition(left) ∩ FollowLink(right) — reversed roles
        // Open issues filtered to only those also in ENG project.
        // O7: symmetric — FollowLink on right, condition extracted from left, same result as Q68.
        val q70 = issues(PropEqual("status", "open"))
            .intersect(issuesInProject(PropEqual("key", "ENG")))
        println("[Q70 condition-left intersect followlink-right] query  : $q70")
        println("[Q70 condition-left intersect followlink-right] gremlin: ${q70.toGremlin()}")
        assertThat(q70.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").has("status","open").hasLabel("Issue")"""
        )

        // Q71: condition(left) \ FollowLink(right) — reversed roles
        // High-priority issues, excluding those in any sprint.
        val q71 = issues(PropEqual("priority", "high"))
            .difference(issuesInSprint())
        println("[Q71 condition-left difference followlink-right] query  : $q71")
        println("[Q71 condition-left difference followlink-right] gremlin: ${q71.toGremlin()}")
        assertThat(q71.toGremlin()).isEqualTo(
            """g.V().hasLabel("Sprint").in("sprint_link").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().has("priority","high").hasLabel("Issue")""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // Q72: FollowLink(left) ∩ FollowLink(right)
        // Issues in any project, among those also assigned to an Engineering employee.
        // Both sides are FollowLink → extractCondition fails for both → Aggregate.
        val q72 = issuesInProject()
            .intersect(issuesAssignedTo(PropEqual("department", "Engineering")))
        println("[Q72 followlink-left intersect followlink-right] query  : $q72")
        println("[Q72 followlink-left intersect followlink-right] gremlin: ${q72.toGremlin()}")
        assertThat(q72.toGremlin()).isEqualTo(
            """g.V().has("department","Engineering").hasLabel("Employee").in("assignee_link").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().hasLabel("Project").in("project_link").hasLabel("Issue")""" +
            """.where(P.within(["aggr_0"]))"""
        )

        // Q73: FollowLink(left) \ FollowLink(right)
        // Issues in ENG project, excluding those assigned to any employee.
        val q73 = issuesInProject(PropEqual("key", "ENG"))
            .difference(issuesAssignedTo())
        println("[Q73 followlink-left difference followlink-right] query  : $q73")
        println("[Q73 followlink-left difference followlink-right] gremlin: ${q73.toGremlin()}")
        assertThat(q73.toGremlin()).isEqualTo(
            """g.V().hasLabel("Employee").in("assignee_link").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().has("key","ENG").hasLabel("Project").in("project_link").hasLabel("Issue")""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // ------------------------------------------------------------------
        // Slice on left
        // ------------------------------------------------------------------

        // Q74: Slice(left) ∩ condition(right)
        // Page of issues (skip 10), filtered to critical ones.
        // Slice.continueTraversal(t, c, i) = inner.continueTraversal(t,c,i).combine(Skip) = t.V().hasLabel("Issue").skip(10)
        val q74 = issues().then(Skip(10))
            .intersect(issues(PropEqual("priority", "critical")))
        println("[Q74 slice-left intersect condition-right] query  : $q74")
        println("[Q74 slice-left intersect condition-right] gremlin: ${q74.toGremlin()}")
        assertThat(q74.toGremlin()).isEqualTo(
            """g.V().has("priority","critical").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().hasLabel("Issue").skip(10L)""" +
            """.where(P.within(["aggr_0"]))"""
        )

        // Q75: Slice(left) \ condition(right)
        // First 5 issues, excluding those in any sprint.
        val q75 = issues().then(Limit(5))
            .difference(issues(HasLink("sprint")))
        println("[Q75 slice-left difference condition-right] query  : $q75")
        println("[Q75 slice-left difference condition-right] gremlin: ${q75.toGremlin()}")
        assertThat(q75.toGremlin()).isEqualTo(
            """g.V().where(__.out("sprint_link")).hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().hasLabel("Issue").limit(5L)""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // Q76: Slice(left) ∩ FollowLink(right)
        // Page of issues (skip 10), filtered to those also in ENG project.
        // Both sides are non-extractable → Aggregate.
        val q76 = issues().then(Skip(10))
            .intersect(issuesInProject(PropEqual("key", "ENG")))
        println("[Q76 slice-left intersect followlink-right] query  : $q76")
        println("[Q76 slice-left intersect followlink-right] gremlin: ${q76.toGremlin()}")
        assertThat(q76.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().hasLabel("Issue").skip(10L)""" +
            """.where(P.within(["aggr_0"]))"""
        )

        // ------------------------------------------------------------------
        // UnionAll fallback on left
        // ------------------------------------------------------------------

        // Q77: UnionAll(left) ∩ condition(right)
        // The union of two Slice queries falls back to Order(UnionAll, Dedup).
        // Order.continueTraversal(t, c, i) = UnionAll.continueTraversal(t,c,i).combine(Dedup)
        //   = t.union(__.V().hasLabel("Issue").skip(1), ...).dedup()
        val unionFallback = issues().then(Skip(1)).union(issues().then(Skip(2)))
        val q77 = unionFallback.intersect(issues(PropEqual("status", "open")))
        println("[Q77 unionall-left intersect condition-right] query  : $q77")
        println("[Q77 unionall-left intersect condition-right] gremlin: ${q77.toGremlin()}")
        assertThat(q77.toGremlin()).isEqualTo(
            """g.V().has("status","open").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.union(__.V().hasLabel("Issue").skip(1L),__.V().hasLabel("Issue").skip(2L)).dedup()""" +
            """.where(P.within(["aggr_0"]))"""
        )

        // Q78: UnionAll(left) \ condition(right)
        val q78 = unionFallback.difference(issues(PropEqual("priority", "critical")))
        println("[Q78 unionall-left difference condition-right] query  : $q78")
        println("[Q78 unionall-left difference condition-right] gremlin: ${q78.toGremlin()}")
        assertThat(q78.toGremlin()).isEqualTo(
            """g.V().has("priority","critical").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.union(__.V().hasLabel("Issue").skip(1L),__.V().hasLabel("Issue").skip(2L)).dedup()""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // ------------------------------------------------------------------
        // ReversedOrder on left
        // ------------------------------------------------------------------

        // Q79: ReversedOrder(left) ∩ condition(right)
        // Issues in reverse priority order, filtered to open ones.
        // ReversedOrder.continueTraversal appends .order().by(...).fold().reverse().unfold()
        val q79 = ReversedOrder(SortBy(issues(), byPriority))
            .intersect(issues(PropEqual("status", "open")))
        println("[Q79 reversedorder-left intersect condition-right] query  : $q79")
        println("[Q79 reversedorder-left intersect condition-right] gremlin: ${q79.toGremlin()}")
        assertThat(q79.toGremlin()).isEqualTo(
            """g.V().has("status","open").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().hasLabel("Issue")""" +
            """.order().by(__.values("priority").count(),Order.desc).by(__.values("priority").fold(),Order.asc)""" +
            """.fold().reverse().unfold()""" +
            """.where(P.within(["aggr_0"]))"""
        )

        // ------------------------------------------------------------------
        // SortBy(FollowLink) on left — O3 tries to strip sort, inner FollowLink still fails
        // ------------------------------------------------------------------

        // Q80: SortBy(FollowLink)(left) ∩ condition(right)
        // O3 strips the SortBy wrapper, delegates to inner FollowLink.intersect(condition).
        // O7 fires on the inner call, producing Labeled(AndThen(FollowLink, condition), T).
        // O3 re-wraps with the preserved left sort.
        val q80 = SortBy(issuesInProject(PropEqual("key", "ENG")), byPriority)
            .intersect(issues(PropEqual("status", "open")))
        println("[Q80 sortby-followlink-left intersect condition-right] query  : $q80")
        println("[Q80 sortby-followlink-left intersect condition-right] gremlin: ${q80.toGremlin()}")
        assertThat(q80.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").has("status","open").hasLabel("Issue")""" +
            byPriorityGremlin
        )

        // ------------------------------------------------------------------
        // Chained aggregates — Aggregate as left operand of another Aggregate
        // ------------------------------------------------------------------

        // Q81: (FollowLink ∩ condition) ∩ condition — double intersect
        // Step 1: agg1 = issuesInProject("ENG").intersect(issues(open))
        //   → O7 fires: Labeled(AndThen(FollowLink(ENG), open), "Issue")  [no Aggregate]
        // Step 2: agg1.intersect(issues(critical))
        //   → extractCondition(Labeled(AndThen,...)) = null → Aggregate(agg1, critical, within)
        //   → single Aggregate: critical collected into aggr_0, agg1 traversal filtered by it.
        val agg1 = issuesInProject(PropEqual("key", "ENG"))
            .intersect(issues(PropEqual("status", "open")))
        val q81 = agg1.intersect(issues(PropEqual("priority", "critical")))
        println("[Q81 chained double intersect] query  : $q81")
        println("[Q81 chained double intersect] gremlin: ${q81.toGremlin()}")
        assertThat(q81.toGremlin()).isEqualTo(
            """g.V().has("priority","critical").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().has("key","ENG").hasLabel("Project").in("project_link").has("status","open").hasLabel("Issue")""" +
            """.where(P.within(["aggr_0"]))"""
        )

        // Q82: (FollowLink ∩ condition) \ condition — intersect then difference
        // agg1 is O7-optimised (no inner Aggregate); outer difference falls to single Aggregate.
        val q82 = agg1.difference(issues(HasLink("assignee")))
        println("[Q82 chained intersect then difference] query  : $q82")
        println("[Q82 chained intersect then difference] gremlin: ${q82.toGremlin()}")
        assertThat(q82.toGremlin()).isEqualTo(
            """g.V().where(__.out("assignee_link")).hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().has("key","ENG").hasLabel("Project").in("project_link").has("status","open").hasLabel("Issue")""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // ------------------------------------------------------------------
        // ByIds × FollowLink — ByIds.asBlock()=IdWithin is extractable for plain
        // conditions (see Q46/Q56) but not when the other side is a FollowLink.
        // ------------------------------------------------------------------

        // Q83: ByIds(left) ∩ FollowLink(right)
        // O7: FollowLink is the right side; ByIds.asBlock() = IdWithin is extractable.
        // IdWithin appended directly to the FollowLink traversal — no Aggregate.
        val q83 = ByIds(listOf(issueRid1, issueRid2))
            .intersect(issuesInProject(PropEqual("key", "ENG")))
        println("[Q83 byids-left intersect followlink-right] query  : $q83")
        println("[Q83 byids-left intersect followlink-right] gremlin: ${q83.toGremlin()}")
        assertThat(q83.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").hasId(P.within([#30:1, #30:2])).hasLabel("Issue")"""
        )

        // Q84: ByIds(left) \ FollowLink(right)
        // O7 does NOT apply: `condition \ FollowLink` cannot be rewritten as a FollowLink traversal
        // (ByIds is `this`, not the link side). Falls to Aggregate.
        val q84 = ByIds(listOf(issueRid1, issueRid2))
            .difference(issuesInProject(PropEqual("key", "ENG")))
        println("[Q84 byids-left difference followlink-right] query  : $q84")
        println("[Q84 byids-left difference followlink-right] gremlin: ${q84.toGremlin()}")
        assertThat(q84.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").hasLabel("Issue").aggregate("aggr_0").fold()""" +
            """.V().hasId(P.within([#30:1, #30:2]))""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // Q85: FollowLink(left) ∩ ByIds(right)
        // O7: FollowLink is `this`; IdWithin from ByIds appended to traversal — no Aggregate.
        val q85 = issuesInProject(PropEqual("key", "ENG"))
            .intersect(ByIds(listOf(issueRid1, issueRid2)))
        println("[Q85 followlink-left intersect byids-right] query  : $q85")
        println("[Q85 followlink-left intersect byids-right] gremlin: ${q85.toGremlin()}")
        assertThat(q85.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").hasId(P.within([#30:1, #30:2])).hasLabel("Issue")"""
        )

        // Q86: FollowLink(left) \ ByIds(right)
        // O7: FollowLink is `this`; Not(IdWithin) appended to traversal — no Aggregate.
        val q86 = issuesInProject(PropEqual("key", "ENG"))
            .difference(ByIds(listOf(issueRid1, issueRid2)))
        println("[Q86 followlink-left difference byids-right] query  : $q86")
        println("[Q86 followlink-left difference byids-right] gremlin: ${q86.toGremlin()}")
        assertThat(q86.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").not(__.hasId(P.within([#30:1, #30:2]))).hasLabel("Issue")"""
        )
    }
}
