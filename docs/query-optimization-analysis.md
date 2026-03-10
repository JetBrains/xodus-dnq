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

**New identity cases added** (previously missing):

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

---

## Patterns confirmed as not optimizable

| Pattern | Reason |
|---------|--------|
| `condition \ FollowLink` (Q71, Q84) | Starting set is the condition; can't express "not reachable via link" as a simple append |
| `FollowLink ∩ FollowLink` (Q72, Q73) | Both sides are traversals; Aggregate is required |
| `FollowLink \ FollowLink` (Q73) | Same — Aggregate required |
| `Slice ∩ / \ anything` (Q74–Q76) | Order-dependent semantics; pushing condition before skip/limit changes results |
| `UnionAll ∩ / \ condition` (Q77–Q78) | Pushing condition into each union branch would change paging semantics |
| `ReversedOrder ∩ condition` (Q79) | Can't push condition before fold/reverse/unfold |
| O4 extension to intersect/difference for FollowLink | `FollowLink(A).intersect(FollowLink(B))` ≠ `FollowLink(A.intersect(B))` — an issue reachable from an A-vertex and separately from a B-vertex is in the intersection but may not be reachable from any vertex in `A ∩ B` |

---

## Summary

| ID | Description | Queries affected | Complexity |
|----|-------------|-----------------|------------|
| O7 | FollowLink × Condition fusion — eliminates Aggregate | Q68, Q69, Q70, Q80, Q83, Q85, Q86 | ✅ Done |
| O8 | And/Or flattening to n-ary form | Q35, Q47, Q60, Q65 | ✅ Done |
| O9 | PropWithin coalescing from repeated PropEqual unions | Q30, Q31, Q35, Q39, Q58, Q60 | ✅ Done |
| O5 | Recursive `simplify()` with full `None`/`All` coverage, wired into `Where.of()` and `NestedCondition` | edge cases | ✅ Done |
