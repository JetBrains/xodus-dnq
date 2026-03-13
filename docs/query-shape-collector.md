# Query Shape Collector

Instruments DNQ's Gremlin query engine to produce a frequency distribution of all
executed query shapes across a test run. The output shows which query patterns are
most common and where `Aggregate` (unoptimized) queries appear — useful for
prioritizing further optimizations based on real workloads.

---

## Usage

No code changes are needed in the app. Add two JVM arguments to the Gradle test task:

```groovy
test {
    jvmArgs "-Ddnq.query.collector.enabled=true",
            "-Ddnq.query.collector.output=/tmp/query-shapes.txt"
}
```

| Property | Description |
|----------|-------------|
| `dnq.query.collector.enabled` | Set to `true` to activate collection. Default: disabled. |
| `dnq.query.collector.output` | Path to write the report file. If omitted, output goes to stdout. |

The report is written automatically when the test JVM exits, after all test classes
have completed. Each line is one unique query shape with its occurrence count,
sorted by frequency descending:

```
[8432] Labeled(Where(PropEqual("status", ?)), "Issue")
[3201] Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue")
 [512] Aggregate(Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue"), Where(PropEqual("priority", ?)))
 [201] Order(Labeled(FollowLink(Labeled(Where(PropWithin("key", ?)), "Project"), IN, "project"), "Issue"), Dedup)
```

To find all unoptimized queries in the output:

```bash
grep Aggregate /tmp/query-shapes.txt
```

---

## Shape string format

Mirrors Kotlin constructor syntax. Rules:

- **Class names verbatim** — `Labeled`, `FollowLink`, `Aggregate`, `PropEqual`, etc.
- **Names kept as string literals** — property names, link names, entity type labels,
  enum values (`IN`, `OUT`, `Dedup`) — these distinguish meaningfully different shapes
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
Order(Labeled(FollowLink(Labeled(Where(PropWithin("key", ?)), "Project"), IN, "project"), "Issue"), Dedup)
```

### Full shape mapping

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

## Notes

- Collection is disabled by default; overhead when disabled is a single `@Volatile`
  boolean read per executed query.
- The report covers all queries executed across the entire test JVM lifetime, not
  per test class — this is intentional, giving the full picture in one file.
- Gremlin string logging (one example string per shape) is a possible future addition.
