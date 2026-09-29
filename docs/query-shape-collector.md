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
| `dnq.query.collector.output` | Path to write the report file. If omitted, output goes to stdout. `{pid}` is replaced by the JVM's process id, so JVMs forked during a test run do not overwrite each other's file. |

The report is written automatically when the test JVM exits, after all test classes
have completed. Each line is one unique query shape with its occurrence count,
sorted by frequency descending:

```
[8432] Labeled(Where(PropEqual("status", ?)), "Issue")
[3201] Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue")
 [512] Aggregate(Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue"), Where(PropEqual("priority", ?)))
 [201] Dedup(Labeled(FollowLink(Labeled(Where(PropWithin("key", ?)), "Project"), IN, "project"), "Issue"))
```

To find all unoptimized queries in the output:

```bash
grep Aggregate /tmp/query-shapes.txt
```

### Translation outcomes

While collection is enabled, DNQ also observes whether YouTrackDB translated each executed query to
a MATCH statement. The outcomes follow the shape frequencies, after a `# translation outcomes` header,
as one tab-separated line per distinct outcome with five columns:

1. `[count]`
2. `TRANSLATED` or `NOT_TRANSLATED`
3. the DNQ shape
4. the parameterized native TinkerPop Gremlin shape (built from the traversal bytecode, including steps
   appended after `start()`; runtime values are `_args_n` placeholders). Untranslated outcomes are
   distinguished by it; for translated ones it is the first execution's
5. an execution example from the first execution seen, keeping its runtime values: the Gremlin script,
   the final provider traversal and, for translated queries, the execution plan of the MATCH that will run
   (and the MATCH statement when the planner recorded one); newlines are escaped as `\n`

```
# translation outcomes: [count]<TAB>OUTCOME<TAB>DNQ shape<TAB>native Gremlin shape<TAB>execution example
[812]	TRANSLATED	Labeled(Where(PropEqual("status", ?)), "Issue")	g.V().has(_args_0,_args_1).hasLabel(_args_2)	Gremlin: g.V().has("status","open").hasLabel("Issue")\nFinal traversal: [YTDBMatchPlanStep($g2m_v0,ELEMENT)]\nExecution plan:\n+ PREFETCH $g2m_v0 …
[40]	NOT_TRANSLATED	Labeled(Where(HasLink("assignee")), "Issue")	g.V().where(__.out(_args_0)).hasLabel(_args_1)	Gremlin: g.V().where(__.out("assignee_link")).hasLabel("Issue")\nFinal traversal: [YTDBGraphStep(vertex,[~label.eq(Issue)]), TraversalFilterStep([VertexStep(OUT,[assignee_link],edge)])]
```

Outcomes are recorded when provider strategies run, so a query that is built but never iterated has
none, and a traversal that is cloned and re-executed is counted each time. Collection only observes
queries; it never changes or fails them.

---

## Shape string format

Mirrors Kotlin constructor syntax. Rules:

- **Class names verbatim** — `Labeled`, `FollowLink`, `Aggregate`, `PropEqual`, etc.
- **Names kept as string literals** — property names, link names, entity type labels,
  enum values (`IN`, `OUT`) — these distinguish meaningfully different shapes
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
Dedup(Labeled(FollowLink(Labeled(Where(PropWithin("key", ?)), "Project"), IN, "project"), "Issue"))
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
| `SortBy(inner, _)` | `Sort(‹inner›, ?)` |
| `Order(inner, Dedup)` | `Dedup(‹inner›)` |
| `Order(inner, Reverse)` | `Reverse(‹inner›)` |
| `ReversedOrder(inner)` | `Reverse(‹inner›)` |
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
  boolean read per executed query. When enabled, each query also carries a small observing
  strategy; the native Gremlin script and execution example are rendered once per distinct outcome.
- The report covers all queries executed across the entire test JVM lifetime, not
  per test class — this is intentional, giving the full picture in one file.
