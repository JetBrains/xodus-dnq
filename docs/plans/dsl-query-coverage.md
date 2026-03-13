# DSL-Level Query Coverage Plan

**Ticket:** XD-1258
**Branch:** adopt-youtrackdb

## Context

The query shape collector revealed a gap: GremlinQuery-level optimizer unit tests pass,
but the optimizations (O7, O9, O16) do not fire when queries are constructed via the
DNQ Kotlin DSL. Specifically, `flatMapDistinct` produces `Order(FollowLink(...), Dedup)`
rather than bare `Labeled(FollowLink(...), T)`, so the O7 pattern match never triggers.

The goal is to close this gap before analysing real app queries.

---

## Architecture decision: single canonical dataset in `dnq`

`GremlinQueryCoverageTest` currently lives in `dnq-entity-store` alongside a low-level
dataset (`QueryCoverageDataset`) that populates the DB via `YTDBStoreTransaction` directly.

The new approach:

1. Define the model and dataset at the **DNQ DSL level** (`XdEntity` subclasses +
   dataset populator using the DNQ DSL) — in the `dnq` module.
2. Use this single dataset from **both** test layers:
   - DSL tests: query via `XdQuery` / `XdEntity` DSL
   - Low-level tests: query via `YTDBEntityIterable` / `GremlinQuery` directly
3. **Move `GremlinQueryCoverageTest`** from `dnq-entity-store` to `dnq`. Since `dnq`
   already depends on `dnq-entity-store`, it has full access to both layers.
   `dnq-entity-store` keeps its own simpler tests (schema, transactions, lifecycle)
   but no longer owns the query coverage suite.

This avoids duplication and makes `dnq-entity-store` a circular dependency non-issue:
the DNQ-level dataset lives in `dnq`, which can reach down into `dnq-entity-store`
internals freely.

---

## Step 1 — Trace the flatMapDistinct path ✅

`selectManyDistinct` = `selectMany(link).distinct()`. `distinct()` calls
`modify(GremlinBlock.Dedup)` = `query.then(Dedup)`. Since `Dedup.type == BlockType.ORDER`,
`then()` produces `Order(FollowLink(...), Dedup)`.

The wrapping is **intentional**: without it, the same target entity appears multiple
times when multiple source entities link to it. The fix is in the optimizer (O17),
not the query construction. Documented in `docs/plans/query-optimization-analysis.md`.

## Step 2 — Define the DNQ-level model and dataset

Create DNQ entity type definitions (`XdEntity` subclasses) mirroring the existing
`QueryCoverageDataset` domain: `Issue`, `Project`, `Sprint`, `Tag`, `Employee`/`Manager`
hierarchy with the same properties and links.

Write a dataset initializer that uses the DNQ DSL to populate the same data
(24 issues, 4 projects, etc.). Place both in `dnq` test sources.

Delete `QueryCoverageDataset` and its schema setup from `dnq-entity-store`.

## Step 3 — Migrate GremlinQueryCoverageTest to `dnq` ✅

Move `GremlinQueryCoverageTest` to the `dnq` module. Update it to populate data
via the new DNQ-level dataset, while keeping all existing low-level `YTDBEntityIterable`
query assertions unchanged.

Deleted: `QueryCoverageDataset.kt`, `QueryCoverageDatasetTest.kt`, original `GremlinQueryCoverageTest.kt` from `dnq-entity-store`.

## Step 3a — Expand result assertions in GremlinQueryCoverageTest

Currently most test groups only spot-check a handful of result entity keys. Expand
every result-assertion section to verify the **complete** set of returned keys for
every query in that group — no spot-checks, no "hasSize(N)" without also checking the
exact contents. This gives the migration a high-confidence regression net before any
optimizer or translation changes are made.

## Step 4 — Add DSL-level shape + result tests ✅

In the same `dnq` module, add a new test class that exercises the same dataset via
the DNQ DSL (`XdQuery`) and asserts **both**:

1. The resulting `GremlinQueryShape` (optimizer fired or not):
   - Plain `query(prop eq value)` → `Labeled(Where(PropEqual), T)`
   - `flatMapDistinct` alone → expected shape (per Step 1)
   - `flatMapDistinct.intersect(condition)` → O7-fused or Aggregate?
   - `union` of two FollowLink sources → O9/O4 or UnionAll?

2. The **complete set of returned entity keys** for every query — not just a size
   check. Same principle as Step 3a: every DSL query must assert the exact full
   result, not a subset.

These tests fail if an optimizer regresses or a translation path silently bypasses it.

## Step 5 — Implement O17 ✅

Step 1 confirmed `Order(Dedup)` wrapping is intentional. The fix is O17 —
`Order(Dedup)` transparency for FollowLink fusion — documented in
`docs/plans/query-optimization-analysis.md`. Implement it in `combineEfficient` and
add GremlinQuery-level unit tests for the wrapped form.

Implemented O17 plus extensions to O4, O7, and O16 to handle bare `FollowLink` /
`AndThen(FollowLink, ...)` produced after O17 strips the `Order(Dedup)` wrapper.
A symmetric O17 rule handles `condition.intersect(Order(FL, Dedup))` (swaps operands
since intersect is commutative). DslQueryCoverageTest D38–D44 updated to assert the
fused shapes; GremlinQueryCoverageTest Q104 and YTDBGremlinEntityIterableTest
three-way union test updated to reflect the improved Gremlin.

## Step 6 — Re-run collector, verify ✅

Re-run `./gradlew :dnq:test -PcollectQueryShapes` and confirm that optimized shapes
appear in the output instead of Aggregate fallbacks.

Confirmed: `Dedup(AndThen(FL, cond))` and `Dedup(FollowLink(PropWithin))` shapes
appear in the output. Only one `Aggregate` remains — D45 (`condition.exclude(flatMapDistinct)`,
documented as an expected gap in group 9). No regressions.

---

## Resolved questions

- **Is `Order(Dedup)` always present on `flatMapDistinct`?** Yes — `selectManyDistinct`
  always calls `.distinct()` regardless of link cardinality.
- **Other tests depending on `QueryCoverageDataset`?** Only `GremlinQueryCoverageTest`
  and `QueryCoverageDatasetTest` (the latter is a smoke test that goes away with the migration).
