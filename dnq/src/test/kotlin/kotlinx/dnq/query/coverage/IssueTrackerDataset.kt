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

import jetbrains.exodus.database.TransientEntityStore

/**
 * Canonical dataset for query coverage tests.
 *
 * Populates the DB via the DNQ DSL so that both DSL-level and GremlinQuery-level
 * test assertions can run against the same data.
 *
 * Usage (inside a [DBTest]-based test):
 * ```
 * private lateinit var dataset: IssueTrackerDataset
 *
 * @Before fun setupDataset() {
 *     dataset = IssueTrackerDataset(store)
 * }
 * ```
 *
 * Entities are keyed by their stable `key` property (issues, projects, sprints)
 * or `name` (users, tags) so result assertions don't depend on RIDs.
 */
class IssueTrackerDataset(store: TransientEntityStore) {

    /** Projects keyed by `key`, e.g. "ENG". */
    val projects: Map<String, Project>
    /** All users (including employees and managers) keyed by `name`. */
    val users: Map<String, XdUser>
    /** Sprints keyed by `key`, e.g. "S1". */
    val sprints: Map<String, Sprint>
    /** Tags keyed by `name`. */
    val tags: Map<String, Tag>
    /** Issues keyed by `key`, e.g. "ENG-1". */
    val issues: Map<String, Issue>

    init {
        projects = store.transactional { createProjects() }
        users    = store.transactional { createUsers() }
        sprints  = store.transactional { createSprints() }
        tags     = store.transactional { createTags() }
        issues   = store.transactional { createIssues() }
        store.transactional { createLinks(issues, projects, users, sprints, tags) }
    }

    // -------------------------------------------------------------------------
    // Entity creation
    // -------------------------------------------------------------------------

    private fun createProjects(): Map<String, Project> = mapOf(
        "ENG"   to Project.new { key = "ENG";   name = "Engineering";    isArchived = false },
        "OPS"   to Project.new { key = "OPS";   name = "Operations";     isArchived = false },
        "INFRA" to Project.new { key = "INFRA"; name = "Infrastructure"; isArchived = false },
        "ARC"   to Project.new { key = "ARC";   name = "Archive";        isArchived = true  },
    )

    private fun createUsers(): Map<String, XdUser> = mapOf(
        "Alice" to Employee.new { name = "Alice"; email = "alice@example.com"; active = true;  department = "Engineering"; salary = 100000L },
        "Bob"   to Employee.new { name = "Bob";   email = "bob@example.com";   active = true;  department = "Engineering"; salary = 90000L  },
        "Carol" to Employee.new { name = "Carol"; email = "carol@example.com"; active = false; department = "Operations";  salary = 85000L  },
        "Dave"  to XdUser.new   { name = "Dave";  email = "dave@example.com";  active = true   },
        "Eve"   to Manager.new  { name = "Eve";   email = "eve@example.com";   active = true;  department = "Engineering"; salary = 120000L; reportsCount = 5 },
    )

    private fun createSprints(): Map<String, Sprint> = mapOf(
        "S1" to Sprint.new { key = "S1"; name = "Sprint 1"; state = "active"; velocity = 40 },
        "S2" to Sprint.new { key = "S2"; name = "Sprint 2"; state = "closed"; velocity = 35 },
        "S3" to Sprint.new { key = "S3"; name = "Sprint 3"; state = "active"; velocity = 50 },
    )

    private fun createTags(): Map<String, Tag> = mapOf(
        "bug"         to Tag.new { name = "bug";         color = "red"    },
        "feature"     to Tag.new { name = "feature";     color = "blue"   },
        "performance" to Tag.new { name = "performance"; color = "orange" },
    )

    private fun createIssues(): Map<String, Issue> = mapOf(
        // ENG issues
        "ENG-1"  to Issue.new { key = "ENG-1";  summary = "Bug: login page crash";                priority = "critical"; status = "open";        estimate = 5  },
        "ENG-2"  to Issue.new { key = "ENG-2";  summary = "Bug: dashboard crash";                 priority = "high";     status = "open";        estimate = 3  },
        "ENG-3"  to Issue.new { key = "ENG-3";  summary = "Feature: user login flow";             priority = "medium";   status = "in-progress"; estimate = 8  },
        "ENG-4"  to Issue.new { key = "ENG-4";  summary = "Performance issue on login";           priority = "high";     status = "resolved";    estimate = 2  },
        "ENG-5"  to Issue.new { key = "ENG-5";  summary = "Add OAuth login support";              priority = "low";      status = "open";        estimate = 13 },
        "ENG-6"  to Issue.new { key = "ENG-6";  summary = "Fix null pointer crash";               priority = "critical"; status = "open";        estimate = 1  },
        "ENG-7"  to Issue.new { key = "ENG-7";  summary = "Memory leak on startup crash";         priority = "high";     status = "in-progress"; estimate = 5  },
        "ENG-8"  to Issue.new { key = "ENG-8";  summary = "Improve search performance";           priority = "medium";   status = "open";        estimate = 8  },
        "ENG-9"  to Issue.new { key = "ENG-9";  summary = "Update dependencies";                  priority = "low";      status = "resolved";    estimate = 3  },
        "ENG-10" to Issue.new { key = "ENG-10"; summary = "Bug: export fails for large datasets"; priority = "high";     status = "open";        estimate = 8  },
        "ENG-11" to Issue.new { key = "ENG-11"; summary = "Feature: dark mode";                   priority = "low";      status = "open";        estimate = 13 },
        "ENG-12" to Issue.new { key = "ENG-12"; summary = "Subtask: implement login UI";          priority = "medium";   status = "in-progress"; estimate = 3  },
        "ENG-13" to Issue.new { key = "ENG-13"; summary = "Subtask: add login validation";        priority = "medium";   status = "open";        estimate = 2  },
        "ENG-14" to Issue.new { key = "ENG-14"; summary = "Subtask: OAuth callback handling";     priority = "medium";   status = "open";        estimate = 3  },
        // OPS issues
        "OPS-1"  to Issue.new { key = "OPS-1";  summary = "Fix authentication bypass";            priority = "critical"; status = "in-progress"; estimate = 2  },
        "OPS-2"  to Issue.new { key = "OPS-2";  summary = "Add monitoring dashboard";             priority = "high";     status = "open";        estimate = 5  },
        "OPS-3"  to Issue.new { key = "OPS-3";  summary = "Database migration script";            priority = "medium";   status = "resolved";    estimate = 8  },
        "OPS-4"  to Issue.new { key = "OPS-4";  summary = "Bug: report generation crash";         priority = "critical"; status = "open";        estimate = 3  },
        "OPS-5"  to Issue.new { key = "OPS-5";  summary = "Add rate limiting";                    priority = "medium";   status = "resolved";    estimate = 5  },
        // INFRA issues
        "INFRA-1" to Issue.new { key = "INFRA-1"; summary = "Setup CI pipeline";                  priority = "medium";   status = "closed";      estimate = 5  },
        "INFRA-2" to Issue.new { key = "INFRA-2"; summary = "Configure load balancer";            priority = "high";     status = "resolved";    estimate = 8  },
        "INFRA-3" to Issue.new { key = "INFRA-3"; summary = "Deploy to staging crash";            priority = "high";     status = "open";        estimate = 2  },
        "INFRA-4" to Issue.new { key = "INFRA-4"; summary = "Write API documentation";            priority = "low";      status = "open";        estimate = 5  },
        // ARC issues
        "ARC-1"  to Issue.new { key = "ARC-1";  summary = "Legacy cleanup";                       priority = "low";      status = "closed";      estimate = 3  },
    )

    private fun createLinks(
        i: Map<String, Issue>,
        p: Map<String, Project>,
        u: Map<String, XdUser>,
        s: Map<String, Sprint>,
        t: Map<String, Tag>,
    ) {
        // Issue → Project
        fun linkProject(issueKey: String, projectKey: String) { i[issueKey]!!.project = p[projectKey] }
        listOf("ENG-1","ENG-2","ENG-3","ENG-4","ENG-5","ENG-6","ENG-7","ENG-8","ENG-9",
               "ENG-10","ENG-11","ENG-12","ENG-13","ENG-14").forEach { linkProject(it, "ENG") }
        listOf("OPS-1","OPS-2","OPS-3","OPS-4","OPS-5").forEach { linkProject(it, "OPS") }
        listOf("INFRA-1","INFRA-2","INFRA-3","INFRA-4").forEach { linkProject(it, "INFRA") }
        linkProject("ARC-1", "ARC")

        // Issue → Assignee (only employees can be assignees)
        fun assign(issueKey: String, userName: String) { i[issueKey]!!.assignee = u[userName] as Employee }
        assign("ENG-1", "Alice"); assign("ENG-2", "Bob");    assign("ENG-3", "Alice")
        assign("ENG-4", "Bob");   assign("ENG-5", "Alice");  assign("ENG-7", "Eve")
        assign("ENG-8", "Bob");   assign("ENG-10", "Alice"); assign("ENG-12", "Alice")
        assign("OPS-1", "Carol"); assign("OPS-2", "Carol");  assign("OPS-3", "Carol")
        assign("OPS-5", "Carol"); assign("INFRA-1", "Bob");  assign("INFRA-2", "Bob")

        // Issue → Sprint
        fun linkSprint(issueKey: String, sprintKey: String) { i[issueKey]!!.sprint = s[sprintKey] }
        listOf("ENG-1","ENG-2","ENG-3","ENG-6","ENG-10","ENG-12","ENG-13").forEach { linkSprint(it, "S1") }
        listOf("ENG-4","ENG-7").forEach { linkSprint(it, "S2") }
        listOf("OPS-2","OPS-4").forEach { linkSprint(it, "S3") }

        // Issue → Tags
        fun tag(issueKey: String, tagName: String) { i[issueKey]!!.tags.add(t[tagName]!!) }
        listOf("ENG-1","ENG-2","ENG-6","ENG-10","OPS-1","OPS-4","INFRA-3").forEach { tag(it, "bug") }
        listOf("ENG-3","ENG-11").forEach { tag(it, "feature") }
        listOf("ENG-4","ENG-8").forEach { tag(it, "performance") }

        // Issue → Parent (subtasks)
        i["ENG-12"]!!.parent = i["ENG-3"]
        i["ENG-13"]!!.parent = i["ENG-3"]
        i["ENG-14"]!!.parent = i["ENG-5"]

        // Project → Lead
        p["ENG"]!!.lead   = u["Alice"] as Employee
        p["OPS"]!!.lead   = u["Carol"] as Employee
        p["INFRA"]!!.lead = u["Bob"]   as Employee

        // Sprint → Project
        s["S1"]!!.project = p["ENG"]
        s["S2"]!!.project = p["ENG"]
        s["S3"]!!.project = p["OPS"]
    }
}
