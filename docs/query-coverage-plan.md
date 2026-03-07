# GremlinQuery Coverage Plan

## Data Model

Five entity classes with a simple two-level hierarchy:

```
User          (name: String, email: String, active: Boolean)
└── Employee  (+ department: String, salary: Long)
    └── Manager (+ reportsCount: Int)

Project  (name: String, key: String, isArchived: Boolean)
Issue    (summary: String, priority: String, status: String, estimate: Int)
Tag      (name: String, color: String)
Sprint   (name: String, state: String, velocity: Int)
```

**Links:**
- `Issue --project-->  Project`
- `Issue --assignee--> User`
- `Issue --reporter--> Employee`
- `Issue --tags-->     Tag`   (multi-value)
- `Issue --sprint-->   Sprint`
- `Issue --parent-->   Issue` (self-referential; subtasks)
- `Project --lead-->   Employee`
- `Sprint --project--> Project`

Priority values: `critical`, `high`, `medium`, `low`
Status values: `open`, `in-progress`, `resolved`, `closed`
Sprint state values: `active`, `closed`

---

## Test Class

File: `dnq-entity-store/src/test/kotlin/jetbrains/exodus/entitystore/youtrackdb/query/GremlinQueryCoverageTest.kt`

Each test method covers one query group. Within each method the queries are built, then printed and asserted:

```kotlin
println("[$name] query  : $query")
println("[$name] gremlin: ${query.toGremlin()}")
```

(`toGremlin()` uses `EmptyGraph.instance().traversal()` + `GroovyTranslator.of("g")`, same as `GremlinQueryTest`.)

No real database is used. Tests assert the Gremlin string produced by each query.

---

## Query Groups and Scenarios

### Group 1 — Simple property queries (~10)
- [ ] Q01 Issues with `priority = "critical"`
- [ ] Q02 Issues with `status = "open"`
- [ ] Q03 Issues where `estimate` is in range [1, 8]
- [ ] Q04 Projects that are archived (`isArchived = true`)
- [ ] Q05 Employees in the `"Engineering"` department
- [ ] Q06 Issues with priority within `["critical", "high"]`
- [ ] Q07 Issues where `summary` contains `"login"` (substring, case-insensitive)
- [ ] Q08 Issues where `summary` starts with `"Bug:"` (prefix, case-insensitive)
- [ ] Q09 Issues where `summary` ends with `"crash"` (suffix, case-insensitive)
- [ ] Q10 Active users (`active = true`) — using `All` base, then label filter for `User`

### Group 2 — Link-based queries (~8)
- [ ] Q11 Issues that have an assignee (`HasLink`)
- [ ] Q12 Issues with no assignee (`HasNoLink`)
- [ ] Q13 Issues with no sprint (`HasNoLink`)
- [ ] Q14 Issues that are subtasks (have a parent link)
- [ ] Q15 Issues that are top-level (no parent link)
- [ ] Q16 Issues that have at least one tag (`HasLink`)
- [ ] Q17 Issues assigned to a specific user (`HasLinkTo` by RID)
- [ ] Q18 Issues in a specific project (`HasLinkTo` by RID)

### Group 3 — ByIds queries (~4)
- [ ] Q19 Fetch two specific issues by RID (`ByIds`)
- [ ] Q20 `ByIds` union `ByIds` — merge two RID sets
- [ ] Q21 `ByIds` intersect `ByIds` — common RIDs only
- [ ] Q22 `ByIds` difference `ByIds` — first set minus second

### Group 4 — Sort and slice queries (~7)
- [ ] Q23 All issues sorted by priority ascending
- [ ] Q24 All issues sorted by estimate descending
- [ ] Q25 Issues sorted by assignee name (sort by linked property)
- [ ] Q26 All issues, skip 10 (pagination offset)
- [ ] Q27 All issues, limit 20 (page size)
- [ ] Q28 All issues, skip 10, limit 5 (pagination window — slice composition)
- [ ] Q29 Last 5 issues (tail)

### Group 5 — Union queries (~10)
- [ ] Q30 Critical OR high priority issues (union of two conditions → `Or`)
- [ ] Q31 Open OR in-progress issues
- [ ] Q32 Issues with no assignee OR with critical priority (null-link OR condition)
- [ ] Q33 Issues in project A OR project B (two `HasLinkTo` predicates)
- [ ] Q34 Issues assigned to user A OR user B (`ByIds` union)
- [ ] Q35 Open OR in-progress OR resolved — three-way union (tests O1 UnionAll flattening)
- [ ] Q36 Issues in sprint A OR issues with no sprint
- [ ] Q37 Subtasks OR issues matching `"Bug:"` prefix
- [ ] Q38 `SortBy(open issues).union(SortBy(critical issues))` — both sorts stripped (O3)
- [ ] Q39 `SortBy(open).union(unresolved)` — left sort stripped (O3)

### Group 6 — Intersect queries (~10)
- [ ] Q40 Critical AND open issues (conditions → `And`)
- [ ] Q41 Open issues that are also in a sprint (condition AND `HasLink`)
- [ ] Q42 Issues with assignee AND with at least one tag
- [ ] Q43 High-estimate AND high-priority issues
- [ ] Q44 `SortBy(all issues, priority).intersect(open issues)` — sort preserved (O3)
- [ ] Q45 `SortBy(all issues, priority).intersect(SortBy(high priority, estimate))` — right sort stripped, left preserved
- [ ] Q46 `ByIds` intersect condition — specific issues that are also open
- [ ] Q47 Triple intersect: critical AND open AND in-sprint
- [ ] Q48 Open issues intersected with issues in Engineering project
- [ ] Q49 Unresolved issues that are also unassigned (two `HasNoLink` conditions)

### Group 7 — Difference queries (~8)
- [ ] Q50 Open issues NOT assigned to a specific user
- [ ] Q51 Critical issues NOT in any sprint
- [ ] Q52 Issues in project A NOT marked as subtasks
- [ ] Q53 High-priority issues NOT resolved
- [ ] Q54 All issues NOT tagged with "bug" tag (specific RID)
- [ ] Q55 `SortBy(open issues, priority).difference(assigned issues)` — sort preserved (O3)
- [ ] Q56 `ByIds` difference condition — specific issues that are not open
- [ ] Q57 Open issues NOT in a specific project

### Group 8 — Complex combined queries (~10)
- [ ] Q58 (Critical OR high) AND open — union result intersected with condition
- [ ] Q59 (Critical AND open) OR (high AND in-progress) — two intersects unioned
- [ ] Q60 (Critical OR high) AND NOT resolved AND in-sprint — union, then intersect, then difference
- [ ] Q61 Open issues in project A UNION open issues in project B, minus assigned issues
- [ ] Q62 Cascaded 3-way union with fallback: `skip(1).union(skip(2)).union(skip(3))` (verifies O1 flattening)
- [ ] Q63 (Sorted open) intersect (Sorted critical) — both sorted same key, sort preserved
- [ ] Q64 (Open AND critical) UNION (high AND in-progress) — two And-conditions unioned → Or
- [ ] Q65 Open in sprint A minus open in sprint B (two intersects, then difference)
- [ ] Q66 Issues assigned to an employee AND in a project whose lead is the same employee (illustrates NestedCondition / chained links)
- [ ] Q67 All issues sorted by priority UNION all issues sorted by estimate — both sorts dropped, result unsorted (O3)

---

## Follow-up: Real Database Integration
- [ ] Add a second test class `GremlinQueryCoverageIntegrationTest` that uses a real YouTrackDB instance, populates the data model with sample data, and verifies that each query above returns the expected entity count or specific entities.

---

## Part 2 — Optimization Analysis (to be done after Part 1)
- [ ] Run through all 67 queries and identify which ones currently fall back to `UnionAll`/`Aggregate` where `combineEfficient` could potentially do better
- [ ] Identify any patterns not yet handled by O1–O6
- [ ] Propose new optimization candidates (O7+)
