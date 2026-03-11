# GremlinQuery Optimization Analysis

Analysis of all 86 queries in `GremlinQueryCoverageTest` against the current
`combineEfficient` implementation (O1–O6). Each candidate below is a pattern
that appears in the test suite and is not yet handled.

Existing optimizations for reference:

| ID | Description |
|----|-------------|
| O1 | Flatten cascaded `UnionAll` — `Order(UnionAll([...]), Dedup)` shape merges nested unions |
| O2 | Identity shortcuts in `combineBlocks` — `a==b→a` for Intersect/Union, `None` for Difference |
| O3 | `SortBy` passthrough — strip sort wrappers; left sort preserved for intersect/difference, dropped for union |
| O4 | `FollowLink` union shortcut — merges `Labeled(FollowLink(A),T).union(Labeled(FollowLink(B),T))` when same dir/link |
| O5 | `simplify()` on combined block — defined but not yet called in `combineEfficient` |
| O6 | `Labeled.of` flattens same-label nesting — `Labeled(Labeled(X,"T"),"T")` → `Labeled(X,"T")` |

---

## O7 — FollowLink × Condition fusion

**Priority: High. New.**

### Problem

When one operand is `Labeled(FollowLink(src, dir, link), T)` and the other has an
extractable condition block (`Where`, `ByIds`, or `NestedCondition`), the current
code falls back to `Aggregate` because `extractCondition` returns null for `FollowLink`.
This produces a collect-then-filter traversal with a named aggregate step.

### Key insight

`issues-via-link ∩ condition` = "vertices reachable via the link that also satisfy
condition" = traverse the link, then append a filter. No collection into a named set
is needed. The condition block can simply be appended to the `FollowLink` traversal.

### Affected queries

| Query | Current Gremlin (simplified) | Optimized Gremlin |
|-------|------------------------------|-------------------|
| Q68 `FollowLink(left) ∩ condition` | `g.V().has(cond)...aggregate("aggr_0").fold().V().src.in(link).where(P.within(...))` | `g.V().src.in(link).has(cond).hasLabel(T)` |
| Q69 `FollowLink(left) \ condition` | Aggregate with `P.without` | `g.V().src.in(link).not(has(cond)).hasLabel(T)` |
| Q70 `condition(left) ∩ FollowLink(right)` | Aggregate (FollowLink as filter set) | same result as Q68 — swap operands, append cond |
| Q80 `SortBy(FollowLink) ∩ condition` | O3 strips sort, inner FollowLink still fails → Aggregate | O3 strips sort, O7 fires on inner, sort re-wrapped |
| Q83 `ByIds ∩ FollowLink` | Aggregate (`ByIds.asBlock()=IdWithin` extractable, but FollowLink side is not) | `g.V().src.in(link).hasId(P.within([...])).hasLabel(T)` |
| Q85 `FollowLink ∩ ByIds` | Aggregate (ByIds as filter set via special `startTraversal`) | same result as Q83 — symmetric |
| Q86 `FollowLink \ ByIds` | Aggregate (ByIds as filter set via special `startTraversal`) | `g.V().src.in(link).not(__.hasId(P.within([...]))).hasLabel(T)` |

### What does NOT apply

- **`condition \ FollowLink` (Q71, Q84):** The starting set is the condition side. To
  check that each condition-matching vertex is NOT reachable via the link requires
  inspecting the link from the vertex, not appending to the FollowLink traversal.
  `Aggregate` stays.
- **`FollowLink ∩ FollowLink` (Q72, Q73):** Both sides are traversals with no
  extractable condition. `Aggregate` stays.
- **`Slice ∩ anything` (Q74–Q76):** Slice has order-dependent semantics. Pushing a
  condition before the skip/limit changes which items are skipped. Not safe.

### Implementation sketch

In `combineEfficient`, before the `extractCondition` block:

```kotlin
// O7: FollowLink + extractable-condition fusion — avoids Aggregate
if (condCombiner is ConditionCombiner.Intersect || condCombiner is ConditionCombiner.Difference) {
    val linkQuery: Labeled?
    val condBlock: GremlinBlock?

    when {
        this is Labeled && this.inner is FollowLink -> {
            // this is the link side; check the other side for an extractable condition
            condBlock = extractCondition(other)
            linkQuery = if (condBlock != null) this else null
        }
        condCombiner is ConditionCombiner.Intersect &&
        other is Labeled && other.inner is FollowLink -> {
            // for intersect only: the other side is the link, this side has the condition
            condBlock = extractCondition(this)
            linkQuery = if (condBlock != null) other else null
        }
        else -> { linkQuery = null; condBlock = null }
    }

    if (linkQuery != null && condBlock != null) {
        val appended = if (condCombiner is ConditionCombiner.Difference)
            GremlinBlock.Not(condBlock) else condBlock
        return Labeled(linkQuery.inner.then(appended), linkQuery.label)
    }
}
```

Note: the `then(appended)` call on `FollowLink` falls into the `else → AndThen(this, block)`
branch of `GremlinQuery.then`, producing `Labeled(AndThen(FollowLink(...), cond), T)`.
The resulting Gremlin is `g.V().src.dir("link_link").{cond}.hasLabel(T)` — a single
linear traversal with no aggregate.

---

## O8 — And / Or flattening to n-ary form

**Priority: Medium. New.**

### Problem

All boolean combinations are currently binary trees. Three-way unions produce
`Or(Or(a,b), c)`, three-way intersects produce `And(And(a,b), c)`. TinkerPop's
`.or(a, b, c)` and `.and(a, b, c)` accept n-ary arguments, so the nested form
is correct but adds an unnecessary intermediate step.

### Affected queries

| Query | Current | Optimized |
|-------|---------|-----------|
| Q35 three-way union | `or(or(open, in-progress), resolved)` | `or(open, in-progress, resolved)` |
| Q47 triple intersect | `and(and(critical, open), in-sprint)` | `and(critical, open, in-sprint)` |
| Q60 union+diff+intersect | `and(and(or(c,h), not(resolved)), in-sprint)` | `and(or(c,h), not(resolved), in-sprint)` |
| Q61 union+diff | `and(or(and(o,A), and(o,B)), not(assignee))` | same Or nesting, outer And could flatten |
| Q65 two intersects then diff | `and(and(open, sprintA), not(and(open, sprintB)))` | `and(open, sprintA, not(and(open, sprintB)))` |

### Implementation

Requires either:
- **New block types** `NaryOr(blocks: List<GremlinBlock>)` and `NaryAnd(blocks: List<GremlinBlock>)`.
- **Or** recursive detection in `combineBlocks`: when `Or(a, b)` is built and `a` is
  already an `Or`, accumulate into a list representation.

The DB likely plans `or(or(a,b),c)` and `or(a,b,c)` identically, so this is a
readability / query-plan-inspection improvement rather than a runtime gain.

---

## O9 — PropWithin coalescing

**Priority: Low. New.**

### Problem

`Or(PropEqual("status","open"), PropEqual("status","in-progress"))` is equivalent to
`PropWithin("status", ["open","in-progress"])`. A single `has("status", P.within([...]))`
is simpler than `or(has("status","open"), has("status","in-progress"))`.

### Affected queries

Q30, Q31, Q35 (partial — both sides are `PropEqual` on the same property).

### Condition for applicability

Inside `combineBlocks` for the `Union` combiner: when both `a` and `b` are `PropEqual`
on the same property name, or one is `PropEqual` and the other is `PropWithin` on the
same property, merge into a single `PropWithin`.

```kotlin
// In ConditionCombiner.Union.combineBlocks:
a is PropEqual && b is PropEqual && a.property == b.property ->
    PropWithin(a.property, listOf(a.value, b.value))

a is PropEqual && b is PropWithin && a.property == b.propName ->
    PropWithin(b.propName, listOf(a.value) + b.within)
```

The improvement is mostly cosmetic. Whether the DB treats `or(has(p,v1), has(p,v2))`
differently from `has(p, P.within([v1,v2]))` depends on the query planner.

---

## O5 — `simplify()` recursive propagation ✅

**Priority: Low. Done.**

`simplify()` implementations were extended and made recursive, then wired into two call sites.

**New identity cases added (first pass):**

| Block | New cases |
|-------|-----------|
| `Or` | `None OR x → x`, `x OR None → x` |
| `And` | `None AND x → None`, `x AND None → None` |
| `Not` | `NOT(All) → None`, `NOT(None) → All` |
| `Where` | `Where(All) → All`, `Where(None) → None` (new override) |
| `AndThen` | `None THEN x → None`, `x THEN None → None` |

**Recursive implementation:** each compound block simplifies its children first, then applies
its own rules. A new parent node is only allocated when a child changed — trees requiring no
simplification incur zero allocations beyond O(n) method calls.

**Call sites:**
- `GremlinQuery.Where.of(block)` — covers `combineBinary`, `combineUnary`, `combineEfficient`
- `NestedCondition.buildBlock()` — extracted companion function covers the chained-link path

**Additional cases added (second pass):**

*`Not` — semantic duals:*

| Pattern | Simplification | Effect |
|---------|---------------|--------|
| `Not(HasLink(l))` | `→ HasNoLink(l)` | Eliminates `.not(__.where(__.out(...)))` double-negation |
| `Not(HasNoLink(l))` | `→ HasLink(l)` | Same |
| `Not(PropNull(p))` | `→ PropNotNull(p)` | Cleaner Gremlin |
| `Not(PropNotNull(p))` | `→ PropNull(p)` | Same |

*`And` — deduplication and contradiction:*

| Pattern | Simplification |
|---------|---------------|
| `And([…, x, …, x, …])` | deduplicate (structural equality) |
| `And(HasLink(l), HasNoLink(l))` | `→ None` |
| `And(PropNull(p), PropNotNull(p))` | `→ None` |

*`Or` — deduplication and tautology:*

| Pattern | Simplification |
|---------|---------------|
| `Or([…, x, …, x, …])` | deduplicate; also prevents O9 from emitting `PropWithin` with duplicate values |
| `Or(HasLink(l), HasNoLink(l))` | `→ All` |
| `Or(PropNull(p), PropNotNull(p))` | `→ All` |

The `Not` semantic duals cascade into the `And`/`Or` rules: `And(HasLink(l), Not(HasLink(l)))` first reduces to `And(HasLink(l), HasNoLink(l))` via `Not.simplify`, then to `None` via `And.simplify`.

---

## Patterns confirmed as not optimizable

| Pattern | Reason |
|---------|--------|
| `FollowLink ∩ FollowLink` (Q72, Q73) | Both sides are traversals; Aggregate is required |
| `FollowLink \ FollowLink` (Q73) | Same — Aggregate required |
| `Slice ∩ / \ anything` (Q74–Q76) | Order-dependent semantics; pushing condition before skip/limit changes results |
| `UnionAll ∩ / \ condition` (Q77–Q78) | Pushing condition into each union branch would change paging semantics |
| `ReversedOrder ∩ condition` (Q79) | Can't push condition before fold/reverse/unfold |
| O4 extension to intersect/difference for FollowLink | `FollowLink(A).intersect(FollowLink(B))` ≠ `FollowLink(A.intersect(B))` — an issue reachable from an A-vertex and separately from a B-vertex is in the intersection but may not be reachable from any vertex in `A ∩ B` |

Note: `condition \ FollowLink` (Q71, Q84, Q90) and `condition ∪ FollowLink` (Q91) were
previously listed here but are now O11 candidates — see below.

---

## O11 — Inverse-link predicate for `condition OP FollowLink(src)`

**Priority: Medium. Not yet started.**

### Problem

When the left operand is an extractable condition and the right operand is a
`Labeled(FollowLink(src, IN, link), T)`, the current code falls back to `Aggregate`.
Examples:

| Query | Current Gremlin (simplified) |
|-------|------------------------------|
| Q71 `cond \ FollowLink(All)` | `g.V().hasLabel("Sprint").in("sprint_link")…aggregate.fold().V().has(cond)…where(P.without)` |
| Q84 `ByIds \ FollowLink(src)` | `g.V().src.in(link)…aggregate.fold().V().hasId(…).where(P.without)` |
| Q90 `cond \ FollowLink(src)` | `g.V().src.hasLabel("Project").in("project_link")…aggregate.fold().V().has(cond)…where(P.without)` |
| Q91 `cond ∪ FollowLink(src)` | `g.union(__.V().has(cond)…, __.V().src.in(link)…).dedup()` |

### Key insight

Membership in `FollowLink(src, IN, link)` can be tested from the candidate vertex
using an outgoing-edge probe:

- `src = Labeled(Where.of(All), "Sprint")` — any vertex with a `sprint_link` edge qualifies
  → inverse predicate = `HasLink("sprint")`
- `src = Labeled(Where.of(srcCond), "Project")` — vertex must have a `project_link` edge
  reaching a Project vertex satisfying `srcCond`
  → inverse predicate = `NestedCondition(["project"], Where.of(srcCond))` + label guard

This means the entire operation can be expressed as a single `Labeled(Where)` traversal:

| Operation | O11 rewriting |
|-----------|---------------|
| `cond \ FollowLink(src)` | `And(cond, Not(inversePredicate))` → `Labeled(Where)` |
| `cond ∪ FollowLink(src)` | `Or(cond, inversePredicate)` → `Labeled(Where)` |
| `cond ∩ FollowLink(src)` | already handled by O7 (FollowLink on right, cond extracted) |

### Affected queries and optimized Gremlin

| Query | Optimized Gremlin |
|-------|-------------------|
| Q71 `issues(high) \ issuesInSprint()` | `g.V().and(__.has("priority","high"), __.not(__.out("sprint_link"))).hasLabel("Issue")` |
| Q84 `ByIds \ issuesInProject(ENG)` | `g.V().and(__.hasId(P.within([…])), __.not(__.where(__.out("project_link").has("key","ENG").hasLabel("Project")))).hasLabel("Issue")` |
| Q90 `issues(open) \ issuesInProject(ENG)` | `g.V().and(__.has("status","open"), __.not(__.where(__.out("project_link").has("key","ENG").hasLabel("Project")))).hasLabel("Issue")` |
| Q91 `issues(open) ∪ issuesInProject(ENG)` | `g.V().or(__.has("status","open"), __.where(__.out("project_link").has("key","ENG").hasLabel("Project"))).hasLabel("Issue")` |

### Sub-cases for building the inverse predicate

Given `FollowLink(srcQuery, IN, linkName)` where `srcQuery = Labeled(Where.of(srcBlock), srcLabel)`:

1. **`srcBlock == All`**: inverse = `HasLink(linkName)`, simplifies to `not(__.out("linkName_link"))` via O5
2. **`srcBlock` is extractable**: inverse = a new block representing `where(out("linkName_link").{srcBlock}.hasLabel(srcLabel))`

Case 2 requires either extending `NestedCondition` to carry a target label filter or a
small new block (e.g., `InverseLink(linkName, srcBlock, srcLabel)`). The `NestedCondition`
route adds the label as an extra `hasLabel` step at the end of the traversal inside the
`where(...)`.

### Implementation sketch

In `combineEfficient`, in the branch handling `Labeled` results, before calling `Aggregate`:

```kotlin
// O11: condition OP FollowLink(src) — rewrite using inverse-link predicate
val flSide  = if (other is Labeled && other.inner is FollowLink) other else null
val condSide = if (flSide != null) this else null
if (flSide != null && condSide != null) {
    val srcQuery = (flSide.inner as FollowLink).inner
    val linkName = (flSide.inner as FollowLink).linkName
    if (srcQuery is Labeled) {
        val srcBlock = (srcQuery.inner as? GremlinQuery.Where)?.block
        val inversePredicate: GremlinBlock? = when {
            srcBlock == null -> null
            srcBlock == GremlinBlock.All -> GremlinBlock.HasLink(linkName)
            else -> buildInverseLink(linkName, srcBlock, srcQuery.label)  // new helper
        }
        if (inversePredicate != null) {
            val condBlock = extractCondition(condSide)
            if (condBlock != null) {
                val combined = when (condCombiner) {
                    is ConditionCombiner.Difference -> GremlinBlock.And(condBlock, GremlinBlock.Not(inversePredicate))
                    is ConditionCombiner.Union      -> GremlinBlock.Or(condBlock, inversePredicate)
                    else -> null  // intersect already handled by O7
                }
                if (combined != null) return Labeled(GremlinQuery.Where.of(combined), flSide.label)
            }
        }
    }
}
```

`buildInverseLink(linkName, srcBlock, srcLabel)` emits the traversal step
`where(out("linkName_link").{srcBlock}.hasLabel(srcLabel))`.

### Baseline tests

Q90, Q91, and Q92 in `GremlinQueryCoverageTest` group 11 assert the current (Aggregate/UnionAll)
Gremlin and include `TODO O11` comments with the expected optimized output.

---

## O10 — Audit GremlinQuery-level optimizations for block-level applicability

**Priority: Medium. Not yet started.**

### Problem

`GremlinBlock.simplify()` is only called from two sites:
- `GremlinQuery.Where.of(block)`
- `NestedCondition.buildBlock()`

This means blocks constructed by other paths — notably the O7 `andThen()` fusion path,
and any direct block construction in the translation layer — are **never simplified**.
The same pattern (`Not(HasLink(l))`, duplicate operands, etc.) gets simplified or not
depending on *how* the block was assembled, not on *what* it is.

For example, Q69's O7-fused traversal produces `Not(HasLink("assignee"))` appended via
`andThen()`. Because `simplify()` is never called on it, the emitted Gremlin is
`.not(__.where(__.out("assignee_link")))` rather than the cleaner `.not(__.out("assignee_link"))`.
The same input going through `Where.of()` (Q51, Q55, Q61) does get simplified.

### Analysis of GremlinQuery-level optimizations by level-fit

| Optimization | Touches | Can move to block level? |
|---|---|---|
| O1 — flatten cascaded `UnionAll` | `Order`/`UnionAll` (`GremlinQuery`) | No — no block equivalent |
| O2 — identity shortcuts (`a==a→a`) | `GremlinQuery` identity | Partially — `And(x,x)→x`, `Or(x,x)→x` already added to `simplify()` |
| O3 — `SortBy` passthrough | `SortBy` (`GremlinQuery`) | No — no block equivalent |
| O4 — `FollowLink` union shortcut | `FollowLink` (`GremlinQuery`) | No — no block equivalent |
| O7 — `FollowLink × Condition` fusion | `FollowLink` + condition block | No — but the *condition* it appends should be simplified |
| O8 — And/Or n-ary flattening | `And`/`Or` (`GremlinBlock`) | Already at block level via `simplify()` |
| O9 — `PropWithin` coalescing | `PropEqual`/`PropWithin` (`GremlinBlock`) | Already moved to `simplify()` |

### Proposed fix

Ensure `simplify()` is called on every `GremlinBlock` at the point of construction,
not only inside `Where.of()`. The most targeted fix: call `block.simplify() ?: block`
on the `appended` block inside O7 before wrapping in `AndThen`. More complete fix:
add a `GremlinBlock.simplified()` convenience (returns `simplify() ?: this`) and call
it wherever blocks are finalized — O7, any direct `Not(...)` wrapping in `combineBlocks`,
and any other site that constructs compound blocks outside `Where.of()`.

Audit the full call graph from `combineBlocks` / `combineEfficient` to find all sites
that produce `Not`, `And`, or `Or` without subsequently passing through `Where.of()`.

---

## Discovering future optimization candidates

Five approaches for finding the next round of opportunities, roughly by effort:

**1. Instrument the `Aggregate` fallback**

Add a log/counter every time `Aggregate` is constructed. Run integration tests or a real
YouTrack instance and see how often it fires and for which operand-type pairs. Turns
"what might be optimizable" into "what actually fires in practice".

**2. Write a type-pair matrix test**

`GremlinQuery` has a finite set of subclasses. Exercise every
`left-type × right-type × {union, intersect, difference}` combination and record whether
the result is `Aggregate`, a fused traversal, or something else. Gaps in current coverage
appear as a table — similar to the analysis that led to O7, but exhaustive.

**3. Audit the `NodeBase` → `GremlinQuery` translation layer**

Every `NodeBase` subclass in `dnq-query` maps to a `GremlinQuery` shape. Some node types
may produce shapes that were never considered in `combineEfficient` — especially compound
nodes like `LinksEqualDecorator`, `PropertyValueIn`, or anything involving metadata. The
translation code is the bridge between the user-visible DSL and the optimizer; blind spots
there are blind spots everywhere.

**4. Capture Gremlin from a real YouTrack query workload**

Enable Gremlin string logging for a representative set of YouTrack/Hub queries (issue
lists, dashboards, agile boards). Scan the output for `aggregate(` — every occurrence is a
missed optimization. Directly prioritizes by user impact.

**5. Systematically audit `simplify()` coverage** ✅

Walk the truth table of `All`/`None` for every block type and check for unhandled
identities. Also check semantic duals (`Not(HasLink) ↔ HasNoLink`, etc.) and structural
patterns (deduplication, contradiction, tautology). Done — see O5 second-pass section above.

---

## Summary

| ID | Description | Queries affected | Complexity |
|----|-------------|-----------------|------------|
| O7 | FollowLink × Condition fusion — eliminates Aggregate | Q68, Q69, Q70, Q80, Q83, Q85, Q86 | ✅ Done |
| O8 | And/Or flattening to n-ary form | Q35, Q47, Q60, Q65 | ✅ Done |
| O9 | PropWithin coalescing from repeated PropEqual unions — lives in `Or.simplify()`; removed duplicate from `Union.combineBlocks` | Q30, Q31, Q35, Q39, Q58, Q60 | ✅ Done |
| O5 | Recursive `simplify()`: `None`/`All` identities, semantic duals (`Not(HasLink)↔HasNoLink`, `Not(PropNull)↔PropNotNull`), deduplication, contradiction/tautology detection | edge cases | ✅ Done |
| O11 | Inverse-link predicate for `cond OP FollowLink(src)` — eliminates Aggregate/UnionAll for `difference` and `union` when left side is extractable condition | Q71, Q84, Q90, Q91 | Not started |
