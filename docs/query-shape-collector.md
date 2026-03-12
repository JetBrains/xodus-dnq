# XD-1257 Query Shape Collector

## Goal

Instrument DNQ's Gremlin query engine so that running an app's unit test suite
produces a frequency distribution of all executed query shapes. The output shows
both which query patterns are most common overall and where `Aggregate` appears
in that context — driving prioritization of future optimizations by real-world
frequency rather than speculation.

---

## Design decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| What to log | GremlinQuery **structural shape** | Shape is value-free and groupable; Gremlin string logging is a possible future addition |
| When to log | At **`GremlinQuery.start(gs)`** | Fires for every executed top-level query; sub-queries inside Aggregate/UnionAll go through `startTraversal()`/`continueTraversal()` internally and are not logged separately |
| Scope | **All queries**, not just Aggregate | Full frequency distribution shows both common fused patterns and where Aggregate appears within it |
| Grouping | **Shape string** keyed by recursive node descriptor + property/link names, values dropped | Same structure with different values collapses to one entry |

---

## Shape string format

Mirrors Kotlin constructor syntax. Rules:

- **Class names verbatim** — `Labeled`, `FollowLink`, `Aggregate`, `PropEqual`, etc.
- **Names kept as string literals** — property names, link names, entity type labels, enum values (`IN`, `OUT`, `Dedup`) — these distinguish meaningfully different shapes
- **Concrete data values → `?`** — actual property values, RIDs, numeric constants
- **Child queries/blocks** — recursively rendered in argument position

### Examples

```
// simple condition query
Labeled(Where(PropEqual("status", ?)), "Issue")

// fused FollowLink + condition (O7)
Labeled(AndThen(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), PropEqual("status", ?)), "Issue")

// Aggregate fallback: FollowLink intersect condition
Aggregate(Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue"), Where(PropEqual("priority", ?)))

// O4-fused union with dedup
Order(Labeled(FollowLink(UnionAll(Labeled(Where(PropEqual("key", ?)), "Project"), Labeled(Where(PropEqual("key", ?)), "Project")), IN, "project"), "Issue"), Dedup)
```

---

## Components

### 1. `GremlinQueryShape` — shape extractor

A standalone object that walks a `GremlinQuery` tree and produces a normalized
shape string. Located in the `gremlin` package alongside `GremlinQuery.kt`.

```kotlin
object GremlinQueryShape {
    fun of(query: GremlinQuery): String = buildString { append(query) }

    private fun StringBuilder.append(query: GremlinQuery) { ... }
    private fun StringBuilder.append(block: GremlinBlock) { ... }
}
```

Key mapping:

| GremlinQuery type | Shape form |
|-------------------|-----------|
| `Where(block)` | `Where(‹block›)` |
| `ByIds(ids)` | `ByIds(?)` |
| `Labeled(inner, T)` | `Labeled(‹inner›, "T")` |
| `AndThen(inner, block)` | `AndThen(‹inner›, ‹block›)` |
| `FollowLink(inner, IN, link)` | `FollowLink(‹inner›, IN, "link")` |
| `FollowLink(inner, OUT, link)` | `FollowLink(‹inner›, OUT, "link")` |
| `SortBy(inner, _)` | `SortBy(‹inner›, ?)` |
| `Order(inner, Dedup)` | `Order(‹inner›, Dedup)` |
| `Order(inner, _)` | `Order(‹inner›, ?)` |
| `ReversedOrder(inner)` | `ReversedOrder(‹inner›)` |
| `Slice(inner, _)` | `Slice(‹inner›, ?)` |
| `UnionAll(subs)` | `UnionAll(‹sub1›, ‹sub2›, …)` |
| `Aggregate(left, right, _)` | `Aggregate(‹left›, ‹right›)` |

| GremlinBlock type | Shape form |
|-------------------|-----------|
| `PropEqual(p, v)` | `PropEqual("p", ?)` |
| `PropWithin(p, vs)` | `PropWithin("p", ?)` |
| `PropInRange(p, lo, hi)` | `PropInRange("p", ?, ?)` |
| `PropNull(p)` | `PropNull("p")` |
| `PropNotNull(p)` | `PropNotNull("p")` |
| `HasLink(l)` | `HasLink("l")` |
| `HasNoLink(l)` | `HasNoLink("l")` |
| `HasLinkTo(l, rid)` | `HasLinkTo("l", ?)` |
| `HasLabel(t)` | `HasLabel("t")` |
| `All` | `All` |
| `None` | `None` |
| `And(ops)` | `And(‹op1›, ‹op2›, …)` |
| `Or(ops)` | `Or(‹op1›, ‹op2›, …)` |
| `Not(q)` | `Not(‹q›)` |
| `Where(chain)` | `Where(‹chain›)` |

---

### 2. `GremlinQueryCollector` — thread-safe accumulator

A singleton that counts occurrences per shape and dumps a sorted report.

```kotlin
object GremlinQueryCollector {
    @Volatile var enabled: Boolean = false

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun record(shape: String) {
        if (!enabled) return
        counts.computeIfAbsent(shape) { AtomicInteger(0) }.incrementAndGet()
    }

    fun reset() { counts.clear() }

    /** Returns entries sorted by count descending. */
    fun report(): List<ReportEntry> =
        counts.entries
            .sortedByDescending { it.value.get() }
            .map { ReportEntry(it.key, it.value.get()) }

    data class ReportEntry(val shape: String, val count: Int)
}
```

---

### 3. Hook in `GremlinQuery.start()`

```kotlin
fun start(gs: GraphTraversalSource): YT {
    GremlinQueryCollector.record(GremlinQueryShape.of(this))
    return startTraversal(gs).traversal
}
```

The `record()` call is a no-op when `enabled = false`, so there is no overhead
in normal use.

---

## Usage in app tests

```kotlin
@BeforeClass fun setUp() {
    GremlinQueryCollector.enabled = true
}

@AfterClass fun tearDown() {
    GremlinQueryCollector.enabled = false
    GremlinQueryCollector.report().forEach { (shape, count) ->
        println("[$count] $shape")
    }
    GremlinQueryCollector.reset()
}
```

The report is printed to stdout and can be redirected to a file for analysis.

---

## Open questions

1. **Gremlin string logging** — not in scope for now; shape string is sufficient. Could
   be added later by storing one example Gremlin string per shape (the `this` object
   at `start()` time already has everything needed to render it).

2. **Thread safety of shape generation** — `GremlinQueryShape.of()` walks immutable
   data structures, so it's safe. The `ConcurrentHashMap` in the collector handles
   concurrent recording.

3. **`AggregateNoOrder`** — defined in `GremlinQuery.kt` but never constructed anywhere.
   Dead code; no hook needed.

---

## Implementation order

1. `GremlinQueryShape.of()` + unit tests against known query trees
2. `GremlinQueryCollector` singleton
3. Hook in `GremlinQuery.start()`
4. Try against a real app test suite; tune shape format based on output
