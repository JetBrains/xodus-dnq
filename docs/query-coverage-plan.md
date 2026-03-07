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

### Group 1 — Simple property queries (10) ✅
- [x] Q01 Issues with `priority = "critical"`
- [x] Q02 Issues with `status = "open"`
- [x] Q03 Issues where `estimate` is in range [1, 8]
- [x] Q04 Projects that are archived (`isArchived = true`)
- [x] Q05 Employees in the `"Engineering"` department
- [x] Q06 Issues with priority within `["critical", "high"]`
- [x] Q07 Issues where `summary` contains `"login"` (substring, case-insensitive)
- [x] Q08 Issues where `summary` starts with `"Bug:"` (prefix, case-insensitive)
- [x] Q09 Issues where `summary` ends with `"crash"` (suffix, case-insensitive)
- [x] Q10 Active users (`active = true`) — using `All` base, then label filter for `User`

### Group 2 — Link-based queries (8) ✅
- [x] Q11 Issues that have an assignee (`HasLink`)
- [x] Q12 Issues with no assignee (`HasNoLink`)
- [x] Q13 Issues with no sprint (`HasNoLink`)
- [x] Q14 Issues that are subtasks (have a parent link)
- [x] Q15 Issues that are top-level (no parent link)
- [x] Q16 Issues that have at least one tag (`HasLink`)
- [x] Q17 Issues assigned to a specific user (`HasLinkTo` by RID)
- [x] Q18 Issues in a specific project (`HasLinkTo` by RID)

### Group 3 — ByIds queries (4) ✅
- [x] Q19 Fetch two specific issues by RID (`ByIds`)
- [x] Q20 `ByIds` union `ByIds` — merge two RID sets
- [x] Q21 `ByIds` intersect `ByIds` — common RIDs only
- [x] Q22 `ByIds` difference `ByIds` — first set minus second

### Group 4 — Sort and slice queries (7) ✅
- [x] Q23 All issues sorted by priority ascending
- [x] Q24 All issues sorted by estimate descending
- [x] Q25 Issues sorted by assignee name (sort by linked property)
- [x] Q26 All issues, skip 10 (pagination offset)
- [x] Q27 All issues, limit 20 (page size)
- [x] Q28 All issues, skip 10, limit 5 (pagination window — slice composition)
- [x] Q29 Last 5 issues (tail)

### Group 5 — Union queries (10) ✅
- [x] Q30 Critical OR high priority issues (union of two conditions → `Or`)
- [x] Q31 Open OR in-progress issues
- [x] Q32 Issues with no assignee OR with critical priority (null-link OR condition)
- [x] Q33 Issues in project A OR project B (two `HasLinkTo` predicates)
- [x] Q34 Issues assigned to user A OR user B (`ByIds` union)
- [x] Q35 Open OR in-progress OR resolved — three-way union (tests O1 UnionAll flattening)
- [x] Q36 Issues in sprint A OR issues with no sprint
- [x] Q37 Subtasks OR issues matching `"Bug:"` prefix
- [x] Q38 `SortBy(open issues).union(SortBy(critical issues))` — both sorts stripped (O3)
- [x] Q39 `SortBy(open).union(unresolved)` — left sort stripped (O3)

### Group 6 — Intersect queries (10) ✅
- [x] Q40 Critical AND open issues (conditions → `And`)
- [x] Q41 Open issues that are also in a sprint (condition AND `HasLink`)
- [x] Q42 Issues with assignee AND with at least one tag
- [x] Q43 High-estimate AND high-priority issues
- [x] Q44 `SortBy(all issues, priority).intersect(open issues)` — sort preserved (O3)
- [x] Q45 `SortBy(all issues, priority).intersect(SortBy(high priority, estimate))` — right sort stripped, left preserved
- [x] Q46 `ByIds` intersect condition — specific issues that are also open
- [x] Q47 Triple intersect: critical AND open AND in-sprint
- [x] Q48 Open issues intersected with issues in Engineering project
- [x] Q49 Unresolved issues that are also unassigned (two `HasNoLink` conditions)

### Group 7 — Difference queries (8) ✅
- [x] Q50 Open issues NOT assigned to a specific user
- [x] Q51 Critical issues NOT in any sprint
- [x] Q52 Issues in project A NOT marked as subtasks
- [x] Q53 High-priority issues NOT resolved
- [x] Q54 All issues NOT tagged with "bug" tag (specific RID)
- [x] Q55 `SortBy(open issues, priority).difference(assigned issues)` — sort preserved (O3)
- [x] Q56 `ByIds` difference condition — specific issues that are not open
- [x] Q57 Open issues NOT in a specific project

### Group 8 — Complex combined queries (10) ✅
- [x] Q58 (Critical OR high) AND open — union result intersected with condition
- [x] Q59 (Critical AND open) OR (high AND in-progress) — two intersects unioned
- [x] Q60 (Critical OR high) AND NOT resolved AND in-sprint — union, then intersect, then difference
- [x] Q61 Open issues in project A UNION open issues in project B, minus assigned issues
- [x] Q62 Cascaded 3-way union with fallback: `skip(1).union(skip(2)).union(skip(3))` (verifies O1 flattening)
- [x] Q63 (Sorted open) intersect (Sorted critical) — both sorted same key, sort preserved
- [x] Q64 (Open AND critical) UNION (high AND in-progress) — two And-conditions unioned → Or
- [x] Q65 Open in sprint A minus open in sprint B (two intersects, then difference)
- [x] Q66 Issues assigned to an employee AND in a project whose lead is the same employee (illustrates NestedCondition / chained links)
- [x] Q67 All issues sorted by priority UNION all issues sorted by estimate — both sorts dropped, result unsorted (O3)

### Group 9 — Aggregate fallback queries (19) ✅

`Aggregate` is produced when `combineEfficient` returns null. This happens when `extractCondition`
fails on either operand — i.e. for `FollowLink`, `Slice`, `UnionAll`/`Order`, `ReversedOrder`,
or `SortBy(FollowLink)` (O3 strips SortBy but the inner FollowLink still fails). Scenarios are
organized as a matrix: **left-side type × right-side type × operation (intersect / difference)**.

**FollowLink on left:**
- [x] Q68 `FollowLink(left) ∩ condition` — issues via project link, filtered to open
- [x] Q69 `FollowLink(left) \ condition` — issues via project link, minus assigned
- [x] Q70 `condition(left) ∩ FollowLink(right)` — reversed roles: FollowLink used as filter set
- [x] Q71 `condition(left) \ FollowLink(right)` — reversed roles: FollowLink used as exclusion set
- [x] Q72 `FollowLink(left) ∩ FollowLink(right)` — both sides are link traversals
- [x] Q73 `FollowLink(left) \ FollowLink(right)` — both sides are link traversals, difference

**Slice on left:**
- [x] Q74 `Slice(left) ∩ condition` — paginated result filtered by condition
- [x] Q75 `Slice(left) \ condition` — paginated result minus condition
- [x] Q76 `Slice(left) ∩ FollowLink(right)` — paginated result filtered by link-traversal set

**UnionAll fallback on left:**
- [x] Q77 `UnionAll(left) ∩ condition` — fallback union intersected with a condition
- [x] Q78 `UnionAll(left) \ condition` — fallback union differenced with a condition

**ReversedOrder on left:**
- [x] Q79 `ReversedOrder(left) ∩ condition` — reversed traversal filtered by condition

**SortBy(FollowLink) on left — O3 strip fails, Aggregate fallback:**
- [x] Q80 `SortBy(FollowLink)(left) ∩ condition` — sort stripped by O3, inner FollowLink triggers Aggregate

**Chained aggregates — Aggregate as left operand of another Aggregate:**
- [x] Q81 Double intersect: `(FollowLink ∩ condition) ∩ condition` — two sequential Aggregates, aggr_0 and aggr_1
- [x] Q82 Intersect then difference: `(FollowLink ∩ condition) \ condition` — first `P.within`, then `P.without`

**ByIds × FollowLink — ByIds efficiently combines with plain conditions but not with FollowLink:**
- [x] Q83 `ByIds(left) ∩ FollowLink(right)` — IdWithin combined with FollowLink is not extractable
- [x] Q84 `ByIds(left) \ FollowLink(right)`
- [x] Q85 `FollowLink(left) ∩ ByIds(right)` — ByIds as filter set (uses its special `startTraversal`)
- [x] Q86 `FollowLink(left) \ ByIds(right)`

---

## Follow-up: Real Database Integration
- [ ] Add a second test class `GremlinQueryCoverageIntegrationTest` that uses a real YouTrackDB instance, populates the data model with sample data, and verifies that each query above returns the expected entity count or specific entities.

---

## Part 2 — Optimization Analysis ✅

Analysis complete. See [`docs/query-optimization-analysis.md`](query-optimization-analysis.md) for the full write-up.

Summary of candidates identified:

- [x] Run through all 86 queries and identify which ones currently fall back to `UnionAll`/`Aggregate` where `combineEfficient` could potentially do better
- [x] Identify any patterns not yet handled by O1–O6
- [x] Propose new optimization candidates (O7+)

| ID | Description | Affected queries | Priority |
|----|-------------|-----------------|----------|
| O7 | FollowLink × Condition fusion — eliminates Aggregate | Q68, Q69, Q70, Q80, Q83, Q85 | High |
| O8 | And/Or flattening to n-ary form | Q35, Q47, Q60, Q61, Q65 | Medium |
| O9 | PropWithin coalescing from repeated `PropEqual` unions | Q30, Q31, Q35 | Low |
| O5 | Call `simplify()` after `combineBlocks` (already defined, not yet called) | edge cases | Trivial |
