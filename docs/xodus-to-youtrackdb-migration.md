# Xodus → YouTrackDB Migration

This document describes the ongoing migration of xodus-dnq's storage backend from [Xodus](https://github.com/JetBrains/xodus) to **YouTrackDB** (a JetBrains fork of OrientDB), accessed via the Apache TinkerPop Gremlin graph traversal API.

## Related Codebases

| Codebase | Location | Role |
|---|---|---|
| xodus-dnq master (Xodus-based) | `~/code/xodus-dnq-branches/master` | Original branch this diverged from |
| Xodus database | `~/code/xodus` | Original storage engine (being replaced) |
| YouTrackDB | `~/code/youtrackdb` | New storage engine (OrientDB fork) |

## Why the Migration

YouTrackDB is a graph database that natively models entities as vertices and relationships as edges. This aligns better with the DNQ entity/link model than a key-value store, and allows using Gremlin as a standard, well-supported query language instead of Xodus-specific iterables.

## Structural Changes vs Master

### Module Count: 4 → 10

`master` has 4 modules and takes everything storage-related from external Xodus JARs (`xodus-entity-store`, `xodus-query`, `xodus-utils`, `xodus-environment` at version 3.0.86). This branch adds 6 new modules and drops the Xodus runtime dependency entirely:

| New module | Origin / purpose |
|---|---|
| `dnq-entity-store` | The new YouTrackDB backend (no equivalent in master) |
| `dnq-query` | Copied from `xodus-query`, extended with `YouTrackDbSchemaInitializer` and Gremlin query translation |
| `dnq-utils` | Copied from `xodus-utils` (Caffeine caches, data structures) |
| `dnq-xodus-open-api` | Thin stubs of Xodus API types still referenced by upper layers |
| `dnq-crypto` / `dnq-compress` | Copied from Xodus to eliminate the runtime dependency |
| `dnq-migrate` | Entirely new — one-shot Xodus→YouTrackDB data migrator |

`java-8-time` from master is not yet present in this branch.

The Xodus interfaces still used as API contracts (e.g. `PersistentEntityStore`, `EntityId`, `EntityIterable`) are kept as lightweight stubs inside `dnq-xodus-open-api`.

### High-Level Comparison Table

| Aspect | `master` | `adopt-youtrackdb` |
|---|---|---|
| Storage backend | Xodus `PersistentEntityStoreImpl` (B+tree/Patricia trie, file-based KV) | YouTrackDB `YTDBPersistentEntityStore` (graph DB, Gremlin API) |
| Xodus as dependency | `org.jetbrains.xodus:*:3.0.86` (external) | No runtime Xodus dependency; select APIs copied in-tree |
| Query engine | `xodus-query` external JAR, `QueryEngine` against Xodus iterables | `dnq-query` module in-tree, `GremlinQuery`/`GremlinBlock` against YTDB traversals |
| Entity ID type | `PersistentEntityId` (typeId + localId integers) | `RIDEntityId` (wraps YouTrackDB `RID`; also stores classId/localId for compat) |
| Entity storage | Xodus `PersistentEntity` backed by BTree stores | `YTDBVertexEntity` backed by a YouTrackDB graph vertex |
| Link storage | Xodus internal link tables in BTree stores | YouTrackDB edges with class `<linkName>_link` |
| Schema management | Schema-less; DNQ metadata tracked in `ModelMetaDataImpl` only | `YouTrackDbSchemaInitializer` creates actual OClasses, indices, edge classes in YTDB |
| Transaction type | `PersistentStoreTransaction` wrapping Xodus `Environment` transaction | `YTDBStoreTransactionImpl` wrapping `YTDBGraph` / `DatabaseSessionEmbedded` |
| `TransientEntityStoreImpl.persistentStore` type | `PersistentEntityStore` (Xodus interface) | `YTDBPersistentEntityStore` (concrete YTDB class) |
| Session management | `ContextualEnvironment` always knows current thread's transaction | `ThreadLocal<YTDBStoreTransaction>` in `YTDBPersistentEntityStore` |
| Encryption | First-class cipher support in `EnvironmentConfig` | `YTDBDatabaseParams.withEncryptionKey()` / `withHexEncryptionKey()` |

## Entity ID Backward Compatibility

Xodus used integer `(typeId, localEntityId)` pairs as entity IDs. The YTDB backend maintains this for compatibility:
- Each vertex stores a `classId` property (integer type ID) and `localEntityId` property (sequential ID)
- `YTDBSchemaBuddy` manages sequences for both; `CLASS_ID_SEQUENCE_NAME = "sequence_classId"` and per-class `localEntityIdSequenceName`
- `RIDEntityId` is the native YTDB entity ID (wraps `RID`)
- `PersistentEntityId` ↔ `RIDEntityId` lookup goes through `YTDBSchemaBuddy.resolveEntityIdOrNull()`

## Link Storage Convention

Links are stored as YouTrackDB graph edges. Edge class name = `<linkName>_link` (`YTDBVertexEntity.edgeClassName()`). When a link carries a target's legacy `PersistentEntityId`, it is stored in a property `<linkName>_link_targetEntityId` on the edge.

## YouTrackDB Dependency

YouTrackDB is pulled from JetBrains team Maven repositories:
```
https://central.sonatype.com/repository/maven-snapshots
https://packages.jetbrains.team/maven/p/xodus/youtrackdb-daily
```
The version pin lives in `dnq-entity-store/build.gradle.kts` as `ytdbVersion` (currently a dev-SNAPSHOT). The key artifact is `io.youtrackdb:youtrackdb-core`.

---

## NodeBase Query Tree

Both branches share the same abstract `NodeBase` root (`jetbrains.exodus.query.NodeBase`), but the class hierarchies and execution models are fundamentally different.

### Master — many specialised subclasses, direct `EntityIterable` composition

The master branch (via external `xodus-query` JAR) has ~15 concrete node types, each directly calling a specific Xodus transaction method in its `instantiate()` override:

| Class | What `instantiate()` calls |
|---|---|
| `GetAll` | `queryEngine.instantiateGetAll(entityType)` |
| `PropertyEqual` | `txn.find(entityType, name, value)` |
| `PropertyRange` | `txn.find(entityType, name, min, max)` |
| `PropertyNotNull` | `txn.findWithProp()` / `txn.findWithBlob()` |
| `PropertyContains` | `txn.findContaining(entityType, name, value, ignoreCase)` |
| `PropertyStartsWith` | `txn.findStartingWith(entityType, name, prefix)` |
| `LinkEqual` | `txn.findLinks(entityType, entity, linkName)` |
| `LinkNotNull` | `txn.findWithLinks(entityType, linkName)` |
| `GetLinks` | `entity.getLinks(linkName)` |
| `And` (CommutativeOperator) | `queryEngine.intersectAdjusted(left, right)` |
| `Or` (CommutativeOperator) | `queryEngine.unionAdjusted(left, right)` |
| `Minus` (BinaryOperator) | `queryEngine.excludeAdjusted(left, right)` |
| `Concat` (BinaryOperator) | `queryEngine.concatAdjusted(left, right)` |
| `UnaryNot` (UnaryNode) | throws — must be optimised away before execution |
| `SortByProperty` (Sort/UnaryNode) | `sortEngine.sort(entityType, propName, iterable, ascending)` |
| `SortByLinkProperty` (Sort/UnaryNode) | `sortEngine.sortByLinked(...)` |
| `Wildcard`, `ConversionWildcard` | used only in optimisation rule pattern-matching, never executed |

The tree is evaluated bottom-up by recursively calling `instantiate()`. The result of every node is an `Iterable<Entity>` (a lazy Xodus `EntityIterable`). Binary nodes combine two iterables with set operations; sort nodes wrap an iterable.

```
And.instantiate()
  left  → PropertyEqual.instantiate() → txn.find("Person", "name", "John")  → EntityIterable
  right → PropertyNotNull.instantiate() → txn.findWithProp("Person", "age") → EntityIterable
  → queryEngine.intersectAdjusted(left, right)  →  EntityIterable (lazy intersection)
```

### This branch — three generic node types, Gremlin traversal compilation

The `NodeBase` subclass hierarchy is collapsed to **three generic, reusable node types** in `dnq-query/src/main/kotlin/jetbrains/exodus/query/`:

| Class | Role |
|---|---|
| `LeafNode(query: GremlinQuery)` | Wraps a single `GremlinBlock` condition (or the `All`/`None` singletons) |
| `UnaryNode(child, shortName, op: (GremlinBlock) -> GremlinBlock)` | Applies a transformation function to the child's block (e.g. `::Not`) |
| `BinaryNode(left, right, commutative, shortName, combineQuery: (GremlinBlock, GremlinBlock) -> GremlinBlock)` | Combines two children's blocks with a function (e.g. `::And`, `::Or`, `::AndThen`) |

The key method is now **`getQuery(): GremlinQuery`** (instead of `instantiate()`). `instantiate()` still exists but just calls `getQuery()` and executes the resulting traversal:

```kotlin
// LeafNode
fun instantiate(...) = YTDBEntityIterable.query(
    txn, query.then(GremlinBlock.HasLabel(entityType))
)
```

Concrete query node variants are created by `NodeFactory` (a Kotlin object), which wires up the appropriate `GremlinBlock` constructors as lambda arguments:

```
NodeFactory.and(left, right)
  → BinaryNode(left, right, commutative=true, "and", combineQuery=::And)

BinaryNode.getQuery()
  → leftCondition.combineBinary(rightCondition, ::And)
  → GremlinQuery.Where(GremlinBlock.And(leftBlock, rightBlock))
```

### Old node → new GremlinBlock mapping

| Old node (master) | New `GremlinBlock` (this branch) |
|---|---|
| `GetAll` | `GremlinBlock.All` (singleton) |
| `PropertyEqual(name, null)` | `GremlinBlock.PropNull(name)` |
| `PropertyEqual(name, value)` | `GremlinBlock.PropEqual(name, value)` |
| `PropertyNotNull(name)` | `GremlinBlock.PropNotNull(name)` |
| `PropertyRange(name, min, max)` | `GremlinBlock.PropInRange(name, min, max)` |
| `PropertyContains(name, v, ignoreCase)` | `GremlinBlock.MatchStringProp(name, Substring, v, isCollection = false)` |
| `PropertyStartsWith(name, v, ignoreCase)` | `GremlinBlock.MatchStringProp(name, Prefix, v, isCollection = false)` |
| `LinkEqual(name, entityId)` | `GremlinBlock.HasLinkTo(name, rid)` |
| `LinkNotNull(name)` | `GremlinBlock.HasLink(name)` |
| `And` | `GremlinBlock.And(left, right)` via `BinaryNode(..., ::And)` |
| `Or` | `GremlinBlock.Or(left, right)` via `BinaryNode(..., ::Or)` |
| `Minus` | `GremlinBlock.Not` + `And` composition |
| `UnaryNot` | `GremlinBlock.Not(block)` via `UnaryNode(..., ::Not)` |
| `SortByProperty` | `GremlinBlock.Sort(ByProp(name), direction)` via `LeafNode` |
| `SortByLinkProperty` | `GremlinBlock.Sort(ByLinked(link, prop), direction)` via `LeafNode` |
| `GetLinks` / link traversal | `GremlinBlock.OutLink` / `InLink` → `GremlinQuery.FollowLink` |
| `LinksEqualDecorator` | `GremlinQuery.NestedCondition` (see below) |
| `Wildcard`, `ConversionWildcard` | Removed — no longer needed |

The case-mode parameters shown in the master APIs above are not retained on this branch.
Remove the former `ignoreCase` argument from `findContaining`, the `NodeFactory` string factories,
and property-reference String `contains`; `MatchStringProp` no longer accepts `caseSensitive`.
Scalar predicates retain declared-schema collation and native predicate pushdown; standard DNQ
string properties use case-insensitive collation. `HasElement` compares String set elements
case-insensitively, independently of schema collation, while preserving non-String equality.

### LinksEqualDecorator → GremlinQuery.NestedCondition

In master, `LinksEqualDecorator` was a specialised `NodeBase` that expressed: *"find entities of type X where the entity linked via `linkName` satisfies sub-query `decorated`"*. It was also an optimisation hint: when `And` detected a `LinksEqualDecorator` child it substituted the naive intersection with Xodus's native `EntityIterableBase.findLinks()` index operation (which is cheaper than intersecting two full iterables). It also handled polymorphism explicitly by iterating over all subtypes of the linked entity type and unioning the results.

```kotlin
// master — KProperty1.matches() creates a LinksEqualDecorator:
LinksEqualDecorator(linkName = "contact", decorated = <sub-query>, linkEntityType = "Contact")

// And.instantiate() detects it and calls the optimised path:
((EntityIterableBase) selfInstance).findLinks(decorator.instantiateDecorated(...), decorator.getLinkName())
```

In this branch the equivalent is **`GremlinQuery.NestedCondition`**, created directly inside `KProperty1.matches()` in `NodeBaseOperations.kt`:

```kotlin
// adopt-youtrackdb — matches() creates a NestedCondition:
LeafNode(
    GremlinQuery.NestedCondition(
        structure = listOf(linkName),   // the link path to follow
        condition = subQueryCondition   // the condition to apply on the linked vertex
    )
)
```

`NestedCondition` is a `GremlinQuery.Condition` that compiles to a Gremlin `where()` clause. It folds the `structure` list into a chain of `OutLink` traversals and appends the sub-condition:

```kotlin
GremlinBlock.Where(
    structure.fold(GremlinBlock.All as GremlinBlock) { a, b -> a.andThen(GremlinBlock.OutLink(b)) }
             .andThen(condition.asBlock())
)
// → .where(__.out("<linkName>_link").<condition>)
```

Key differences from `LinksEqualDecorator`:

| Aspect | `LinksEqualDecorator` (master) | `NestedCondition` (this branch) |
|---|---|---|
| Mechanism | Calls `EntityIterableBase.findLinks()` — a dedicated Xodus index operation | Emits a Gremlin `where(__.out(...))` sub-traversal — natural to the graph model |
| Optimisation | Special-cased inside `And.instantiate()` to avoid naive intersection | No special case needed; Gremlin's graph engine handles it natively |
| Polymorphism | Explicitly unions results across all subtypes via recursive `instantiateDecorated()` | Not needed — `out(edgeLabel)` traversal returns all linked vertices regardless of type |
| Multi-hop | Only single-link | `structure: List<String>` supports a chain of links (though currently only single links are created) |

The old commented-out code in `NodeBaseOperations.kt` (`matches()`) still shows the direct swap:
```kotlin
// return LinksEqualDecorator(getDBName(entityKClass), node, ...)  // old
return LeafNode(GremlinQuery.NestedCondition(listOf(getDBName(entityKClass)), condition))  // new
```

### GremlinQuery sealed class

`GremlinQuery` assembles the final Gremlin traversal. Key subtypes:
- `Condition` (abstract) — a filter that can be combined; concrete variants `Where(block)`, `ByIds(rids)`, `NestedCondition`
- `Labeled(inner, label)` — adds `hasLabel(entityType)` to any inner query
- `AndThen(inner, block)` — appends a generic block to an inner query
- `Slice` / `SortBy` / `Order` / `ReversedOrder` / `FollowLink` — chained wrappers
- `UnionAll(subqueries)` — union of multiple independent queries

`GremlinQuery.start(gs)` returns a live `GraphTraversal<*, YTDBVertex>` that is streamed lazily through the Gremlin engine inside YouTrackDB.

---

## dnq-migrate: Data Migration Tool

`dnq-migrate` is a standalone one-shot tool for migrating data from an existing Xodus database into a new YouTrackDB database.

### Key Classes

- **`MigrateXodusToOrient`** — CLI entry point; reads configuration from system properties (source Xodus path/name/cipher, target YTDB path/name/type, batch size, validation flag).
- **`XodusToOrientDataMigratorLauncher`** — Orchestrates the full lifecycle: checks if migration is needed, opens both databases, runs the migrator, initialises the schema buddy, optionally validates, backs up or deletes the old Xodus files.
- **`XodusToOrientDataMigrator`** — Core engine. Migrates in four passes:
  1. `createVertexClassesIfAbsent()` — maps Xodus entity types to YTDB vertex classes, assigns `classId`
  2. `copyPropertiesAndBlobs()` — creates vertices, copies all properties and blobs in configurable batches; identifies which link names need edge classes
  3. `createEdgeClassesIfAbsent()` — creates `<linkName>_link` edge classes
  4. `copyLinks()` — copies all relationships as edges, deduplicating as needed

### Target prerequisites and entry points

Run migration with exclusive, offline access to both databases. Keep applications offline
until you accept the target data. Both public migration entries inspect the opened target
before migration-owned preparation or schema, record and sequence writes. They refuse any
class name outside this exact case-sensitive list:

`OIdentity`, `OSecurityPolicy`, `ORole`, `OUser`, `OFunction`, `OSchedule`, `OSequence`, `O`, `V`, `E`.

None of these classes must exist. The check reads names only. It does not check definitions,
contents or record counts. Empty application classes and subclasses also refuse.
Independently, the check explicitly reloads the engine sequence library and requires its
loaded names to be empty. It refuses failed required observations. Reload sees only the
provider session's authorized view. CLASS read denial or a read policy can silently hide
sequence records. This is not a physical storage audit or proof of an empty database.

Before migration, manually validate all standard-class contents, including records directly
in O, V and E. Validate effective application-user permissions, security policies, functions,
scheduled jobs and their effects. Requesting the admin role at creation does not bypass
persisted permissions or policies. Reassess the permitted names when upgrading the engine.

The launcher returns without target inspection when the source path is missing, empty or
contains no Xodus files. Otherwise it inspects the target before opening the Xodus environment.
It then prepares wrappers with automatic schema initialization disabled, copies through the inspected provider, initializes
the schema, optionally validates, releases resources and performs eligible cleanup.

The direct API is
`migrateDataFromXodusToOrientDb(xodus, orientProvider, entitiesPerTransaction = 10, mapPropertyName = { it })`.
It always inspects and builds its own wrappers from that provider with automatic schema initialization disabled. It accepts
no independent target store or schema buddy. Every migration write uses the inspected provider.
It closes only its owned store wrapper. The caller keeps the source and provider open.

### Ordinary-property names

The direct copier, `checkDataIsSame` and `XodusToOrientDataMigratorLauncher` accept an optional
`mapPropertyName: (String) -> String`. It defaults to identity. The function must be pure and
deterministic. Supply the same function to copy and check. The launcher forwards it to both.
Only ordinary scalar, set and string property names are mapped. Values retain their existing
conversion. Blob, link and entity-type names stay unchanged.

Callers can combine explicit mappings and rules with identity fallback:

```kotlin
val names = mapOf("oldTitle" to "title")
val mapPropertyName: (String) -> String = { name ->
    names[name] ?: if (name.startsWith("legacy_")) name.removePrefix("legacy_") else name
}
```

Checks are per entity, not global or type-wide. Two distinct ordinary source names must not
reach one target name, including another unchanged name. A renamed output must not reach a
blob name on that entity, `localEntityId`, or a name ending in `_targetEntityId`, `_blob_size`
or `_string_blob_hash`. Unchanged same-name property/blob behavior keeps blob precedence.
Names may be reused across entities. Engine name restrictions still apply.

The checker reads original source names and mapped target names. It retains identity filtering
but never filters away a renamed output. Callback exceptions and collision refusals propagate,
even when launcher validation is disabled. These checks are not a source-wide preflight.
Earlier committed target work can remain. Failure blocks source cleanup, and a nonfresh target
refuses retry. Preserve both databases and follow the recovery checklist below.

### Persisted sequences and identifier reservations

Migration reads saved Xodus sequence rows, not cached handles. It preserves application names
and current Long values, including saved caller-assigned values. A getter without a saved row
does not create a migrated counter. Unsaved cache changes are not migration input.
The pinned Xodus compressed-long encoding cannot save negative application values.

The source `<store>.entity.types.sequence` becomes `sequence_classId`.
Each retained type's `<store>.entities.sequences#<typeId>` becomes
`<Type>_sequence_localEntityId`. Reservations survive deletion of the highest entity or type.
An all-deleted retained type keeps its local reservation. A never-allocated retained type has
current -1 and receives local ID 0 on its first allocation.

Deleted-type local counters have no target equivalent. Migration also excludes exactly
`<store>.property.types.sequence`, `<store>.link.types.sequence`,
`<store>.property.custom.types.sequence` and the unprefixed `blob.handles.sequence`.
Similar-looking application names remain application counters.

Every destination must be unique under the pinned target engine's sequence-name rule,
copied locally as `name.uppercase(Locale.ROOT)`, including Unicode case expansion.
Conflicts refuse before migration schema, record or sequence writes, even when values match
or launcher validation is disabled. Dependency upgrades require compatibility checks.
Selected test cases do not promise compatibility across engine versions. Validation retains
its dependence on the engine's internal `OSequence`/`name`/`value` storage schema.

Validation reads committed target sequence rows without creating or advancing counters.
Missing counters, changed values and unexpected counters fail validation.
The supported allocator input is ordinary allocation and deletion history. Migration adds no
repair, reconstruction or clamping for manually lowered or deleted internal counters.
Native malformed-row decoding errors propagate. The source initializer k means current k-1.
The target initializer k means current k. Saved current values, not initializers, transfer.

### Lifecycle and source files

Copy and requested-validation errors reach the caller. Committed target batches can remain,
so a failed run does not imply an empty target. There is no automatic reset, repair or resume.

Validation is optional. A run with validation disabled reports `validation skipped`.
Source-file cleanup is eligible only after copy, requested validation, and resource release
succeed. `dropOldDatabaseAfterMigration=false` moves source files into `xodus_backup`.
`true` deletes them. Neither value preserves the source in place after success. Cleanup is
a separate filesystem operation and is not atomic or automatically retried.

The launcher releases its target store wrapper, source store, and separately opened source
environment. It closes the target provider only when `closeOnFinish=true`. It attempts all
releases even when one fails. A primary failure keeps its identity and later close failures
are suppressed on it. A release failure blocks source-file cleanup. The direct copier does
not own or close caller resources.

After a refusal or failure, use this recovery checklist:

1. Identify the failed stage. Copy, requested-validation or release failure means lifecycle
   cleanup did not run. Startup recovery, callbacks or external activity can still change files.
2. Preserve the source, target and any backup. Check which copies are complete before deciding
   what to do. Never blindly recreate a target after refusal or a failed run.
3. If cleanup started or was interrupted, source or backup files may be incomplete. The target
   may be the only complete copy. Preserve it, assess the files and finish cleanup manually.

### Shaded Xodus Dependency

`dnq-migrate` must read the *source* Xodus database, so it depends on Xodus. To avoid package conflicts with the rest of this project it uses **shaded** Xodus JARs (`xodus-entity-store:3.1-dev-shaded`, `xodus-environment:3.1-dev-shaded`, etc.) whose packages are renamed to `jetbrains.shaded.exodus.*`. The target YouTrackDB store uses the normal non-shaded classes.

The selected `xodus-environment:3.1-dev-shaded` JAR has SHA-256
`127e01ae9bd007586688e20fb929f8ac9e5993b19bfbdea346da17b4280f80fc`.
On this runtime, source startup recovery can truncate or delete log tail segments. Backup
creation can fail while recovery continues. Disabling restore-at-any-cost does not always
force an abort. The traced abort branch also requires more than two affected blocks.
Do not assume unconditional recoverability or byte-for-byte source preservation. These limits
come from inspection of the selected JAR. No runtime corruption fixture establishes recovery
behavior for your source.
