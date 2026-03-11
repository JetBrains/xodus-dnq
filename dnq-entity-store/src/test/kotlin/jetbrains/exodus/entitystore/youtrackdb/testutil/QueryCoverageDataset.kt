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
package jetbrains.exodus.entitystore.youtrackdb.testutil

import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import jetbrains.exodus.entitystore.youtrackdb.getOrCreateVertexClass

/**
 * Canonical dataset for GremlinQueryCoverageTest result assertions.
 *
 * Data model mirrors the one described in GremlinQueryCoverageTest:
 *
 *   User          (name, email, active)
 *   └── Employee  (+ department, salary)
 *       └── Manager (+ reportsCount)
 *
 *   Project  (name, key, isArchived)
 *   Issue    (key, summary, priority, status, estimate)
 *   Sprint   (key, name, state, velocity)
 *   Tag      (name, color)
 *
 * Links (edge class = "${linkName}_link"):
 *   Issue   --project-->  Project
 *   Issue   --assignee--> Employee
 *   Issue   --sprint-->   Sprint
 *   Issue   --tags-->     Tag
 *   Issue   --parent-->   Issue    (self-referential; subtasks)
 *   Project --lead-->     Employee
 *   Sprint  --project-->  Project
 *
 * Each entity carries a stable `key` property used as surrogate ID in result assertions
 * (RIDs are not stable across test runs). For User/Employee/Manager the `name` field is
 * unique and serves as the surrogate.
 *
 * **Usage:** create [InMemoryYouTrackDB] with `initializeIssueSchema = false` to avoid
 * schema conflicts with the coverage model's link names, then pass it to this class.
 *
 * ```kotlin
 * @Rule @JvmField val db = InMemoryYouTrackDB(initializeIssueSchema = false)
 * private val dataset by lazy { QueryCoverageDataset(db) }
 * ```
 */
class QueryCoverageDataset(val db: InMemoryYouTrackDB) {

    // -------------------------------------------------------------------------
    // Entity type constants
    // -------------------------------------------------------------------------

    object Types {
        const val USER     = "User"
        const val EMPLOYEE = "Employee"
        const val MANAGER  = "Manager"
        const val PROJECT  = "Project"
        const val ISSUE    = "Issue"
        const val SPRINT   = "Sprint"
        const val TAG      = "Tag"
    }

    object Links {
        const val PROJECT  = "project"
        const val ASSIGNEE = "assignee"
        const val SPRINT   = "sprint"
        const val TAGS     = "tags"
        const val PARENT   = "parent"
        const val LEAD     = "lead"
    }

    // -------------------------------------------------------------------------
    // Entity maps — keyed by stable surrogate (key property or name)
    // -------------------------------------------------------------------------

    /** Issues keyed by their `key` property, e.g. "ENG-1". */
    val issues: Map<String, YTDBVertexEntity>
    /** Projects keyed by their `key` property, e.g. "ENG". */
    val projects: Map<String, YTDBVertexEntity>
    /** Users/Employees/Managers keyed by `name`. */
    val users: Map<String, YTDBVertexEntity>
    /** Sprints keyed by their `key` property, e.g. "S1". */
    val sprints: Map<String, YTDBVertexEntity>
    /** Tags keyed by `name`. */
    val tags: Map<String, YTDBVertexEntity>

    init {
        setupSchema()
        projects = createProjects()
        users    = createUsers()
        sprints  = createSprints()
        tags     = createTags()
        issues   = createIssues()
        createLinks()
    }

    // -------------------------------------------------------------------------
    // Schema setup
    // -------------------------------------------------------------------------

    private fun setupSchema() {
        db.withSession { session ->
            // Vertex class hierarchy
            val userClass     = session.getOrCreateVertexClass(Types.USER)
            val employeeClass = session.getOrCreateVertexClass(Types.EMPLOYEE)
            val managerClass  = session.getOrCreateVertexClass(Types.MANAGER)
            employeeClass.addSuperClass(userClass)
            managerClass.addSuperClass(employeeClass)

            session.getOrCreateVertexClass(Types.PROJECT)
            session.getOrCreateVertexClass(Types.ISSUE)
            session.getOrCreateVertexClass(Types.SPRINT)
            session.getOrCreateVertexClass(Types.TAG)

            // Edge classes for all links.
            // The reverse-link name (4th arg) creates a back-reference edge class;
            // it is never traversed in the coverage queries but is required by addAssociation.
            session.addAssociation(Types.ISSUE,   Types.PROJECT,  Links.PROJECT,  "projectBack")
            session.addAssociation(Types.ISSUE,   Types.EMPLOYEE, Links.ASSIGNEE, "assigneeBack")
            session.addAssociation(Types.ISSUE,   Types.SPRINT,   Links.SPRINT,   "sprintBack")
            session.addAssociation(Types.ISSUE,   Types.TAG,      Links.TAGS,     "tagsBack")
            session.addAssociation(Types.ISSUE,   Types.ISSUE,    Links.PARENT,   "subtask")
            session.addAssociation(Types.PROJECT, Types.EMPLOYEE, Links.LEAD,     "leadBack")
            // Sprint→Project reuses the "project_link" edge class (already created above).
            session.addAssociation(Types.SPRINT,  Types.PROJECT,  Links.PROJECT,  "sprintProjectBack")
        }
    }

    // -------------------------------------------------------------------------
    // Entity creation helpers
    // -------------------------------------------------------------------------

    private fun createProjects(): Map<String, YTDBVertexEntity> =
        db.withStoreTx { tx ->
            mapOf(
                "ENG"   to tx.newProject("ENG",   "Engineering",    isArchived = false),
                "OPS"   to tx.newProject("OPS",   "Operations",     isArchived = false),
                "INFRA" to tx.newProject("INFRA", "Infrastructure", isArchived = false),
                "ARC"   to tx.newProject("ARC",   "Archive",        isArchived = true),
            )
        }

    private fun createUsers(): Map<String, YTDBVertexEntity> =
        db.withStoreTx { tx ->
            mapOf(
                "Alice" to tx.newEmployee("Alice", "alice@example.com", active = true,  department = "Engineering", salary = 100000L),
                "Bob"   to tx.newEmployee("Bob",   "bob@example.com",   active = true,  department = "Engineering", salary = 90000L),
                "Carol" to tx.newEmployee("Carol", "carol@example.com", active = false, department = "Operations",  salary = 85000L),
                "Dave"  to tx.newUser("Dave",      "dave@example.com",  active = true),
                "Eve"   to tx.newManager("Eve",    "eve@example.com",   active = true,  department = "Engineering", salary = 120000L, reportsCount = 5),
            )
        }

    private fun createSprints(): Map<String, YTDBVertexEntity> =
        db.withStoreTx { tx ->
            mapOf(
                "S1" to tx.newSprint("S1", "Sprint 1", state = "active", velocity = 40),
                "S2" to tx.newSprint("S2", "Sprint 2", state = "closed", velocity = 35),
                "S3" to tx.newSprint("S3", "Sprint 3", state = "active", velocity = 50),
            )
        }

    private fun createTags(): Map<String, YTDBVertexEntity> =
        db.withStoreTx { tx ->
            mapOf(
                "bug"         to tx.newTag("bug",         "red"),
                "feature"     to tx.newTag("feature",     "blue"),
                "performance" to tx.newTag("performance", "orange"),
            )
        }

    private fun createIssues(): Map<String, YTDBVertexEntity> =
        db.withStoreTx { tx ->
            mapOf(
                // ENG issues
                "ENG-1"  to tx.newIssue("ENG-1",  "Bug: login page crash",                 priority = "critical", status = "open",        estimate = 5),
                "ENG-2"  to tx.newIssue("ENG-2",  "Bug: dashboard crash",                  priority = "high",     status = "open",        estimate = 3),
                "ENG-3"  to tx.newIssue("ENG-3",  "Feature: user login flow",              priority = "medium",   status = "in-progress", estimate = 8),
                "ENG-4"  to tx.newIssue("ENG-4",  "Performance issue on login",            priority = "high",     status = "resolved",    estimate = 2),
                "ENG-5"  to tx.newIssue("ENG-5",  "Add OAuth login support",               priority = "low",      status = "open",        estimate = 13),
                "ENG-6"  to tx.newIssue("ENG-6",  "Fix null pointer crash",                priority = "critical", status = "open",        estimate = 1),
                "ENG-7"  to tx.newIssue("ENG-7",  "Memory leak on startup crash",          priority = "high",     status = "in-progress", estimate = 5),
                "ENG-8"  to tx.newIssue("ENG-8",  "Improve search performance",            priority = "medium",   status = "open",        estimate = 8),
                "ENG-9"  to tx.newIssue("ENG-9",  "Update dependencies",                   priority = "low",      status = "resolved",    estimate = 3),
                "ENG-10" to tx.newIssue("ENG-10", "Bug: export fails for large datasets",  priority = "high",     status = "open",        estimate = 8),
                "ENG-11" to tx.newIssue("ENG-11", "Feature: dark mode",                    priority = "low",      status = "open",        estimate = 13),
                "ENG-12" to tx.newIssue("ENG-12", "Subtask: implement login UI",           priority = "medium",   status = "in-progress", estimate = 3),
                "ENG-13" to tx.newIssue("ENG-13", "Subtask: add login validation",         priority = "medium",   status = "open",        estimate = 2),
                "ENG-14" to tx.newIssue("ENG-14", "Subtask: OAuth callback handling",      priority = "medium",   status = "open",        estimate = 3),
                // OPS issues
                "OPS-1"  to tx.newIssue("OPS-1",  "Fix authentication bypass",             priority = "critical", status = "in-progress", estimate = 2),
                "OPS-2"  to tx.newIssue("OPS-2",  "Add monitoring dashboard",              priority = "high",     status = "open",        estimate = 5),
                "OPS-3"  to tx.newIssue("OPS-3",  "Database migration script",             priority = "medium",   status = "resolved",    estimate = 8),
                "OPS-4"  to tx.newIssue("OPS-4",  "Bug: report generation crash",          priority = "critical", status = "open",        estimate = 3),
                "OPS-5"  to tx.newIssue("OPS-5",  "Add rate limiting",                     priority = "medium",   status = "resolved",    estimate = 5),
                // INFRA issues
                "INFRA-1" to tx.newIssue("INFRA-1", "Setup CI pipeline",                   priority = "medium",   status = "closed",      estimate = 5),
                "INFRA-2" to tx.newIssue("INFRA-2", "Configure load balancer",             priority = "high",     status = "resolved",    estimate = 8),
                "INFRA-3" to tx.newIssue("INFRA-3", "Deploy to staging crash",             priority = "high",     status = "open",        estimate = 2),
                "INFRA-4" to tx.newIssue("INFRA-4", "Write API documentation",             priority = "low",      status = "open",        estimate = 5),
                // ARC issues
                "ARC-1"  to tx.newIssue("ARC-1",  "Legacy cleanup",                        priority = "low",      status = "closed",      estimate = 3),
            )
        }

    private fun createLinks() {
        db.withStoreTx { tx ->
            val i = issues; val p = projects; val u = users; val s = sprints; val t = tags

            // Issue → Project
            fun link(issueKey: String, projectKey: String) = i[issueKey]!!.addLink(Links.PROJECT, p[projectKey]!!)
            listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9",
                   "ENG-10","ENG-11","ENG-12","ENG-13","ENG-14").forEach { link(it, "ENG") }
            listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5").forEach { link(it, "OPS") }
            listOf("INFRA-1","INFRA-2","INFRA-3","INFRA-4").forEach { link(it, "INFRA") }
            link("ARC-1", "ARC")

            // Issue → Assignee
            fun assign(issueKey: String, userName: String) = i[issueKey]!!.addLink(Links.ASSIGNEE, u[userName]!!)
            assign("ENG-1", "Alice"); assign("ENG-2", "Bob");   assign("ENG-3", "Alice")
            assign("ENG-4", "Bob");   assign("ENG-5", "Alice"); assign("ENG-7", "Eve")
            assign("ENG-8", "Bob");   assign("ENG-10", "Alice"); assign("ENG-12", "Alice")
            assign("OPS-1", "Carol"); assign("OPS-2", "Carol"); assign("OPS-3", "Carol")
            assign("OPS-5", "Carol"); assign("INFRA-1", "Bob"); assign("INFRA-2", "Bob")

            // Issue → Sprint
            fun sprint(issueKey: String, sprintKey: String) = i[issueKey]!!.addLink(Links.SPRINT, s[sprintKey]!!)
            listOf("ENG-1","ENG-2","ENG-3","ENG-6","ENG-10","ENG-12","ENG-13").forEach { sprint(it, "S1") }
            listOf("ENG-4","ENG-7").forEach { sprint(it, "S2") }
            listOf("OPS-2","OPS-4").forEach { sprint(it, "S3") }

            // Issue → Tags
            fun tag(issueKey: String, tagName: String) = i[issueKey]!!.addLink(Links.TAGS, t[tagName]!!)
            listOf("ENG-1","ENG-2","ENG-6","ENG-10","OPS-1","OPS-4","INFRA-3").forEach { tag(it, "bug") }
            listOf("ENG-3","ENG-11").forEach { tag(it, "feature") }
            listOf("ENG-4","ENG-8").forEach { tag(it, "performance") }

            // Issue → Parent (subtasks)
            i["ENG-12"]!!.addLink(Links.PARENT, i["ENG-3"]!!)
            i["ENG-13"]!!.addLink(Links.PARENT, i["ENG-3"]!!)
            i["ENG-14"]!!.addLink(Links.PARENT, i["ENG-5"]!!)

            // Project → Lead (Employee)
            p["ENG"]!!.addLink(Links.LEAD,   u["Alice"]!!)
            p["OPS"]!!.addLink(Links.LEAD,   u["Carol"]!!)
            p["INFRA"]!!.addLink(Links.LEAD, u["Bob"]!!)

            // Sprint → Project
            s["S1"]!!.addLink(Links.PROJECT, p["ENG"]!!)
            s["S2"]!!.addLink(Links.PROJECT, p["ENG"]!!)
            s["S3"]!!.addLink(Links.PROJECT, p["OPS"]!!)
        }
    }

    // -------------------------------------------------------------------------
    // Low-level entity constructors (transaction-scoped)
    // -------------------------------------------------------------------------

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newProject(
        key: String, name: String, isArchived: Boolean
    ): YTDBVertexEntity = (newEntity(Types.PROJECT) as YTDBVertexEntity).apply {
        setProperty("key", key)
        setProperty("name", name)
        setProperty("isArchived", isArchived)
    }

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newUser(
        name: String, email: String, active: Boolean
    ): YTDBVertexEntity = (newEntity(Types.USER) as YTDBVertexEntity).apply {
        setProperty("name", name)
        setProperty("email", email)
        setProperty("active", active)
    }

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newEmployee(
        name: String, email: String, active: Boolean, department: String, salary: Long
    ): YTDBVertexEntity = (newEntity(Types.EMPLOYEE) as YTDBVertexEntity).apply {
        setProperty("name", name)
        setProperty("email", email)
        setProperty("active", active)
        setProperty("department", department)
        setProperty("salary", salary)
    }

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newManager(
        name: String, email: String, active: Boolean, department: String, salary: Long, reportsCount: Int
    ): YTDBVertexEntity = (newEntity(Types.MANAGER) as YTDBVertexEntity).apply {
        setProperty("name", name)
        setProperty("email", email)
        setProperty("active", active)
        setProperty("department", department)
        setProperty("salary", salary)
        setProperty("reportsCount", reportsCount)
    }

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newSprint(
        key: String, name: String, state: String, velocity: Int
    ): YTDBVertexEntity = (newEntity(Types.SPRINT) as YTDBVertexEntity).apply {
        setProperty("key", key)
        setProperty("name", name)
        setProperty("state", state)
        setProperty("velocity", velocity)
    }

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newTag(
        name: String, color: String
    ): YTDBVertexEntity = (newEntity(Types.TAG) as YTDBVertexEntity).apply {
        setProperty("name", name)
        setProperty("color", color)
    }

    private fun jetbrains.exodus.entitystore.youtrackdb.YTDBStoreTransaction.newIssue(
        key: String, summary: String, priority: String, status: String, estimate: Int
    ): YTDBVertexEntity = (newEntity(Types.ISSUE) as YTDBVertexEntity).apply {
        setProperty("key", key)
        setProperty("summary", summary)
        setProperty("priority", priority)
        setProperty("status", status)
        setProperty("estimate", estimate)
    }
}
