# Current Work

**Ticket:** XD-1255

**Plan docs:**
- `docs/query-coverage-plan.md`
- `docs/query-optimization-analysis.md`

## Status

All 86 query scenarios in `GremlinQueryCoverageTest` are covered.
All optimizations O5, O7, O8, O9, and O10 (first pass: semantic duals,
deduplication, contradiction/tautology) are implemented and committed.

## Next

**O10 — Ensure `simplify()` fires at all block construction sites**

`simplify()` is currently only called from `Where.of()` and `NestedCondition.buildBlock()`.
Blocks assembled via other paths (notably O7's `andThen()` fusion) are never simplified,
so the same pattern (e.g. `Not(HasLink(l))`) is optimized or not depending on how the
block was built rather than what it is.

Task:
1. Find all sites in `combineBlocks` / `combineEfficient` that produce `Not`, `And`,
   or `Or` without subsequently passing through `Where.of()`.
2. Call `simplify()` at each such site (a `simplified()` convenience returning
   `simplify() ?: this` would reduce noise).
3. Update affected test assertions.

See `docs/query-optimization-analysis.md` § O10 for full analysis.

---

Backlog: Add `GremlinQueryCoverageIntegrationTest` (real DB, 86 queries, expected counts).
