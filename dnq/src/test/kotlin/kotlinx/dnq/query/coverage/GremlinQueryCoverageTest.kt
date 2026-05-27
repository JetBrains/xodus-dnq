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
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.Aggregate
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.ByIds
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.FollowLink
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.Labeled
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.LinkDirection
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.NestedCondition
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.ReversedOrder
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.SortBy
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.UnionAll
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.Where
import jetbrains.exodus.entitystore.youtrackdb.RIDEntityId
import jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransactionImpl
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdEntity
import org.junit.Before
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
 *   Issue  --tags-->     Tag      (multi-value)
 *   Issue  --sprint-->   Sprint
 *   Issue  --parent-->   Issue    (self-referential; subtasks)
 *   Project --lead-->    Employee
 *   Sprint  --project--> Project
 *
 * No real database is used. Tests assert the Gremlin string produced by each query
 * using GroovyTranslator on top of EmptyGraph.
 */
class GremlinQueryCoverageTest : DBTest() {

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
    // Result assertion infrastructure — DNQ-level IssueTrackerDataset
    // =========================================================================

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser, Employee, Manager, Project, Issue, Sprint, Tag)
    }

    private lateinit var dataset: IssueTrackerDataset

    @Before
    fun setupDataset() {
        dataset = IssueTrackerDataset(store)
    }

    private fun <R> withLowLevelTx(block: (YTDBStoreTransactionImpl) -> R): R =
        store.persistentStore.computeInTransaction { tx -> block(tx as YTDBStoreTransactionImpl) }

    private fun GremlinQuery.resultKeys(tx: YTDBStoreTransactionImpl): List<String> =
        YTDBEntityIterable.query(tx, this).map { it.getProperty("key") as String }

    private fun GremlinQuery.resultNames(tx: YTDBStoreTransactionImpl): List<String> =
        YTDBEntityIterable.query(tx, this).map { it.getProperty("name") as String }

    /** Extracts the actual OrientDB RID from a dataset entity for use in ByIds/HasLinkTo queries. */
    private fun rid(xdEntity: XdEntity) =
        (xdEntity.entity.id as RIDEntityId).asOId()

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

        // ---- Result assertions ----
        withLowLevelTx { tx ->
            // Q01: critical = ENG-1, ENG-6, OPS-1, OPS-4
            assertThat(q01.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1", "ENG-6", "OPS-1", "OPS-4"))
            // Q02: open = 13 issues
            assertThat(q02.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14","OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q03: estimate in [1,8] — all 24 except ENG-5(13) and ENG-11(13) = 22
            assertThat(q03.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10","ENG-12","ENG-13","ENG-14",
                       "OPS-1","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
            // Q04: archived project = ARC only
            assertThat(q04.resultKeys(tx)).containsExactly("ARC")
            // Q05: Engineering employees by name = Alice, Bob, Eve
            assertThat(q05.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Bob", "Eve"))
            // Q06: priority in [critical, high] = 11 issues
            assertThat(q06.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-4","ENG-6","ENG-7","ENG-10","OPS-1","OPS-2","OPS-4","INFRA-2","INFRA-3"))
            // Q07: summary contains "login" (case-insensitive) = 6 issues
            assertThat(q07.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-3","ENG-4","ENG-5","ENG-12","ENG-13"))
            // Q08: summary starts with "bug:" (lowercased) = 4 issues
            assertThat(q08.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-2","ENG-10","OPS-4"))
            // Q09: summary ends with "crash" = 6 issues
            assertThat(q09.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-7","OPS-4","INFRA-3"))
            // Q10: active users by name = Alice, Bob, Dave, Eve
            assertThat(q10.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Bob", "Dave", "Eve"))
        }
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

        // ---- Result assertions ----
        // Q17 and Q18 use fake RIDs for Gremlin-string tests; build real queries with dataset RIDs.
        withLowLevelTx { tx ->
            // Q11: issues with assignee — 15 issues
            assertThat(q11.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-7","ENG-8","ENG-10","ENG-12",
                       "OPS-1","OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2"))
            // Q12: no assignee = 9 issues
            assertThat(q12.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-9","ENG-11","ENG-13","ENG-14","OPS-4","INFRA-3","INFRA-4","ARC-1"))
            // Q13: no sprint = 13 issues
            assertThat(q13.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-5","ENG-8","ENG-9","ENG-11","ENG-14","OPS-1","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
            // Q14: has parent (subtasks) = ENG-12, ENG-13, ENG-14
            assertThat(q14.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-12","ENG-13","ENG-14"))
            // Q15: no parent (top-level) = 21 issues (all except ENG-12, ENG-13, ENG-14)
            assertThat(q15.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10","ENG-11",
                       "OPS-1","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
            // Q16: has tags = 11 issues
            assertThat(q16.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-6","ENG-8","ENG-10","ENG-11","OPS-1","OPS-4","INFRA-3"))
            // Q17 real: assigned to Alice = ENG-1,3,5,10,12
            val q17real = issues(HasLinkTo("assignee", rid(dataset.users["Alice"]!!)))
            assertThat(q17real.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-3","ENG-5","ENG-10","ENG-12"))
            // Q18 real: issues in ENG project = ENG-1..14
            val q18real = issues(HasLinkTo("project", rid(dataset.projects["ENG"]!!)))
            assertThat(q18real.resultKeys(tx)).containsExactlyElementsIn((1..14).map { "ENG-$it" })
        }
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

        // ---- Result assertions ----
        // All four queries use fake RIDs; build equivalent queries with real dataset RIDs.
        withLowLevelTx { tx ->
            val eng1Rid = rid(dataset.issues["ENG-1"]!!)
            val eng2Rid = rid(dataset.issues["ENG-2"]!!)
            val eng3Rid = rid(dataset.issues["ENG-3"]!!)
            // Q19: fetch ENG-1 and ENG-2 by ID
            assertThat(ByIds(listOf(eng1Rid, eng2Rid)).resultKeys(tx))
                .containsExactlyElementsIn(listOf("ENG-1", "ENG-2"))
            // Q20: ByIds union ByIds = ENG-1 ∪ ENG-2
            assertThat(ByIds(listOf(eng1Rid)).union(ByIds(listOf(eng2Rid))).resultKeys(tx))
                .containsExactlyElementsIn(listOf("ENG-1", "ENG-2"))
            // Q21: {ENG-1,ENG-2} ∩ {ENG-2,ENG-3} = ENG-2
            assertThat(ByIds(listOf(eng1Rid, eng2Rid)).intersect(ByIds(listOf(eng2Rid, eng3Rid))).resultKeys(tx))
                .containsExactly("ENG-2")
            // Q22: {ENG-1,ENG-2} \ {ENG-2} = ENG-1
            assertThat(ByIds(listOf(eng1Rid, eng2Rid)).difference(ByIds(listOf(eng2Rid))).resultKeys(tx))
                .containsExactly("ENG-1")
            // Q22b: {ENG-1} \ {ENG-1} = ∅. Regression for the ByIds(emptyList()) → g.V()
            // bug where the optimiser-reduced empty by-IDs query executed as "all vertices"
            // instead of "no vertices", surfacing as XdQuery.exclude returning everything.
            // Iterate the raw entity iterable so a non-empty result is observable directly
            // (resultKeys would NPE on non-Issue vertices, masking the actual count).
            assertThat(YTDBEntityIterable.query(tx, ByIds(listOf(eng1Rid)).difference(ByIds(listOf(eng1Rid)))).toList())
                .isEmpty()
        }
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

        // ---- Result assertions ----
        // Sorted queries return all issues; sliced queries return deterministic counts.
        withLowLevelTx { tx ->
            // Q23-Q25: sorted queries return all 24 issues (order varies; set is the same)
            val allIssueKeys = listOf(
                "ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                "ENG-11","ENG-12","ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                "INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1")
            assertThat(q23.resultKeys(tx)).containsExactlyElementsIn(allIssueKeys)
            assertThat(q24.resultKeys(tx)).containsExactlyElementsIn(allIssueKeys)
            assertThat(q25.resultKeys(tx)).containsExactlyElementsIn(allIssueKeys)
            // Q26–Q29: count is deterministic (slice size); content depends on scan order (not insertion order)
            assertThat(q26.resultKeys(tx)).hasSize(14)  // skip(10) of 24
            assertThat(q27.resultKeys(tx)).hasSize(20)  // limit(20) of 24
            assertThat(q28.resultKeys(tx)).hasSize(5)   // skip(10).limit(5)
            assertThat(q29.resultKeys(tx)).hasSize(5)   // tail(5)
        }
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

        // ---- Result assertions ----
        // Q33, Q34, Q36 use fake RIDs; build real queries with dataset entity RIDs.
        withLowLevelTx { tx ->
            // Q30: critical ∪ high = 11 issues
            assertThat(q30.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-4","ENG-6","ENG-7","ENG-10","OPS-1","OPS-2","OPS-4","INFRA-2","INFRA-3"))
            // Q31: open ∪ in-progress = 17 issues
            assertThat(q31.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-5","ENG-6","ENG-7","ENG-8","ENG-10","ENG-11","ENG-12",
                       "ENG-13","ENG-14","OPS-1","OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q32: no-assignee ∪ critical = 9 + 4 - 2 overlap (ENG-6, OPS-4) = 11
            assertThat(q32.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-6","ENG-9","ENG-11","ENG-13","ENG-14","OPS-1","OPS-4","INFRA-3","INFRA-4","ARC-1"))
            // Q33 real: issues in ENG ∪ issues in OPS = 14 + 5 = 19
            val q33real = issues(HasLinkTo("project", rid(dataset.projects["ENG"]!!))).union(
                          issues(HasLinkTo("project", rid(dataset.projects["OPS"]!!))))
            assertThat(q33real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5"))
            // Q34 real: ByIds({ENG-1}) ∪ ByIds({ENG-2})
            assertThat(ByIds(listOf(rid(dataset.issues["ENG-1"]!!))).union(ByIds(listOf(rid(dataset.issues["ENG-2"]!!)))).resultKeys(tx))
                .containsExactlyElementsIn(listOf("ENG-1","ENG-2"))
            // Q35: open ∪ in-progress ∪ resolved = 22 issues (all except INFRA-1 and ARC-1 which are closed)
            assertThat(q35.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                       "INFRA-2","INFRA-3","INFRA-4"))
            // Q36 real: in-S1 ∪ no-sprint = 20 issues (no overlap)
            val q36real = issues(HasLinkTo("sprint", rid(dataset.sprints["S1"]!!))).union(issues(HasNoLink("sprint")))
            assertThat(q36real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-5","ENG-6","ENG-8","ENG-9","ENG-10","ENG-11","ENG-12",
                       "ENG-13","ENG-14","OPS-1","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
            // Q37: has-parent ∪ starts-with-"bug:" = subtasks(3) ∪ bug-prefixed(4) — ENG-1,2,10,OPS-4 + ENG-12,13,14 = 7
            assertThat(q37.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-10","ENG-12","ENG-13","ENG-14","OPS-4"))
            // Q38: open ∪ critical (both SortBy, sorts stripped) = 13 open + OPS-1 = 14
            assertThat(q38.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14","OPS-1","OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q39: open ∪ resolved = 18 issues
            assertThat(q39.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-4","ENG-5","ENG-6","ENG-8","ENG-9","ENG-10","ENG-11",
                       "ENG-13","ENG-14","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-2","INFRA-3","INFRA-4"))
        }
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
            .isEqualTo("""g.V().has("priority","critical").has("status","open").hasLabel("Issue")""")

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
            .isEqualTo("""g.V().has("estimate",P.gte((int) 5).and(P.lte((int) 8))).has("priority","high").hasLabel("Issue")""")

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
            .isEqualTo("""g.V().hasId(P.within([#30:1, #30:2])).has("status","open").hasLabel("Issue")""")

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

        // ---- Result assertions ----
        // Q46 and Q48 use fake RIDs; build real queries with dataset entity RIDs.
        withLowLevelTx { tx ->
            // Q40: critical ∩ open = ENG-1(crit+open), ENG-6(crit+open), OPS-4(crit+open) = 3
            assertThat(q40.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))
            // Q41: open ∩ has-sprint = 7 issues (open issues that are in any sprint)
            assertThat(q41.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-10","ENG-13","OPS-2","OPS-4"))
            // Q42: has-assignee ∩ has-tags = 7 issues
            assertThat(q42.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-8","ENG-10","OPS-1"))
            // Q43: estimate in [5,8] ∩ high = ENG-7(est=5,high), ENG-10(est=8,high), OPS-2(est=5,high), INFRA-2(est=8,high)
            assertThat(q43.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-7","ENG-10","OPS-2","INFRA-2"))
            // Q44: SortBy(all,priority) ∩ open = all open issues (13)
            assertThat(q44.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14",
                       "OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q45: SortBy(all,priority) ∩ SortBy(high,estimate) = high priority issues (7)
            assertThat(q45.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-2","ENG-4","ENG-7","ENG-10","OPS-2","INFRA-2","INFRA-3"))
            // Q46 real: {ENG-1(open), ENG-4(resolved)} ∩ open = ENG-1 only
            val q46real = ByIds(listOf(rid(dataset.issues["ENG-1"]!!), rid(dataset.issues["ENG-4"]!!))).intersect(issues(PropEqual("status","open")))
            assertThat(q46real.resultKeys(tx)).containsExactly("ENG-1")
            // Q47: critical ∩ open ∩ has-sprint = ENG-1(S1), ENG-6(S1), OPS-4(S3)
            assertThat(q47.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))
            // Q48 real: open ∩ in-ENG = 9 open ENG issues
            val q48real = issues(PropEqual("status","open")).intersect(issues(HasLinkTo("project", rid(dataset.projects["ENG"]!!))))
            assertThat(q48real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14"))
            // Q49: no-assignee ∩ no-sprint = 6 issues
            assertThat(q49.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-9","ENG-11","ENG-14","INFRA-3","INFRA-4","ARC-1"))
        }
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
            .isEqualTo("""g.V().and(__.has("priority","critical"),__.not(__.out("sprint_link"))).hasLabel("Issue")""")

        // Q52: Issues in project A NOT marked as subtasks
        val q52 = issues(HasLinkTo("project", projectRid))
            .difference(issues(HasLink("parent")))
        println("[Q52 in project not subtasks] query  : $q52")
        println("[Q52 in project not subtasks] gremlin: ${q52.toGremlin()}")
        assertThat(q52.toGremlin())
            .isEqualTo("""g.V().and(__.where(__.out("project_link").hasId(#20:1)),__.not(__.out("parent_link"))).hasLabel("Issue")""")

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
            .isEqualTo("""g.V().and(__.has("status","open"),__.not(__.out("assignee_link"))).hasLabel("Issue")$byPriorityGremlin""")

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

        // ---- Result assertions ----
        // Q50, Q52, Q54, Q56, Q57 use fake RIDs; build real queries with dataset entity RIDs.
        withLowLevelTx { tx ->
            // Q50 real: open \ assigned-to-Alice = 10 issues (13 open, 3 of which are Alice's: ENG-1,5,10)
            val q50real = issues(PropEqual("status","open")).difference(issues(HasLinkTo("assignee", rid(dataset.users["Alice"]!!))))
            assertThat(q50real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-2","ENG-6","ENG-8","ENG-11","ENG-13","ENG-14","OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q51: critical \ has-sprint = OPS-1 (ENG-1→S1, ENG-6→S1, OPS-4→S3; only OPS-1 has no sprint)
            assertThat(q51.resultKeys(tx)).containsExactly("OPS-1")
            // Q52 real: in-ENG \ has-parent = ENG non-subtasks = ENG-1..11 (11 issues)
            val q52real = issues(HasLinkTo("project", rid(dataset.projects["ENG"]!!))).difference(issues(HasLink("parent")))
            assertThat(q52real.resultKeys(tx)).containsExactlyElementsIn((1..11).map { "ENG-$it" })
            // Q53: high \ resolved = high issues that are not resolved (ENG-4 and INFRA-2 are resolved)
            assertThat(q53.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-2","ENG-7","ENG-10","OPS-2","INFRA-3"))
            // Q54 real: all \ tagged-with-"bug" = 24 - 7 = 17 (bug: ENG-1,2,6,10,OPS-1,4,INFRA-3)
            val q54real = issues().difference(issues(HasLinkTo("tags", rid(dataset.tags["bug"]!!))))
            assertThat(q54real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-3","ENG-4","ENG-5","ENG-7","ENG-8","ENG-9","ENG-11","ENG-12","ENG-13","ENG-14",
                       "OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-4","ARC-1"))
            // Q55: SortBy(open,priority) \ has-assignee = open unassigned = 7 issues
            assertThat(q55.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-11","ENG-13","ENG-14","OPS-4","INFRA-3","INFRA-4"))
            // Q56 real: {ENG-1(open), ENG-4(resolved)} \ open = ENG-4 (resolved, survives exclusion)
            val q56real = ByIds(listOf(rid(dataset.issues["ENG-1"]!!), rid(dataset.issues["ENG-4"]!!))).difference(issues(PropEqual("status","open")))
            assertThat(q56real.resultKeys(tx)).containsExactly("ENG-4")
            // Q57 real: open \ in-ENG = 4 open non-ENG issues
            val q57real = issues(PropEqual("status","open")).difference(issues(HasLinkTo("project", rid(dataset.projects["ENG"]!!))))
            assertThat(q57real.resultKeys(tx)).containsExactlyElementsIn(listOf("OPS-2","OPS-4","INFRA-3","INFRA-4"))
        }
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
            .isEqualTo("""g.V().has("priority",P.within(["critical", "high"])).has("status","open").hasLabel("Issue")""")

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
            .isEqualTo("""g.V().or(__.has("priority","critical").has("status","open"),__.has("priority","high").has("status","in-progress")).hasLabel("Issue")""")

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
            .isEqualTo("""g.V().and(__.or(__.and(__.has("status","open"),__.where(__.out("project_link").hasId(#20:1))),__.and(__.has("status","open"),__.where(__.out("project_link").hasId(#20:2)))),__.not(__.out("assignee_link"))).hasLabel("Issue")""")

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
            .isEqualTo("""g.V().has("status","open").has("priority","critical").hasLabel("Issue")$byPriorityGremlin""")

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
            .isEqualTo("""g.V().or(__.has("status","open").has("priority","critical"),__.has("status","in-progress").has("priority","high")).hasLabel("Issue")""")

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

        // ---- Result assertions ----
        // Q61 and Q65 use fake RIDs; build real queries with dataset entity RIDs.
        withLowLevelTx { tx ->
            // Q58: (critical ∪ high) ∩ open = 7 issues
            assertThat(q58.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-10","OPS-2","OPS-4","INFRA-3"))
            // Q59: (critical ∩ open) ∪ (high ∩ in-progress) = {ENG-1,ENG-6,OPS-4} ∪ {ENG-7} = 4
            assertThat(q59.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6","ENG-7","OPS-4"))
            // Q60: (critical ∪ high) \ resolved ∩ has-sprint = 7 issues
            assertThat(q60.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-7","ENG-10","OPS-2","OPS-4"))
            // Q61 real: (open ∩ ENG ∪ open ∩ OPS) \ has-assignee = 5 unassigned issues
            val q61real = issues(PropEqual("status","open")).intersect(issues(HasLinkTo("project", rid(dataset.projects["ENG"]!!))))
                .union(issues(PropEqual("status","open")).intersect(issues(HasLinkTo("project", rid(dataset.projects["OPS"]!!)))))
                .difference(issues(HasLink("assignee")))
            assertThat(q61real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-11","ENG-13","ENG-14","OPS-4"))
            // Q62: skip(1)∪skip(2)∪skip(3) — issue at position 0 (scan-order dependent) excluded from all three
            assertThat(q62.resultKeys(tx)).hasSize(23)
            // Q63: SortBy(open,priority) ∩ SortBy(critical,priority) = open ∩ critical = 3 issues
            assertThat(q63.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))
            // Q64: (open ∩ critical) ∪ (in-progress ∩ high) = {ENG-1,ENG-6,OPS-4} ∪ {ENG-7} = 4
            assertThat(q64.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6","ENG-7","OPS-4"))
            // Q65 real: (open ∩ S1) \ (open ∩ S2) — openInS2 is empty (ENG-4 resolved, ENG-7 in-progress) → = openInS1
            val q65real = issues(PropEqual("status","open")).intersect(issues(HasLinkTo("sprint", rid(dataset.sprints["S1"]!!))))
                .difference(issues(PropEqual("status","open")).intersect(issues(HasLinkTo("sprint", rid(dataset.sprints["S2"]!!)))))
            assertThat(q65real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-6","ENG-10","ENG-13"))
            // Q66: ENG (Alice leads, Engineering) + INFRA (Bob leads, Engineering) = 18 issues
            assertThat(q66.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","INFRA-1","INFRA-2","INFRA-3","INFRA-4"))
            // Q67: All ∪ All = All — all 24 issues
            assertThat(q67.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                       "INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
        }
    }

    // =========================================================================
    // Group 9 — Aggregate fallback queries
    //
    // Aggregate fires when combineEfficient returns null. This happens when neither
    // O7/O11 nor extractCondition can handle both operands.
    //
    // O7 handles: FollowLink ∩/\ Condition and Condition ∩ FollowLink (Q68–Q70, Q80, Q83, Q85, Q86).
    // O11 handles: condition OP FollowLink(srcCond) — condition \ FollowLink and condition ∪ FollowLink (Q71, Q84).
    // Remaining Aggregate cases:
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
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").not(__.out("assignee_link")).hasLabel("Issue")"""
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
        // O11: srcCond = All → inverse = HasLink("sprint") → Not(HasLink) → HasNoLink.
        // Rewrites to: and(has("priority","high"), not(out("sprint_link"))).hasLabel("Issue")
        val q71 = issues(PropEqual("priority", "high"))
            .difference(issuesInSprint())
        println("[Q71 condition-left difference followlink-right] query  : $q71")
        println("[Q71 condition-left difference followlink-right] gremlin: ${q71.toGremlin()}")
        assertThat(q71.toGremlin()).isEqualTo(
            """g.V().and(__.has("priority","high"),__.not(__.out("sprint_link"))).hasLabel("Issue")"""
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
        //   → O16 fires: extends AndThen chain with critical — single traversal, no Aggregate.
        val agg1 = issuesInProject(PropEqual("key", "ENG"))
            .intersect(issues(PropEqual("status", "open")))
        val q81 = agg1.intersect(issues(PropEqual("priority", "critical")))
        println("[Q81 chained double intersect] query  : $q81")
        println("[Q81 chained double intersect] gremlin: ${q81.toGremlin()}")
        assertThat(q81.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link")""" +
            """.has("status","open").has("priority","critical").hasLabel("Issue")"""
        )

        // Q82: (FollowLink ∩ condition) \ condition — intersect then difference
        // O16 fires: Not(HasLink("assignee")) appended to existing chain — no Aggregate.
        val q82 = agg1.difference(issues(HasLink("assignee")))
        println("[Q82 chained intersect then difference] query  : $q82")
        println("[Q82 chained intersect then difference] gremlin: ${q82.toGremlin()}")
        assertThat(q82.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link")""" +
            """.has("status","open").not(__.out("assignee_link")).hasLabel("Issue")"""
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
        // O11: ByIds.asBlock() = IdWithin is extractable; srcCond = PropEqual("key","ENG") → inverse = Where(out("project_link")...).
        // Rewrites to: and(hasId(P.within([...])), not(where(out("project_link").has("key","ENG").hasLabel("Project")))).hasLabel("Issue")
        val q84 = ByIds(listOf(issueRid1, issueRid2))
            .difference(issuesInProject(PropEqual("key", "ENG")))
        println("[Q84 byids-left difference followlink-right] query  : $q84")
        println("[Q84 byids-left difference followlink-right] gremlin: ${q84.toGremlin()}")
        assertThat(q84.toGremlin()).isEqualTo(
            """g.V().and(__.hasId(P.within([#30:1, #30:2])),""" +
            """__.not(__.where(__.out("project_link").has("key","ENG").hasLabel("Project")))).hasLabel("Issue")"""
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

        // ---- Result assertions ----
        // Q68-Q73, Q79-Q82 use no fake RIDs. Q74-Q78 are slice-based (non-deterministic content).
        // Q83-Q86 use fake RIDs; build real queries with dataset entity RIDs.
        withLowLevelTx { tx ->
            // Q68: issuesInProject(ENG) ∩ open = 9 open ENG issues
            assertThat(q68.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14"))
            // Q69: issuesInProject(ENG) \ has-assignee = 5 unassigned ENG issues
            assertThat(q69.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-9","ENG-11","ENG-13","ENG-14"))
            // Q70: same as Q68 (symmetric O7 application)
            assertThat(q70.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14"))
            // Q71: high \ issuesInSprint() = high issues not in any sprint = INFRA-2, INFRA-3
            assertThat(q71.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA-2","INFRA-3"))
            // Q72: issuesInProject() ∩ issuesAssignedTo(Engineering) — all issues ∩ assigned to Alice/Bob/Eve = 11
            assertThat(q72.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-7","ENG-8","ENG-10","ENG-12","INFRA-1","INFRA-2"))
            // Q73: issuesInProject(ENG) \ issuesAssignedTo() = ENG unassigned issues = 5
            assertThat(q73.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-9","ENG-11","ENG-13","ENG-14"))
            // Q74–Q78: results intersect/subtract a positional slice — content is scan-order dependent
            // Q74: skip(10) ∩ critical: 4 critical issues, ≤4 can appear in positions ≥10
            assertThat(q74.resultKeys(tx).size).isAtMost(4)
            // Q75: limit(5) \ has-sprint: first 5 issues minus sprinted ones
            assertThat(q75.resultKeys(tx).size).isAtMost(5)
            // Q76: skip(10) ∩ issuesInProject(ENG): ENG issues in positions ≥10 (min 4, max 14)
            assertThat(q76.resultKeys(tx).size).isAtLeast(4)
            // Q77: union(skip1,skip2) ∩ open: 23 issues minus pos0, ∩ 13 open = 12 or 13
            assertThat(q77.resultKeys(tx).size).isAtLeast(12)
            assertThat(q77.resultKeys(tx).size).isAtMost(13)
            // Q78: union(skip1,skip2) \ critical: 23 - (3 or 4 critical) = 19 or 20
            assertThat(q78.resultKeys(tx).size).isAtLeast(19)
            // Q79: reversed(all) ∩ open = all 13 open issues
            assertThat(q79.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14",
                       "OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q80: SortBy(issuesInProject(ENG),priority) ∩ open = 9 ENG open issues with sort
            assertThat(q80.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14"))
            // Q81: (issuesInProject(ENG) ∩ open) ∩ critical = ENG-1, ENG-6
            assertThat(q81.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6"))
            // Q82: (issuesInProject(ENG) ∩ open) \ has-assignee = ENG open unassigned = 4 issues
            assertThat(q82.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-11","ENG-13","ENG-14"))
            // Q83-Q86 real: use actual dataset RIDs
            val eng1Rid  = rid(dataset.issues["ENG-1"]!!)
            val eng2Rid  = rid(dataset.issues["ENG-2"]!!)
            val infra1Rid = rid(dataset.issues["INFRA-1"]!!)
            // Q83 real: {ENG-1,ENG-2} ∩ issuesInProject(ENG) = ENG-1, ENG-2
            assertThat(ByIds(listOf(eng1Rid, eng2Rid)).intersect(issuesInProject(PropEqual("key","ENG"))).resultKeys(tx))
                .containsExactlyElementsIn(listOf("ENG-1","ENG-2"))
            // Q84 real: {ENG-1,INFRA-1} \ issuesInProject(ENG) = INFRA-1
            assertThat(ByIds(listOf(eng1Rid, infra1Rid)).difference(issuesInProject(PropEqual("key","ENG"))).resultKeys(tx))
                .containsExactly("INFRA-1")
            // Q85 real: issuesInProject(ENG) ∩ {ENG-1,ENG-2} = ENG-1, ENG-2
            assertThat(issuesInProject(PropEqual("key","ENG")).intersect(ByIds(listOf(eng1Rid, eng2Rid))).resultKeys(tx))
                .containsExactlyElementsIn(listOf("ENG-1","ENG-2"))
            // Q86 real: issuesInProject(ENG) \ {ENG-1,ENG-2} = ENG-3..ENG-14 (12 issues)
            assertThat(issuesInProject(PropEqual("key","ENG")).difference(ByIds(listOf(eng1Rid, eng2Rid))).resultKeys(tx))
                .containsExactlyElementsIn((3..14).map { "ENG-$it" })
        }
    }

    // =========================================================================
    // Group 10 — O9 coalescing via Or.simplify() (the combineBinary path)
    // =========================================================================

    @Test
    fun `group 10 - O9 PropWithin coalescing via Or simplify`() {

        // Q87: Or(PropEqual, PropEqual) on same property — constructed directly,
        // mirrors what NodeFactory.or() / combineBinary produces.
        // Where.of(Or(a,b)) calls Or.simplify() → PropWithin coalescing fires here.
        val q87 = issues(Or(PropEqual("status", "open"), PropEqual("status", "resolved")))
        println("[Q87 Or.simplify PropEqual+PropEqual] query  : $q87")
        println("[Q87 Or.simplify PropEqual+PropEqual] gremlin: ${q87.toGremlin()}")
        assertThat(q87.toGremlin())
            .isEqualTo("""g.V().has("status",P.within(["open", "resolved"])).hasLabel("Issue")""")

        // Q88: Or(PropEqual, PropWithin) on same property — coalesces into PropWithin
        val q88 = issues(Or(PropEqual("priority", "critical"), PropWithin("priority", listOf("high", "medium"))))
        println("[Q88 Or.simplify PropEqual+PropWithin] query  : $q88")
        println("[Q88 Or.simplify PropEqual+PropWithin] gremlin: ${q88.toGremlin()}")
        assertThat(q88.toGremlin())
            .isEqualTo("""g.V().has("priority",P.within(["critical", "high", "medium"])).hasLabel("Issue")""")

        // Q89: Or(PropEqual, PropEqual) on DIFFERENT properties — must NOT coalesce
        val q89 = issues(Or(PropEqual("status", "open"), PropEqual("priority", "critical")))
        println("[Q89 Or.simplify PropEqual+PropEqual different props] query  : $q89")
        println("[Q89 Or.simplify PropEqual+PropEqual different props] gremlin: ${q89.toGremlin()}")
        assertThat(q89.toGremlin())
            .isEqualTo("""g.V().or(__.has("status","open"),__.has("priority","critical")).hasLabel("Issue")""")

        // ---- Result assertions ----
        withLowLevelTx { tx ->
            // Q87: Or(open, resolved) = 18 issues
            assertThat(q87.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-4","ENG-5","ENG-6","ENG-8","ENG-9","ENG-10","ENG-11",
                       "ENG-13","ENG-14","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-2","INFRA-3","INFRA-4"))
            // Q88: priority in [critical, high, medium] = 19 issues (all except low: ENG-5,9,11,INFRA-4,ARC-1)
            assertThat(q88.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-6","ENG-7","ENG-8","ENG-10","ENG-12",
                       "ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-1","INFRA-2","INFRA-3"))
            // Q89: Or(open, critical) = 14 issues (13 open + OPS-1 which is critical/in-progress)
            assertThat(q89.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14",
                       "OPS-1","OPS-2","OPS-4","INFRA-3","INFRA-4"))
        }
    }

    // =========================================================================
    // Group 11 — O11: condition × FollowLink(srcCond)
    //
    // When the FollowLink source has a concrete extractable condition (not All),
    // the link-side set membership is re-expressed as an inverse-link predicate:
    //   v ∈ FollowLink(src, IN, "project") iff v.out("project_link") reaches a vertex
    //   satisfying src.
    //
    // O11 rewrites:
    //   cond \ link(srcCond)  →  And(cond, Not(Where(out("link").srcCond.hasLabel(srcLabel))))
    //   cond ∪ link(srcCond)  →  Or(cond,     Where(out("link").srcCond.hasLabel(srcLabel)))
    // eliminating the Aggregate/UnionAll in favour of a single Labeled(Where) traversal.
    // =========================================================================

    @Test
    fun `group 11 - O11 condition x FollowLink(srcCond)`() {

        // Q90: condition(left) \ FollowLink(srcCond)(right)
        // Open issues that are NOT in project ENG.
        // O11: inverse = Where(out("project_link").has("key","ENG").hasLabel("Project"))
        val q90 = issues(PropEqual("status", "open"))
            .difference(issuesInProject(PropEqual("key", "ENG")))
        println("[Q90 condition difference followlink-with-src] query  : $q90")
        println("[Q90 condition difference followlink-with-src] gremlin: ${q90.toGremlin()}")
        assertThat(q90.toGremlin()).isEqualTo(
            """g.V().and(__.has("status","open"),""" +
            """__.not(__.where(__.out("project_link").has("key","ENG").hasLabel("Project")))).hasLabel("Issue")"""
        )

        // Q91: condition(left) ∪ FollowLink(srcCond)(right)
        // Open issues plus all issues in project ENG (may overlap).
        // O11: inverse = Where(out("project_link").has("key","ENG").hasLabel("Project"))
        val q91 = issues(PropEqual("status", "open"))
            .union(issuesInProject(PropEqual("key", "ENG")))
        println("[Q91 condition union followlink-with-src] query  : $q91")
        println("[Q91 condition union followlink-with-src] gremlin: ${q91.toGremlin()}")
        assertThat(q91.toGremlin()).isEqualTo(
            """g.V().or(__.has("status","open"),""" +
            """__.where(__.out("project_link").has("key","ENG").hasLabel("Project"))).hasLabel("Issue")"""
        )

        // Q92: ByIds(left) \ FollowLink(srcCond)(right)
        // A specific set of issues, excluding those in project ENG.
        // O11: IdWithin is extractable from ByIds; same inverse-link predicate shape as Q90.
        val q92 = ByIds(listOf(issueRid1, issueRid2))
            .difference(issuesInProject(PropEqual("key", "ENG")))
        println("[Q92 byids difference followlink-with-src] query  : $q92")
        println("[Q92 byids difference followlink-with-src] gremlin: ${q92.toGremlin()}")
        assertThat(q92.toGremlin()).isEqualTo(
            """g.V().and(__.hasId(P.within([#30:1, #30:2])),""" +
            """__.not(__.where(__.out("project_link").has("key","ENG").hasLabel("Project")))).hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        // Q90 and Q91 use no fake RIDs (FollowLink with string condition).
        // Q92 uses fake RIDs; build a real query with dataset entity RIDs.
        withLowLevelTx { tx ->
            // Q90: open \ issuesInProject(ENG) = 4 open non-ENG issues
            assertThat(q90.resultKeys(tx)).containsExactlyElementsIn(listOf("OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q91: open ∪ issuesInProject(ENG) = 18 issues (13 open + 5 non-open ENG: ENG-3,4,7,9,12)
            assertThat(q91.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","OPS-2","OPS-4","INFRA-3","INFRA-4"))
            // Q92 real: {INFRA-1, INFRA-2} \ issuesInProject(ENG) — neither is in ENG, so both survive
            val q92real = ByIds(listOf(rid(dataset.issues["INFRA-1"]!!), rid(dataset.issues["INFRA-2"]!!)))
                .difference(issuesInProject(PropEqual("key","ENG")))
            assertThat(q92real.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA-1","INFRA-2"))
        }
    }

    // =========================================================================
    // Group 12 — Multi-hop and structural coverage (Q93–Q104)
    // =========================================================================

    @Test
    fun `group 12 - Q93 multi-hop Issue via Project via Engineering lead`() {

        // Q93: Issues in projects whose lead is an Engineering employee (3-hop traversal).
        // employees(Engineering) → IN "lead" → Project → IN "project" → Issue
        // No combineEfficient involved — this is a single chained FollowLink traversal.
        // Alice leads ENG (14 issues), Bob leads INFRA (4 issues), Eve leads nobody.
        val projectsLedByEngineers = Labeled(
            FollowLink(employees(PropEqual("department", "Engineering")), LinkDirection.IN, "lead"),
            "Project"
        )
        val q93 = Labeled(FollowLink(projectsLedByEngineers, LinkDirection.IN, "project"), "Issue")
        println("[Q93 3-hop eng-lead projects issues] query  : $q93")
        println("[Q93 3-hop eng-lead projects issues] gremlin: ${q93.toGremlin()}")
        assertThat(q93.toGremlin()).isEqualTo(
            """g.V().has("department","Engineering").hasLabel("Employee")""" +
            """.in("lead_link").hasLabel("Project").in("project_link").hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        withLowLevelTx { tx ->
            // ENG (Alice, Engineering) + INFRA (Bob, Engineering) = 14 + 4 = 18 issues
            // OPS (Carol, Operations) and ARC (Dave, plain User — not Employee) are excluded
            assertThat(q93.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9",
                       "ENG-10","ENG-11","ENG-12","ENG-13","ENG-14",
                       "INFRA-1","INFRA-2","INFRA-3","INFRA-4")
            )
        }
    }

    @Test
    fun `group 12 - Q94 Sprint FollowLink to Project (non-Issue target type)`() {

        // Q94: Sprints that belong to the ENG project.
        // Sprint --project--> Project, so to find sprints for a project we traverse IN via "project".
        // Exercises FollowLink targeting Sprint rather than Issue — the only prior FollowLink tests
        // all produce Issue entities. Gremlin and label handling should be identical; this
        // confirms no Issue-specific assumptions are baked in.
        val q94 = Labeled(FollowLink(projects(PropEqual("key", "ENG")), LinkDirection.IN, "project"), "Sprint")
        println("[Q94 sprints in ENG project] query  : $q94")
        println("[Q94 sprints in ENG project] gremlin: ${q94.toGremlin()}")
        assertThat(q94.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").hasLabel("Sprint")"""
        )

        // ---- Result assertions ----
        // S1→ENG, S2→ENG, S3→OPS  →  only S1 and S2
        withLowLevelTx { tx ->
            assertThat(q94.resultKeys(tx)).containsExactlyElementsIn(listOf("S1", "S2"))
        }
    }

    @Test
    fun `group 12 - Q95 self-referential parent link`() {

        // Q95a: Issues that are subtasks (have a parent Issue).
        // Issue --parent--> Issue; traversing IN via "parent" from all issues gives us the subtasks.
        // ENG-12→ENG-3, ENG-13→ENG-3, ENG-14→ENG-5
        val q95a = Labeled(FollowLink(issues(), LinkDirection.IN, "parent"), "Issue")
        println("[Q95a subtasks] query  : $q95a")
        println("[Q95a subtasks] gremlin: ${q95a.toGremlin()}")
        assertThat(q95a.toGremlin()).isEqualTo(
            """g.V().hasLabel("Issue").in("parent_link").hasLabel("Issue")"""
        )

        // Q95b: Subtasks of issues that themselves have subtasks (2-hop self-referential).
        // issues() → IN "parent" → issues with a parent → IN "parent" again → empty in this dataset
        // (ENG-3 and ENG-5 are parents but neither has its own parent)
        val q95b = Labeled(FollowLink(q95a, LinkDirection.IN, "parent"), "Issue")
        println("[Q95b subtasks-of-subtasks] query  : $q95b")
        println("[Q95b subtasks-of-subtasks] gremlin: ${q95b.toGremlin()}")
        assertThat(q95b.toGremlin()).isEqualTo(
            """g.V().hasLabel("Issue").in("parent_link").hasLabel("Issue").in("parent_link").hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        withLowLevelTx { tx ->
            // Q95a: ENG-12, ENG-13, ENG-14 are subtasks
            assertThat(q95a.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-12","ENG-13","ENG-14"))
            // Q95b: no issue in the dataset is a subtask of a subtask
            assertThat(q95b.resultKeys(tx)).isEmpty()
        }
    }

    @Test
    fun `group 12 - Q96 FollowLink union FollowLink (different link names, O4 miss)`() {

        // Q96: Issues assigned to Alice OR in sprint S1.
        // Two FollowLink operands with different link names (assignee vs sprint).
        // O4 requires same link name and direction — misses here → Order(UnionAll) fallback.
        val q96 = issuesAssignedTo(PropEqual("name", "Alice"))
            .union(issuesInSprint(PropEqual("key", "S1")))
        println("[Q96 assigned-alice union in-sprint-S1] query  : $q96")
        println("[Q96 assigned-alice union in-sprint-S1] gremlin: ${q96.toGremlin()}")
        assertThat(q96.toGremlin()).isEqualTo(
            """g.union(__.V().has("name","Alice").hasLabel("Employee").in("assignee_link").hasLabel("Issue")""" +
            """,__.V().has("key","S1").hasLabel("Sprint").in("sprint_link").hasLabel("Issue")).dedup()"""
        )

        // ---- Result assertions ----
        // Alice's issues: ENG-1,3,5,10,12
        // S1 issues:      ENG-1,2,3,6,10,12,13
        // Union (deduped): ENG-1,2,3,5,6,10,12,13 — 8 issues
        withLowLevelTx { tx ->
            assertThat(q96.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-5","ENG-6","ENG-10","ENG-12","ENG-13")
            )
        }
    }

    @Test
    fun `group 12 - Q97 FollowLink difference FollowLink (both with source conditions, Aggregate fallback)`() {

        // Q97: ENG issues that are NOT assigned to Engineering employees.
        // Both sides are Labeled(FollowLink(...)) — O7 only applies when one side is an extractable
        // condition; O11 only applies when the right side is FollowLink and the left is a condition.
        // Neither fires here → Aggregate fallback (same shape as Q72/Q73 but with conditioned sources).
        val q97 = issuesInProject(PropEqual("key", "ENG"))
            .difference(issuesAssignedTo(PropEqual("department", "Engineering")))
        println("[Q97 eng-issues minus eng-assigned, aggregate] query  : $q97")
        println("[Q97 eng-issues minus eng-assigned, aggregate] gremlin: ${q97.toGremlin()}")
        assertThat(q97.toGremlin()).isEqualTo(
            """g.V().has("department","Engineering").hasLabel("Employee").in("assignee_link").hasLabel("Issue")""" +
            """.aggregate("aggr_0").fold()""" +
            """.V().has("key","ENG").hasLabel("Project").in("project_link").hasLabel("Issue")""" +
            """.where(P.without(["aggr_0"]))"""
        )

        // ---- Result assertions ----
        // Engineering employees: Alice(ENG-1,3,5,10,12), Bob(ENG-2,4,8), Eve(ENG-7)
        // Engineering-assigned ENG issues: ENG-1,2,3,4,5,7,8,10,12
        // ENG \ Engineering-assigned = ENG-6,9,11,13,14
        withLowLevelTx { tx ->
            assertThat(q97.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-6","ENG-9","ENG-11","ENG-13","ENG-14")
            )
        }
    }

    @Test
    fun `group 12 - Q98 three-way property union chain (O8 flatten + O9 coalesce)`() {

        // Q98: Issues with priority ∈ {critical, high, medium} built as a left-to-right chain.
        // First union: Or(critical, high) → coalesces to PropWithin("priority", ["critical","high"]) via O9.
        // Second union: Or(PropWithin(...), medium) → O8 flattens to Or(critical,high,medium) → O9 coalesces
        // to PropWithin("priority", ["critical","high","medium"]).
        val q98 = issues(PropEqual("priority", "critical"))
            .union(issues(PropEqual("priority", "high")))
            .union(issues(PropEqual("priority", "medium")))
        println("[Q98 three-way union critical+high+medium] query  : $q98")
        println("[Q98 three-way union critical+high+medium] gremlin: ${q98.toGremlin()}")
        assertThat(q98.toGremlin()).isEqualTo(
            """g.V().has("priority",P.within(["critical", "high", "medium"])).hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        // critical: ENG-1,6 + OPS-1,4 = 4
        // high:     ENG-2,4,7,10 + OPS-2 + INFRA-2,3 = 7
        // medium:   ENG-3,8,12,13,14 + OPS-3,5 + INFRA-1 = 8
        // total: 19
        withLowLevelTx { tx ->
            // Q98: critical(4) + high(7) + medium(8) = 19 issues
            assertThat(q98.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-6","ENG-7","ENG-8","ENG-10","ENG-12",
                       "ENG-13","ENG-14","OPS-1","OPS-2","OPS-3","OPS-4","OPS-5","INFRA-1","INFRA-2","INFRA-3"))
        }
    }

    @Test
    fun `group 12 - Q99 ByIds union FollowLink (O11 union path)`() {

        // Q99: Two specific INFRA issues OR any ENG issue.
        // O11 fires for union: left=ByIds (extractable IdWithin), right=Labeled(FollowLink).
        // Produces Or(IdWithin([...]), Where(out("project_link").has("key","ENG").hasLabel("Project"))).
        // Uses fake RIDs for the Gremlin string assertion; real RIDs for the result assertion.
        val q99fake = ByIds(listOf(issueRid1, issueRid2))
            .union(issuesInProject(PropEqual("key", "ENG")))
        println("[Q99 byids union followlink O11] query  : $q99fake")
        println("[Q99 byids union followlink O11] gremlin: ${q99fake.toGremlin()}")
        assertThat(q99fake.toGremlin()).isEqualTo(
            """g.V().or(__.hasId(P.within([#30:1, #30:2])),""" +
            """__.where(__.out("project_link").has("key","ENG").hasLabel("Project"))).hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        // {INFRA-1, INFRA-2} ∪ ENG-1..14 = 16 issues (INFRA issues are not in ENG)
        withLowLevelTx { tx ->
            val infra1Rid = rid(dataset.issues["INFRA-1"]!!)
            val infra2Rid = rid(dataset.issues["INFRA-2"]!!)
            val q99real = ByIds(listOf(infra1Rid, infra2Rid))
                .union(issuesInProject(PropEqual("key", "ENG")))
            // Q99real: {INFRA-1, INFRA-2} ∪ ENG-1..14 = 16 issues
            assertThat(q99real.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9","ENG-10",
                       "ENG-11","ENG-12","ENG-13","ENG-14","INFRA-1","INFRA-2"))
        }
    }

    @Test
    fun `group 12 - Q100 class hierarchy - hasLabel User matches Employee and Manager subtypes`() {

        // Q100: Issues assigned to any User (the supertype), which in the dataset includes
        // Employee (Alice, Bob, Carol) and Manager (Eve) subtypes.
        // Dave is a plain User but has no assigned issues.
        // hasLabel("User") in YouTrackDB should match all three: User, Employee, Manager.
        val q100 = Labeled(FollowLink(users(), LinkDirection.IN, "assignee"), "Issue")
        println("[Q100 issues assigned to any User] query  : $q100")
        println("[Q100 issues assigned to any User] gremlin: ${q100.toGremlin()}")
        assertThat(q100.toGremlin()).isEqualTo(
            """g.V().hasLabel("User").in("assignee_link").hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        // Alice: ENG-1,3,5,10,12 (5); Bob: ENG-2,4,8 + INFRA-1,2 (5); Carol: OPS-1,2,3,5 (4);
        // Eve: ENG-7 (1); Dave: none. Total assigned = 15.
        withLowLevelTx { tx ->
            // Q100: Alice(5)+Bob(5)+Carol(4)+Eve(1) = 15 assigned issues; Dave has none
            assertThat(q100.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-7","ENG-8","ENG-10","ENG-12",
                       "OPS-1","OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2"))
        }
    }

    @Test
    fun `group 12 - Q101 three-way intersect chain (chained combineEfficient extractions)`() {

        // Q101: Open critical issues with estimate in [1, 8].
        // First intersect: And(open, critical) — fused into a single Where by combineEfficient.
        // Second intersect: And(And(open,critical), inRange(1,8)) — extractCondition sees the
        // compound Where(And(...)) result and fuses again.
        val q101 = issues(PropEqual("status", "open"))
            .intersect(issues(PropEqual("priority", "critical")))
            .intersect(issues(PropInRange("estimate", 1, 8)))
        println("[Q101 open+critical+estimate(1-8) triple intersect] query  : $q101")
        println("[Q101 open+critical+estimate(1-8) triple intersect] gremlin: ${q101.toGremlin()}")
        // O8 flattens And(And(open,critical), range) → And(open,critical,range) — 3-ary.
        // O12 chains all three (all chainable): .has().has().has() instead of .and(__.has(),...).
        assertThat(q101.toGremlin()).isEqualTo(
            """g.V().has("status","open").has("priority","critical")""" +
            """.has("estimate",P.gte((int) 1).and(P.lte((int) 8))).hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        // open ∩ critical: ENG-1(est=5), ENG-6(est=1), OPS-4(est=3)  [OPS-1 is in-progress, not open]
        // ∩ estimate[1,8]: all three qualify (5, 1, 3 are all ≤ 8)
        withLowLevelTx { tx ->
            assertThat(q101.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG-1","ENG-6","OPS-4"))
        }
    }

    @Test
    fun `group 12 - Q102 All issues minus tagged issues (All difference HasLink → HasNoLink)`() {

        // Q102: Issues without any tag.
        // combineEfficient: extractCondition(issues()) = All, extractCondition(issues(HasLink("tags"))) = HasLink("tags").
        // Difference.combineBlocks(All, HasLink("tags")) → Not(HasLink("tags")) → simplify → HasNoLink("tags").
        val q102 = issues().difference(issues(HasLink("tags")))
        println("[Q102 issues without any tag] query  : $q102")
        println("[Q102 issues without any tag] gremlin: ${q102.toGremlin()}")
        assertThat(q102.toGremlin()).isEqualTo(
            """g.V().not(__.out("tags_link")).hasLabel("Issue")"""
        )

        // ---- Result assertions ----
        // Tagged issues: bug(ENG-1,2,6,10,OPS-1,4,INFRA-3=7) + feature(ENG-3,11=2) + performance(ENG-4,8=2) = 11
        // Untagged: 24 - 11 = 13
        withLowLevelTx { tx ->
            // Q102: 24 issues \ 11 tagged = 13 untagged issues
            assertThat(q102.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-5","ENG-7","ENG-9","ENG-12","ENG-13","ENG-14",
                       "OPS-2","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-4","ARC-1"))
        }
    }

    @Test
    fun `group 12 - Q103 SortBy(FollowLink) intersect SortBy(Where) — O3 + O7 interaction`() {

        // Q103: ENG issues sorted by priority, intersected with open issues sorted by priority.
        // O3 recurses on both SortBy wrappers:
        //   outer: this=SortBy(issuesInProject(ENG), byPriority), other=SortBy(issues(open), byPriority)
        //   → strips other's sort, recurses: issuesInProject(ENG).intersect(issues(open))
        //   → O7 fires (left is FollowLink, right is condition) → Labeled(AndThen)
        //   → O3 re-wraps with left sort: SortBy(Labeled(AndThen), byPriority)
        val q103 = SortBy(issuesInProject(PropEqual("key", "ENG")), byPriority)
            .intersect(SortBy(issues(PropEqual("status", "open")), byPriority))
        println("[Q103 SortBy(FL) intersect SortBy(Where)] query  : $q103")
        println("[Q103 SortBy(FL) intersect SortBy(Where)] gremlin: ${q103.toGremlin()}")
        assertThat(q103.toGremlin()).isEqualTo(
            """g.V().has("key","ENG").hasLabel("Project").in("project_link").has("status","open").hasLabel("Issue")""" +
            byPriorityGremlin
        )

        // ---- Result assertions ----
        // ENG open issues: ENG-1,2,5,6,8,10,11,13,14 = 9
        withLowLevelTx { tx ->
            assertThat(q103.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-5","ENG-6","ENG-8","ENG-10","ENG-11","ENG-13","ENG-14")
            )
        }
    }

    @Test
    fun `group 12 - Q104 O4-fused FollowLink union then difference with condition (O17+O7 fused)`() {

        // Q104: Issues in ENG or OPS that are NOT open.
        // Step 1: issuesInProject(ENG).union(issuesInProject(OPS))
        //   → O4 fires (same link name "project", same direction IN, same label "Issue")
        //   → Order(Labeled(FollowLink(UnionedSrc, IN, "project"), "Issue"), Dedup)
        // Step 2: .difference(issues(open))
        //   → O17 strips Order(Dedup), O7 fires on Labeled(FollowLink): appends Not(PropEqual) to chain
        //   → Order(Labeled(AndThen(FollowLink(PropWithin src, IN, "project"), Not(PropEqual("status","open"))), "Issue"), Dedup)
        val engOrOps = issuesInProject(PropEqual("key", "ENG"))
            .union(issuesInProject(PropEqual("key", "OPS")))
        val q104 = engOrOps.difference(issues(PropEqual("status", "open")))
        println("[Q104 O4-fused FL union then difference open] query  : $q104")
        println("[Q104 O4-fused FL union then difference open] gremlin: ${q104.toGremlin()}")
        // O4 merges sources into PropWithin; O17+O7 then appends the negated condition inline.
        assertThat(q104.toGremlin()).isEqualTo(
            """g.V().has("key",P.within(["ENG", "OPS"])).hasLabel("Project").in("project_link")""" +
            """.not(__.has("status","open")).hasLabel("Issue").dedup()"""
        )

        // ---- Result assertions ----
        // ENG non-open: ENG-3(in-progress), ENG-4(resolved), ENG-7(in-progress),
        //               ENG-9(resolved), ENG-12(in-progress) = 5
        // OPS non-open: OPS-1(in-progress), OPS-3(resolved), OPS-5(resolved) = 3
        // Total: 8
        withLowLevelTx { tx ->
            assertThat(q104.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-3","ENG-4","ENG-7","ENG-9","ENG-12","OPS-1","OPS-3","OPS-5")
            )
        }
    }

    // =========================================================================
    // Group 13 — F1: condition \ Dedup(FollowLink(src, OUT, link))
    //
    // The F1 pattern arises as:
    //   condition \ flatMapDistinct { it.followLink(link, OUT) }
    //
    // Two gaps previously blocked this optimisation:
    //
    //   Gap A (fixed): O17 now also strips Dedup when it appears on the right of a difference.
    //
    //   Gap B (fixed): O11 now handles both IN and OUT FollowLink directions.
    //
    // F1a (src = simple condition, e.g. Labeled(Where)):
    //   O17 strips the Order(Dedup) wrapper, then O11 (extended to OUT direction)
    //   rewrites as an inverse predicate.
    //
    // F1b (src = FollowLink traversal):
    //   O11 cannot build an inverse predicate from a FollowLink source (extractCondition returns
    //   null). O11b handles this via a nested where predicate for the two-hop pattern.
    // =========================================================================

    @Test
    fun `group 13 - Q105 F1a projects difference Dedup(FollowLink(sprints-All, OUT))`() {

        // Q105: projects(All) \ Dedup(FollowLink(sprints(All), OUT, "project"))
        // Projects that no sprint points to.
        // After Gap-A strips Order(Dedup) and Gap-B extends O11 to OUT direction:
        //   src=All → inverse = Where(in("project_link").hasLabel("Sprint"))
        //   And(All, Not(inverse)) = Not(inverse) → not(__.where(__.in("project_link").hasLabel("Sprint")))
        val dedupSprintsToProject =
            Labeled(FollowLink(sprints(), LinkDirection.OUT, "project"), "Project").then(Dedup)
        val q105 = projects().difference(dedupSprintsToProject)
        println("[Q105 F1a projects diff Dedup(sprints->project) src=All] query  : $q105")
        println("[Q105 F1a projects diff Dedup(sprints->project) src=All] gremlin: ${q105.toGremlin()}")
        assertThat(q105.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.in("project_link").hasLabel("Sprint"))).hasLabel("Project")"""
        )

        // ---- Result assertions ----
        // Sprints in dataset: S1→ENG, S2→ENG, S3→OPS. Projects with no sprint: INFRA, ARC.
        withLowLevelTx { tx ->
            assertThat(q105.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA", "ARC"))
        }
    }

    @Test
    fun `group 13 - Q106 F1a projects difference Dedup(FollowLink(sprints-condition, OUT))`() {

        // Q106: projects(All) \ Dedup(FollowLink(sprints(active), OUT, "project"))
        // Projects that no active sprint points to.
        // After fix: inverse = Where(in("project_link").has("state","active").hasLabel("Sprint"))
        val dedupActiveSprintsToProject =
            Labeled(FollowLink(sprints(PropEqual("state", "active")), LinkDirection.OUT, "project"), "Project")
                .then(Dedup)
        val q106 = projects().difference(dedupActiveSprintsToProject)
        println("[Q106 F1a projects diff Dedup(active-sprints->project)] query  : $q106")
        println("[Q106 F1a projects diff Dedup(active-sprints->project)] gremlin: ${q106.toGremlin()}")
        assertThat(q106.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.in("project_link").has("state","active").hasLabel("Sprint"))).hasLabel("Project")"""
        )

        // ---- Result assertions ----
        // Active sprints: S1→ENG, S3→OPS. Projects with no active sprint: INFRA, ARC.
        withLowLevelTx { tx ->
            assertThat(q106.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA", "ARC"))
        }
    }

    @Test
    fun `group 13 - Q107 F1b projects difference Dedup(FollowLink(FollowLink-src, OUT))`() {

        // Q107: projects(All) \ Dedup(FollowLink(Labeled(FollowLink(issues, OUT, "sprint"), "Sprint"), OUT, "project"))
        // Projects not reachable via any sprint that has at least one issue.
        //
        // Edge directions:
        //   Issue --sprint_link--> Sprint  (Issue.sprint uses out("sprint_link"))
        //   Sprint --project_link--> Project  (Sprint.project uses out("project_link"))
        //
        // F1b: src is itself a FollowLink traversal — O11 cannot build an inverse predicate from it
        // (extractCondition of Labeled(FollowLink(...), T) returns null).
        // O11b fires instead and builds a nested where predicate.
        val sprintsWithIssues = Labeled(FollowLink(issues(), LinkDirection.OUT, "sprint"), "Sprint")
        val dedupSprintsWithIssuesToProject =
            Labeled(FollowLink(sprintsWithIssues, LinkDirection.OUT, "project"), "Project").then(Dedup)
        val q107 = projects().difference(dedupSprintsWithIssuesToProject)
        println("[Q107 F1b projects diff Dedup(sprints-via-issues->project)] query  : $q107")
        println("[Q107 F1b projects diff Dedup(sprints-via-issues->project)] gremlin: ${q107.toGremlin()}")
        // O11b: src = Labeled(FollowLink(issues, OUT, "sprint"), "Sprint")
        // Outer: direction=OUT, link="project" → inverse = in("project_link")
        //   (Sprint.project: Sprint --project_link--> Project; from Project, in("project_link") → Sprint)
        // Inner: direction=OUT, link="sprint"  → inverse = in("sprint_link")
        //   (Issue.sprint: Issue --sprint_link--> Sprint; from Sprint, in("sprint_link") → Issue)
        // innerSrc = issues() = Labeled(Where(All), "Issue") → condBlock=All, label="Issue"
        //   All.andThen(x) = x, so innerChain = in("sprint_link").hasLabel("Issue")
        // Full: not(where(in("project_link").where(in("sprint_link").hasLabel("Issue")).hasLabel("Sprint")))
        assertThat(q107.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.in("project_link")""" +
            """.where(__.in("sprint_link").hasLabel("Issue")).hasLabel("Sprint"))).hasLabel("Project")"""
        )

        // ---- Result assertions ----
        // Sprints with issues: S1→ENG (has issues), S2→ENG (has issues), S3→OPS (has issues).
        // Projects reachable via sprints-with-issues: ENG, OPS. Projects not reachable: INFRA, ARC.
        withLowLevelTx { tx ->
            assertThat(q107.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA", "ARC"))
        }
    }

    @Test
    fun `group 13 - Q112 O11c F1a bare FollowLink difference Dedup(FollowLink(src-All, OUT))`() {
        // Q112: projects(All) \ Dedup(FollowLink(sprints(), OUT, "project"))
        // Source-labeled variant of Q105: FollowLink has no outer Labeled wrapper — the label
        // is on the SOURCE argument, not the result.  This shape arises when traversing a link
        // without a result type restriction.
        //
        // After O17 strips Dedup, other = FollowLink(Labeled(Where(All), "Sprint"), OUT, "project").
        // O11c fires (bare FollowLink path):
        //   srcCondBlock = All, srcLabel = "Sprint", direction = OUT → linkStep = InLink("project")
        //   All + OUT direction → else branch: chain = in("project_link").hasLabel("Sprint")
        //   inversePredicate = where(in("project_link").hasLabel("Sprint"))
        //   combined = not(where(in("project_link").hasLabel("Sprint")))
        val dedupSprintsToProject = FollowLink(sprints(), LinkDirection.OUT, "project").then(Dedup)
        val q112 = projects().difference(dedupSprintsToProject)
        println("[Q112 O11c F1a bare-FL diff Dedup(sprints-All->project)] query  : $q112")
        println("[Q112 O11c F1a bare-FL diff Dedup(sprints-All->project)] gremlin: ${q112.toGremlin()}")
        assertThat(q112.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.in("project_link").hasLabel("Sprint"))).hasLabel("Project")"""
        )
        withLowLevelTx { tx ->
            assertThat(q112.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA", "ARC"))
        }
    }

    @Test
    fun `group 13 - Q113 O11c F1a bare FollowLink difference Dedup(FollowLink(src-condition, OUT))`() {
        // Q113: projects(All) \ Dedup(FollowLink(sprints(condition), OUT, "project"))
        // Source-labeled variant of Q106: same as Q112 but source carries a non-All condition.
        //
        // O11c:
        //   srcCondBlock = PropEqual("state", "active"), srcLabel = "Sprint", direction = OUT
        //   chain = in("project_link").has("state", "active").hasLabel("Sprint")
        //   inversePredicate = where(in("project_link").has("state", "active").hasLabel("Sprint"))
        val dedupActiveSprintsToProject =
            FollowLink(sprints(PropEqual("state", "active")), LinkDirection.OUT, "project").then(Dedup)
        val q113 = projects().difference(dedupActiveSprintsToProject)
        println("[Q113 O11c F1a bare-FL diff Dedup(sprints-condition->project)] query  : $q113")
        println("[Q113 O11c F1a bare-FL diff Dedup(sprints-condition->project)] gremlin: ${q113.toGremlin()}")
        assertThat(q113.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.in("project_link").has("state","active").hasLabel("Sprint"))).hasLabel("Project")"""
        )
        withLowLevelTx { tx ->
            assertThat(q113.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA", "ARC"))
        }
    }

    @Test
    fun `group 13 - Q114 O11d F1b bare FollowLink difference Dedup(FollowLink(FollowLink-src, OUT))`() {
        // Q114: projects(All) \ Dedup(FollowLink(Labeled(FollowLink(issues, OUT, "sprint"), "Sprint"), OUT, "project"))
        // Source-labeled variant of Q107: the bare FollowLink has a two-hop Labeled(FollowLink) source.
        // O11c main path fails (extractCondition of Labeled(FollowLink) = null).
        // O11d fires:
        //   fl        = FollowLink(Labeled(FollowLink(issues, OUT, "sprint"), "Sprint"), OUT, "project")
        //   srcLabeled = Labeled(FollowLink(issues, OUT, "sprint"), "Sprint"); innerFL = FollowLink(issues, OUT, "sprint")
        //   innerSrcCondBlock = All, srcLabel = "Sprint", innerSrcLabel = "Issue"
        //   outerLinkStep = InLink("project"), innerLinkStep = InLink("sprint")
        //   innerChain = in("sprint_link").hasLabel("Issue")
        //   outerChain = in("project_link").where(in("sprint_link").hasLabel("Issue")).hasLabel("Sprint")
        val sprintsWithIssues = Labeled(FollowLink(issues(), LinkDirection.OUT, "sprint"), "Sprint")
        val dedupSprintsWithIssuesToProject =
            FollowLink(sprintsWithIssues, LinkDirection.OUT, "project").then(Dedup)
        val q114 = projects().difference(dedupSprintsWithIssuesToProject)
        println("[Q114 O11d F1b bare-FL diff Dedup(sprints-via-issues->project)] query  : $q114")
        println("[Q114 O11d F1b bare-FL diff Dedup(sprints-via-issues->project)] gremlin: ${q114.toGremlin()}")
        assertThat(q114.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.in("project_link")""" +
            """.where(__.in("sprint_link").hasLabel("Issue")).hasLabel("Sprint"))).hasLabel("Project")"""
        )
        withLowLevelTx { tx ->
            assertThat(q114.resultKeys(tx)).containsExactlyElementsIn(listOf("INFRA", "ARC"))
        }
    }

    @Test
    fun `group 13 - Q115 O11c bare FollowLink difference Dedup(FollowLink(src-All, IN)) HasLink shortcut`() {
        // Q115: issues(All) \ Dedup(FollowLink(sprints(), IN, "sprint"))
        // Issues that have no sprint — IN direction with All source triggers the HasLink shortcut.
        //
        // O11c: srcCondBlock=All, direction=IN → HasLink("sprint")
        //   inversePredicate = HasLink("sprint")
        //   combined = Not(HasLink("sprint"))
        //   result = Labeled(Where(Not(HasLink("sprint"))), "Issue")
        //
        // This is the IN-direction counterpart of Q112 (which uses OUT direction and the else branch).
        val q115 = issues().difference(FollowLink(sprints(), LinkDirection.IN, "sprint").then(Dedup))
        println("[Q115 O11c bare-FL IN HasLink shortcut] query  : $q115")
        println("[Q115 O11c bare-FL IN HasLink shortcut] gremlin: ${q115.toGremlin()}")
        assertThat(q115.toGremlin()).isEqualTo(
            """g.V().not(__.out("sprint_link")).hasLabel("Issue")"""
        )
        withLowLevelTx { tx ->
            // Same result as issues(HasNoLink("sprint")) / Q13: 13 issues with no sprint
            assertThat(q115.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-5","ENG-8","ENG-9","ENG-11","ENG-14",
                       "OPS-1","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
        }
    }

    @Test
    fun `group 13 - Q116 O11c bare FollowLink difference Dedup(FollowLink(src-condition, IN)) else branch`() {
        // Q116: issues(All) \ Dedup(FollowLink(sprints(active), IN, "sprint"))
        // Issues not in any active sprint — IN direction with non-All source uses the else branch.
        //
        // O11c: srcCondBlock=PropEqual("state","active"), srcLabel="Sprint", direction=IN
        //   linkStep = OutLink("sprint")  (direction IN → invert to OUT)
        //   chain = out("sprint_link").has("state","active").hasLabel("Sprint")
        //   inversePredicate = Where(chain)
        //   combined = Not(Where(chain))
        //
        // Active sprints: S1 (ENG), S3 (OPS); S2 is not active.
        // Issues in active sprint: ENG-1,2,3,6,10,12,13 (S1) + OPS-2,4 (S3) = 9 issues.
        val q116 = issues().difference(
            FollowLink(sprints(PropEqual("state", "active")), LinkDirection.IN, "sprint").then(Dedup)
        )
        println("[Q116 O11c bare-FL IN condition else-branch] query  : $q116")
        println("[Q116 O11c bare-FL IN condition else-branch] gremlin: ${q116.toGremlin()}")
        assertThat(q116.toGremlin()).isEqualTo(
            """g.V().not(__.where(__.out("sprint_link").has("state","active").hasLabel("Sprint"))).hasLabel("Issue")"""
        )
        withLowLevelTx { tx ->
            // 24 total - 9 in active sprint = 15 issues
            assertThat(q116.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-4","ENG-5","ENG-7","ENG-8","ENG-9","ENG-11","ENG-14",
                       "OPS-1","OPS-3","OPS-5","INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
        }
    }

    @Test
    fun `group 13 - Q117 O11c bare FollowLink union FollowLink(src-All, IN) Union combiner`() {
        // Q117: issues(HasNoLink("sprint")).union(FollowLink(sprints(), IN, "sprint"))
        // Issues without a sprint ∪ issues with any sprint = all issues.
        // Exercises O11c with the Union combiner (combineBlocks produces Or instead of Not).
        //
        // Note: O17 only strips Dedup for Difference, not Union, so passing `.then(Dedup)` here
        // would leave an Order wrapper that prevents O11c from matching. The bare FollowLink is
        // the correct input for testing the Union path.
        //
        // O11c with Union: srcCondBlock=All, direction=IN → HasLink("sprint")
        //   thisCondBlock = HasNoLink("sprint")
        //   combined = Or(HasNoLink("sprint"), HasLink("sprint"))
        //   result = Labeled(Where(Or(HasNoLink("sprint"), HasLink("sprint"))), "Issue")
        val q117 = issues(HasNoLink("sprint")).union(
            FollowLink(sprints(), LinkDirection.IN, "sprint")
        )
        println("[Q117 O11c bare-FL IN Union combiner] query  : $q117")
        println("[Q117 O11c bare-FL IN Union combiner] gremlin: ${q117.toGremlin()}")
        // Or(HasNoLink("sprint"), HasLink("sprint")) = Or(Not(HasLink), HasLink) simplifies to All
        assertThat(q117.toGremlin()).isEqualTo("""g.V().hasLabel("Issue")""")
        withLowLevelTx { tx ->
            // Union of complement sets = all 24 issues
            assertThat(q117.resultKeys(tx)).containsExactlyElementsIn(
                listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8",
                       "ENG-9","ENG-10","ENG-11","ENG-12","ENG-13","ENG-14",
                       "OPS-1","OPS-2","OPS-3","OPS-4","OPS-5",
                       "INFRA-1","INFRA-2","INFRA-3","INFRA-4","ARC-1"))
        }
    }

    // =========================================================================
    // Group 14 — F2/F3: Labeled(Labeled(Where(block), T1), T2) as operand (O19)
    //
    // The double-label pattern arises as the right operand of intersect/difference
    // when a query targets an entity that participates in a class hierarchy — for example
    // "vertices labeled both TypeA AND TypeB". extractCondition currently
    // returns null for Labeled(Labeled(Condition, T1), T2) because the inner is not a
    // bare Condition. O19 extends extractCondition to handle this case:
    //   extractCondition(Labeled(Labeled(Where(All), T1), T2)) → HasLabel(T1)
    //
    // Two sites need updating:
    //   - extractCondition itself (O19)
    //   - O7: after fusing a FollowLink with HasLabel(T1), also apply the outer T2 label
    //
    // Data model used: User ← Employee ← Manager hierarchy.
    //   Labeled(Labeled(Where(All), "Employee"), "User") = vertices that are both Employee and User
    //   (in YouTrackDB, Employee inherits User so Employee vertices carry both labels).
    //
    // F3 shape: Labeled(Where(All), T) ∩ Labeled(Labeled(Where(All), T_sub), T) — outer labels match.
    //   → bottom extractCondition path: combines conditions directly, no O7 involved.
    // F2 shape: FollowLink(ByIds, OUT, link) ∩ Labeled(Labeled(Where(All), T1), T2) — bare FollowLink left.
    //   → O7 path: fuse HasLabel(T1) into FollowLink chain, then wrap result with outer T2.
    // =========================================================================

    @Test
    fun `group 14 - Q108 F3 users(All) intersect double-labeled Employee`() {

        // Q108: users(All) ∩ Labeled(Labeled(Where(All), "Employee"), "User")
        // All Users who are also Employees.
        // F3 shape: outer labels match ("User" == "User") — goes through the bottom condition combiner.
        // After O19: extractCondition(right) = HasLabel("Employee"),
        //   combineBlocks(All, HasLabel("Employee")) = HasLabel("Employee")
        //   → Labeled(Where(HasLabel("Employee")), "User")
        val employeesAsUsers = Labeled(Labeled(Where.of(All), "Employee"), "User")
        val q108 = users().intersect(employeesAsUsers)
        println("[Q108 F3 users intersect double-labeled Employee] query  : $q108")
        println("[Q108 F3 users intersect double-labeled Employee] gremlin: ${q108.toGremlin()}")
        assertThat(q108.toGremlin()).isEqualTo(
            """g.V().hasLabel("Employee").hasLabel("User")"""
        )

        // ---- Result assertions ----
        // All Employees (including Manager): Alice, Bob, Carol, Eve. Dave is User-only → excluded.
        withLowLevelTx { tx ->
            assertThat(q108.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Bob", "Carol", "Eve"))
        }
    }

    @Test
    fun `group 14 - Q109 F3 users(condition) intersect double-labeled Employee`() {

        // Q109: users(active) ∩ Labeled(Labeled(Where(All), "Employee"), "User")
        // Active Users who are also Employees.
        // After O19: extractCondition(right) = HasLabel("Employee"),
        //   combineBlocks(PropEqual("active",true), HasLabel("Employee")) = And(has("active",true), hasLabel("Employee"))
        //   → Labeled(Where(And(...)), "User")
        val employeesAsUsers = Labeled(Labeled(Where.of(All), "Employee"), "User")
        val q109 = users(PropEqual("active", true)).intersect(employeesAsUsers)
        println("[Q109 F3 users(active) intersect double-labeled Employee] query  : $q109")
        println("[Q109 F3 users(active) intersect double-labeled Employee] gremlin: ${q109.toGremlin()}")
        // And(PropEqual, HasLabel) simplifies to a chain since both blocks are chainable —
        // the traversal is has("active",true).hasLabel("Employee") rather than and(...).
        assertThat(q109.toGremlin()).isEqualTo(
            """g.V().has("active",true).hasLabel("Employee").hasLabel("User")"""
        )

        // ---- Result assertions ----
        // Active Employees: Alice (true), Bob (true), Eve (true). Carol is active=false → excluded.
        withLowLevelTx { tx ->
            assertThat(q109.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Bob", "Eve"))
        }
    }

    @Test
    fun `group 14 - Q110 F2 FollowLink(ByIds, OUT) intersect double-labeled Employee`() {

        // Q110: FollowLink(ByIds([p1, p2]), OUT, "lead") ∩ Labeled(Labeled(Where(All), "Employee"), "User")
        // Project leads (via bare FollowLink from ByIds) that are also Employees.
        // F2 shape: bare FollowLink on left, double-labeled Condition on right.
        //
        // O7 is intentionally suppressed for the O19 extraLabel case (double-label guard):
        // the optimised form would produce Labeled(Labeled(FollowLink, T1), T2) whose traversal
        // contains two consecutive hasLabel steps. TinkerPop's InlineFilterStrategy merges them
        // into one HasStep; YTDBHasLabelStep evaluates with anyMatch (OR), allowing sibling types
        // under T2 to pass incorrectly. Falls to Aggregate instead.
        val employeesAsUsers = Labeled(Labeled(Where.of(All), "Employee"), "User")
        val q110shape = FollowLink(ByIds(listOf(projectRid, projectRid2)), LinkDirection.OUT, "lead")
            .intersect(employeesAsUsers)
        println("[Q110 F2 FollowLink(ByIds) intersect double-labeled Employee] query  : $q110shape")
        println("[Q110 F2 FollowLink(ByIds) intersect double-labeled Employee] gremlin: ${q110shape.toGremlin()}")
        assertThat(q110shape).isInstanceOf(Aggregate::class.java)

        // ---- Result assertions (real RIDs) ----
        // ENG lead = Alice (Employee), OPS lead = Carol (Employee). INFRA lead = Bob (Employee).
        // Using ENG and OPS project RIDs → leads are Alice and Carol.
        withLowLevelTx { tx ->
            val q110real = FollowLink(
                ByIds(listOf(rid(dataset.projects["ENG"]!!), rid(dataset.projects["OPS"]!!))),
                LinkDirection.OUT, "lead"
            ).intersect(employeesAsUsers)
            assertThat(q110real.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Carol"))
        }
    }

    // =========================================================================
    // Group 15 — F5: ByIds ∩ UnionAll(FollowLink, FollowLink) — O20
    // =========================================================================

    @Test
    fun `group 15 - Q111 F5 ByIds intersect UnionAll(FollowLink, FollowLink)`() {

        // Q111: ByIds(projects) ∩ UnionAll(FollowLink(ByIds(s1), OUT, "project"), FollowLink(ByIds(s2), OUT, "project"))
        // "Which of these projects is reachable from sprint S1 or sprint S2 via their project link?"
        //
        // F5 shape: left = ByIds, right = bare UnionAll(FL, FL) — no Dedup/Slice wrapper.
        // extractCondition(UnionAll) returns null → Aggregate without O20.
        // O20: A ∩ (B ∪ C) = Dedup((A ∩ B) ∪ (A ∩ C)) — distribute IdWithin into each FL branch.
        val q111 = ByIds(listOf(projectRid, projectRid2)).intersect(
            UnionAll(listOf(
                FollowLink(ByIds(listOf(sprintRid)),  LinkDirection.OUT, "project"),
                FollowLink(ByIds(listOf(sprintRid2)), LinkDirection.OUT, "project")
            ))
        )
        println("[Q111 F5 ByIds intersect UnionAll(FL,FL)] query  : $q111")
        println("[Q111 F5 ByIds intersect UnionAll(FL,FL)] gremlin: ${q111.toGremlin()}")
        // O20a: both branches are FollowLink(ByIds, OUT, "project") — same direction+link.
        // Merge inner sources: ByIds([#50:1]).union(ByIds([#50:2])) = ByIds([#50:1, #50:2]).
        // Delegate to combineEfficient(FollowLink(ByIds([#50:1,#50:2]), OUT, "project"), Intersect)
        // → O7 symmetric: AndThen(FollowLink(ByIds([#50:1,#50:2]), OUT, "project"), IdWithin([#20:1,#20:2]))
        // Dedup added because multiple source vertices can reach the same target.
        // Single traversal — no union():
        assertThat(q111.toGremlin()).isEqualTo(
            """g.V(#50:1,#50:2).out("project_link").hasId(P.within([#20:1, #20:2])).dedup()"""
        )

        // ---- Result assertions ----
        // Sprint S1 → ENG, S2 → ENG. Both sprints point to the same project.
        // ByIds = {ENG_rid, OPS_rid}. UnionAll gives {ENG} (from both branches).
        // Intersection: {ENG, OPS} ∩ {ENG} = {ENG}.
        withLowLevelTx { tx ->
            val engRid  = rid(dataset.projects["ENG"]!!)
            val opsRid  = rid(dataset.projects["OPS"]!!)
            val s1Rid   = rid(dataset.sprints["S1"]!!)
            val s2Rid   = rid(dataset.sprints["S2"]!!)
            val q111real = ByIds(listOf(engRid, opsRid)).intersect(
                UnionAll(listOf(
                    FollowLink(ByIds(listOf(s1Rid)), LinkDirection.OUT, "project"),
                    FollowLink(ByIds(listOf(s2Rid)), LinkDirection.OUT, "project")
                ))
            )
            assertThat(q111real.resultKeys(tx)).containsExactlyElementsIn(listOf("ENG"))
        }
    }

    // =========================================================================
    // Group 16 — O_B: Aggregate(left, right) ∩ Labeled(Where(All), T) — redundant-All strip
    //
    // When the left side of an Aggregate is labeled T and the second operand of an
    // outer intersection is Labeled(Where(All), T), the outer intersection is a no-op:
    // Aggregate already produces only T-labeled vertices.
    //
    // This pattern arises when query DSL intersects a cross-label Aggregate (which cannot
    // be combined efficiently) with an additional "all T" constraint.  The Aggregate was
    // forced because the two inner queries have different labels (e.g. "User" vs "Employee").
    //
    // Real-world trigger example:
    //   step1 = events(byId) ∩ eventsByType   → Aggregate (different labels, no efficient rule)
    //   step2 = step1 ∩ allBaseEvents          → redundant outer Aggregate if step1.left is labeled "BaseEvent"
    // =========================================================================

    @Test
    fun `group 16 - Q112 OB single-level redundant-All strip`() {

        // Intersect two queries with different labels — forces Aggregate (O_B pre-condition).
        //   activeUsers   = Labeled(Where(PropEqual("active",true)), "User")
        //   engEmployees  = Labeled(Where(PropEqual("department","Engineering")), "Employee")
        //   q_base        = Aggregate(activeUsers, engEmployees)   ← different labels, no efficient rule
        val activeUsers  = users(PropEqual("active", true))
        val engEmployees = employees(PropEqual("department", "Engineering"))
        val qBase        = activeUsers.intersect(engEmployees)

        // Intersecting qBase with Labeled(Where(All), "User") is a no-op:
        //   • qBase.left = activeUsers is labeled "User"
        //   • qBase already produces only User vertices
        //   • ∩ "all Users" cannot add or remove results
        //
        // After O_B: q112 should produce the same query as qBase.
        // Before O_B: q112 = Aggregate(qBase, users()) — doubly-nested, different gremlin.
        val q112 = qBase.intersect(users())
        println("[Q112 OB single-level] qBase  : $qBase")
        println("[Q112 OB single-level] q112   : $q112")
        println("[Q112 OB single-level] gremlin: ${q112.toGremlin()}")
        assertThat(q112.toGremlin()).isEqualTo(qBase.toGremlin())

        // ---- Result assertions ----
        // Active users who are also Engineering employees: Alice (active, Engineering),
        // Bob (active, Engineering), Eve (active, Engineering).
        // Carol: not active. Dave: active but not an employee.
        withLowLevelTx { tx ->
            assertThat(q112.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Bob", "Eve"))
        }
    }

    @Test
    fun `group 16 - Q113 OB multi-level redundant-All strip`() {

        // Same base Aggregate as Q112; apply the redundant-All strip three times.
        //
        // Without O_B each .intersect(users()) adds another Aggregate wrapper:
        //   q113 = Aggregate(Aggregate(Aggregate(qBase, users()), users()), users())
        //
        // With O_B every .intersect(users()) is a no-op → q113 = qBase.
        val activeUsers  = users(PropEqual("active", true))
        val engEmployees = employees(PropEqual("department", "Engineering"))
        val qBase        = activeUsers.intersect(engEmployees)

        val q113 = qBase.intersect(users()).intersect(users()).intersect(users())
        println("[Q113 OB multi-level] qBase  : $qBase")
        println("[Q113 OB multi-level] q113   : $q113")
        println("[Q113 OB multi-level] gremlin: ${q113.toGremlin()}")
        assertThat(q113.toGremlin()).isEqualTo(qBase.toGremlin())

        // ---- Result assertions ----
        withLowLevelTx { tx ->
            assertThat(q113.resultNames(tx)).containsExactlyElementsIn(listOf("Alice", "Bob", "Eve"))
        }
    }

    @Test
    fun `group 16 - Q114 OB non-firing case - label mismatch`() {

        // Sanity check: O_B must NOT fire when the outer label does not match qBase.left's label.
        //   qBase.left = activeUsers labeled "User"
        //   outer condition = projects() = Labeled(Where(All), "Project")
        //   "User" != "Project" → O_B does not fire → q114 is a distinct Aggregate wrapping qBase
        val activeUsers  = users(PropEqual("active", true))
        val engEmployees = employees(PropEqual("department", "Engineering"))
        val qBase        = activeUsers.intersect(engEmployees)

        val q114 = qBase.intersect(projects())
        println("[Q114 OB non-firing] qBase  : $qBase")
        println("[Q114 OB non-firing] q114   : $q114")
        println("[Q114 OB non-firing] gremlin: ${q114.toGremlin()}")
        // q114 must NOT collapse to qBase — it wraps qBase in another Aggregate.
        assertThat(q114.toGremlin()).isNotEqualTo(qBase.toGremlin())
    }
}
