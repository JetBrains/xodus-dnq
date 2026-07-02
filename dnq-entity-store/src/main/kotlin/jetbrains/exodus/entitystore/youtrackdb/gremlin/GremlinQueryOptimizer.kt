/**
 * Copyright 2006 - 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.youtrackdb.gremlin

import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery.*

private fun extractLabel(q: GremlinQuery): String? = if (q is Labeled) q.label else null

// O19: extend extractCondition to handle Labeled(Labeled(Where(All), T1), T2).
//
// This double-label pattern arises when a query targets an entity that participates in a
// class hierarchy — e.g. "all TypeB vertices that are also TypeA". The outer label T2 is
// the declared return type; the inner label T1 is an additional type constraint.
// extractCondition maps the double-label case to HasLabel(T1) so that the condition
// combiners (including O11) can treat it as a plain condition.
//
// NOTE: O7 does NOT propagate the outer T2 label when the condition originates from an
// O19 double-label (see the double-label guard in O7).  Appending HasLabel(T1) to a
// FollowLink traversal and then wrapping with T2 produces two consecutive hasLabel steps;
// TinkerPop's InlineFilterStrategy merges them and YTDBHasLabelStep evaluates with anyMatch
// (OR), causing sibling types under T2 to pass incorrectly.  Those cases fall to Aggregate.
//
// Only the Where(All) inner case is handled; a non-trivial inner condition would require
// composing GremlinBlock.AndThen which is illegal in the then() dispatch path, so those
// fall through to null (Aggregate).
//
// Example (condition combiner path — not FollowLink, unaffected by double-label guard):
//   Labeled(Where(All), "User") ∩ Labeled(Labeled(Where(All), "Employee"), "User")
//   → extractCondition(right) = HasLabel("Employee")
//   → combinedCondition = Where(HasLabel("Employee")), label = "User"
//   → Labeled(Where(HasLabel("Employee")), "User")
//   → g.V().hasLabel("Employee").hasLabel("User")
private fun extractCondition(q: GremlinQuery): GremlinBlock? =
    if (q is Labeled && q.inner is Condition) q.inner.asBlock()
    else if (q is Labeled && q.inner is Labeled && q.inner.inner is Condition &&
             q.inner.inner.asBlock() is GremlinBlock.All)
        GremlinBlock.HasLabel(q.inner.label)
    else if (q is Condition) q.asBlock()
    else null

internal fun GremlinQuery.combineEfficient(
    other: GremlinQuery,
    condCombiner: ConditionCombiner,
): GremlinQuery? {
    // O3: SortBy passthrough — strip sort wrappers, combine the inner queries, re-wrap.
    //
    // The right-side sort is always irrelevant for set operations.
    // For intersect/difference the result is a filtered subset of `this`, so `this`'s sort order
    // is meaningful and is preserved by re-wrapping. For union, sorting one operand does not
    // define the sort of the combined result — strip both sorts and return unsorted.
    //
    // Example (intersect):
    //   SortBy(issues(open), byPriority) ∩ issues(critical)
    //   → SortBy(issues(open) ∩ issues(critical), byPriority)
    //   → SortBy(issues(open ∧ critical), byPriority)
    //
    // Example (union):
    //   SortBy(issues(open), byPriority) ∪ issues(critical)
    //   → issues(open) ∪ issues(critical)          ← sort stripped, result unsorted
    //   → issues(open ∨ critical)
    if (this is SortBy) {
        val otherInner = if (other is SortBy) other.inner else other
        val combined = this.inner.combineEfficient(otherInner, condCombiner) ?: return null
        return if (condCombiner is ConditionCombiner.Union) combined
               else SortBy(combined, this.sortBlock)
    }

    // Strip right-side sort and retry
    if (other is SortBy) {
        return this.combineEfficient(other.inner, condCombiner)
    }

    // O17: Order(Dedup) transparency — strip the Dedup wrapper, combine inner queries, re-wrap.
    //
    // `flatMapDistinct` wraps its FollowLink result in Order(Dedup) for correct deduplication.
    // This wrapper prevents O4/O7/O16 from matching. Strip it, attempt combination on the inner
    // query, and re-wrap the result with Dedup. If the inner combination already returns
    // Order(Dedup) (e.g. the Labeled O4 path adds Dedup itself), return as-is to avoid
    // double-wrapping.
    //
    // Example (left side is Dedup-wrapped):
    //   Dedup(issuesInProject(ENG)) ∩ issues(open)
    //   → Dedup(issuesInProject(ENG) ∩ issues(open))    ← inner hits O7
    //   → Dedup(issuesInProject(ENG).andThen(open))
    //   → g.V().hasLabel("Project").in("project_link").has("status","open").dedup()
    //
    // O17 symmetric (both sides Dedup-wrapped, intersect):
    //   Dedup(issuesInProject(ENG)) ∩ Dedup(issuesInProject(OPS))   ← two flatMapDistinct calls
    //   → Dedup(issuesInProject(ENG) ∩ issuesInProject(OPS))         ← inner hits O4 or O7
    //
    // O17 right-difference:  condition \ Dedup(FL)  ≡  condition \ FL
    //   Dedup changes cardinality but not the distinct vertex set; safe to drop for set subtraction.
    //   Example: issues(open) \ Dedup(issuesInProject(ENG))
    //            → issues(open) \ issuesInProject(ENG)   ← then O11 fires
    if (this is Order && this.orderBlock === GremlinBlock.Dedup) {
        val otherInner = if (other is Order && other.orderBlock === GremlinBlock.Dedup) other.inner else other
        val innerResult = this.inner.combineEfficient(otherInner, condCombiner)
        if (innerResult != null) {
            return if (innerResult is Order && innerResult.orderBlock === GremlinBlock.Dedup)
                innerResult  // inner path (e.g. O4 Labeled branch) already added Dedup
            else
                Order(innerResult, GremlinBlock.Dedup)
        }
    }
    // O17 symmetric: for commutative intersect, also try when only the right side is Order(Dedup).
    if (condCombiner is ConditionCombiner.Intersect &&
        other is Order && other.orderBlock === GremlinBlock.Dedup) {
        val innerResult = other.inner.combineEfficient(this, condCombiner)
        if (innerResult != null) {
            return if (innerResult is Order && innerResult.orderBlock === GremlinBlock.Dedup)
                innerResult
            else
                Order(innerResult, GremlinBlock.Dedup)
        }
    }
    // O17 right-difference: strip Dedup from the right side of a difference.
    // condition \ Dedup(FL)  ≡  condition \ FL — dedup changes cardinality but not the
    // distinct vertex set, and difference is a set operation. Safe to delegate without Dedup.
    if (condCombiner is ConditionCombiner.Difference &&
        other is Order && other.orderBlock === GremlinBlock.Dedup) {
        return this.combineEfficient(other.inner, condCombiner)
    }

    // O4: FollowLink union shortcut — merge source queries and re-wrap with the shared traversal.
    //
    // When two Labeled(FollowLink) queries share the same direction, link name, and result label,
    // their union can be expressed as a single FollowLink over the union of their source sets,
    // avoiding two separate traversals. Dedup is added because multiple source vertices can reach
    // the same target vertex via separate edges.
    //
    // Example:
    //   Labeled(FollowLink(issues(ENG), OUT, "sprint"), "Sprint")
    //   ∪ Labeled(FollowLink(issues(OPS), OUT, "sprint"), "Sprint")
    //   → Labeled(FollowLink(issues(ENG) ∪ issues(OPS), OUT, "sprint"), "Sprint").dedup()
    //   → g.V().or(hasLabel("Issue").has("project","ENG"), hasLabel("Issue").has("project","OPS"))
    //           .out("sprint_link").hasLabel("Sprint").dedup()
    //
    // O4 extension (bare FollowLink × bare FollowLink, reached via O17 delegation):
    //   Dedup(FollowLink(issues(ENG), OUT, "sprint")) ∪ Dedup(FollowLink(issues(OPS), OUT, "sprint"))
    //   → O17 strips both Dedup wrappers → FollowLink × FollowLink → merge inner sources
    //   → FollowLink(issues(ENG) ∪ issues(OPS), OUT, "sprint")   ← O17 re-wraps with Dedup
    if (this is Labeled && other is Labeled && this.label == other.label &&
        condCombiner is ConditionCombiner.Union) {
        val thisLink = this.inner as? FollowLink
        val otherLink = other.inner as? FollowLink
        if (thisLink != null && otherLink != null &&
            thisLink.direction == otherLink.direction &&
            thisLink.linkName == otherLink.linkName) {
            // Dedup is needed: multiple source vertices can reach the same target via separate edges.
            return Labeled(
                FollowLink(thisLink.inner.union(otherLink.inner), thisLink.direction, thisLink.linkName),
                this.label
            ).then(GremlinBlock.Dedup)
        }
    }

    // O4 extension: bare FollowLink × bare FollowLink.
    // Reached both directly (the union of two single-source FollowLinks over the same link) and via
    // O17 delegation (which strips Order(Dedup) from both sides). Merge the source queries and re-wrap
    // with Dedup: several source vertices can reach the same target via the link, and union has set
    // semantics, so the merged traversal must deduplicate. Without this, a target reachable from two
    // sources is returned twice. When reached via O17, its `innerResult is Order(Dedup)` guard avoids
    // double-wrapping.
    if (this is FollowLink && other is FollowLink &&
        condCombiner is ConditionCombiner.Union &&
        this.direction == other.direction &&
        this.linkName == other.linkName) {
        return FollowLink(this.inner.union(other.inner), this.direction, this.linkName)
            .then(GremlinBlock.Dedup)
    }

    val thisLabel = extractLabel(this)
    val otherLabel = extractLabel(other)

    if (thisLabel != null && otherLabel != null && thisLabel != otherLabel) {
        return null
    }

    if (this is ByIds && other is ByIds) {
        return ByIds(condCombiner.combineIds(this.ids, other.ids))
    }

    // O_B: Aggregate ∩ Labeled(Where(All), T) — strip redundant outer intersection.
    //
    // When `this` is an Aggregate and the right operand is "all vertices of type T",
    // and the left branch of the Aggregate is already labeled T, the intersection is a
    // no-op: Aggregate produces only vertices from its left query, which are already T-labeled.
    //
    // Example (two-step query):
    //   step1 = events(byId) ∩ eventsByType   → Aggregate(events_labeled_T, eventsByType)
    //                                            (forced because the two labels differ)
    //   step2 = step1 ∩ allT                   → O_B fires → step1 (redundant Aggregate dropped)
    //
    // Without O_B:
    //   step2 = Aggregate(Aggregate(events_T, eventsByType), allT)
    //   → g.V().hasLabel("T").aggregate("aggr_0").fold()
    //          .V().hasLabel("T2").aggregate("aggr_1").fold()
    //          .V().<cond>.hasLabel("T").where(within("aggr_1")).where(within("aggr_0"))
    //
    // With O_B:
    //   step2 = Aggregate(events_T, eventsByType)
    //   → g.V().<cond2>.hasLabel("T2").aggregate("aggr_0").fold()
    //          .V().<cond1>.hasLabel("T").where(within("aggr_0"))
    //
    // Applicable for Intersect only; Difference/Union semantics differ.
    // Symmetric: also handle Labeled(Where(All), T) ∩ Aggregate(left_T, ...).
    if (condCombiner is ConditionCombiner.Intersect) {
        if (this is Aggregate &&
            extractCondition(other) is GremlinBlock.All &&
            extractLabel(this.left) != null &&
            extractLabel(this.left) == extractLabel(other)) {
            return this
        }
        if (other is Aggregate &&
            extractCondition(this) is GremlinBlock.All &&
            extractLabel(other.left) != null &&
            extractLabel(other.left) == extractLabel(this)) {
            return other
        }
    }

    // O21: allOf(T) ∩ Q — type-filter rewrite (avoids the full-scan Aggregate fallback).
    //
    // Intersecting "all vertices of type T" with another query is, by definition, a type filter
    // on that query.  The identity is general (not link-specific):
    //
    //   allOf(T) ∩ Q  ≡  { x ∈ Q : isInstanceOf(x, T) }  =  Q.then(HasLabel(T))
    //   Where(All) ∩ Q ≡ Q                                  // untyped All ⇒ pure identity
    //
    // where allOf(T) = Labeled(Where(All), T) — extractCondition == All and a non-null label —
    // and bare Where(All) is the label-less identity (extractCondition == All, null label).
    //
    // Without this rule, allOf(T) ∩ Q falls to the generic Aggregate { P.within } whenever Q has
    // no extractable condition (FollowLink, Aggregate, UnionAll, Slice, Order, …).  That Aggregate
    // drives the traversal from allOf(T) — a full scan of every T vertex — and filters it by
    // membership in the folded Q set, so a type filter on a (usually small) Q is executed as a scan
    // of the entire extent of T.  Appending HasLabel(T) to Q instead keeps Q as the driving
    // traversal and reduces the type check to an inline hasLabel filter.
    //
    // Guard `extractCondition(other) == null`: when the other operand HAS an extractable condition
    // (ByIds → IdWithin, Labeled(Where) → its block, allOf itself → All), the generic condition
    // combiner at the bottom already handles allOf(T) ∩ Q correctly via `combineBlocks` (a is All
    // -> b) and does NOT fall to Aggregate.  Scoping O21 to the null-condition operand leaves those
    // paths untouched — in particular it preserves ByIds ∩ allOf(T) as Labeled(Where(IdWithin), T)
    // rather than restructuring it.
    //
    // Placement:
    //   - After O_B, which yields a strictly better result for Aggregate ∩ allOf(T): it drops the
    //     redundant hasLabel entirely rather than appending it.
    //   - Before O7/O16/O20, which deliberately skip or mishandle an All condBlock (O7's explicit
    //     `condBlock !is All` guard; O16/O20 would silently drop the T label by appending All).
    //     Handling allOf(T) here, uniformly, supersedes those edge cases.
    //
    // The label-mismatch guard above guarantees that when O21 fires, Q's label is either null or
    // equal to T, so Q.then(HasLabel(T)) never produces a double-label Labeled(Labeled(_, U), T) —
    // the OR-merge hazard documented in O19.  Mismatched typed operands stay at Aggregate.
    //
    // Example (FollowLink, previously Aggregate):
    //   Labeled(FollowLink(boards(name=b), IN, "OnBoard"), "Issue") ∩ allOf("Issue")
    //   → FollowLink(...).then(HasLabel("Issue"))   (label idempotent — Labeled.of flattens)
    //   → g.V().has("name","b").hasLabel("Board").in("OnBoard_link").hasLabel("Issue")
    if (condCombiner is ConditionCombiner.Intersect) {
        if (extractCondition(this) is GremlinBlock.All && extractCondition(other) == null) {
            val label = extractLabel(this)
            return if (label != null) other.then(GremlinBlock.HasLabel(label)) else other
        }
        if (extractCondition(other) is GremlinBlock.All && extractCondition(this) == null) {
            val label = extractLabel(other)
            return if (label != null) this.then(GremlinBlock.HasLabel(label)) else this
        }
    }

    // O7: FollowLink × Condition fusion — appends a filter predicate directly to the traversal,
    // avoiding a separate Aggregate step for FollowLink ∩ Condition and FollowLink \ Condition.
    //
    // When `this` is Labeled(FollowLink, T) or a bare FollowLink (after O17 delegation) and
    // `other` has an extractable condition, the condition is appended to the FollowLink chain.
    // For difference the condition is wrapped in Not first. For intersect the rule is symmetric
    // (either side can be the link); for difference only `this` can be the link side, because
    // "condition \ FollowLink" is not safe to inline this way (see O11 instead).
    //
    // Example (intersect, `this` is the link):
    //   Labeled(FollowLink(sprints(), OUT, "project"), "Project") ∩ projects(archived=false)
    //   → Labeled(FollowLink(sprints(), OUT, "project").andThen(archived=false), "Project")
    //   → g.V().hasLabel("Sprint").out("project_link").has("archived",false).hasLabel("Project")
    //
    // Example (difference, `this` is the link):
    //   Labeled(FollowLink(sprints(), OUT, "project"), "Project") \ projects(archived=true)
    //   → Labeled(FollowLink(sprints(), OUT, "project").andThen(not(archived=true)), "Project")
    //   → g.V().hasLabel("Sprint").out("project_link").not(has("archived",true)).hasLabel("Project")
    //
    // Example (intersect, `other` is the link — symmetric path):
    //   issues(open) ∩ Labeled(FollowLink(sprints(S1), IN, "sprint"), "Issue")
    //   → Labeled(FollowLink(sprints(S1), IN, "sprint").andThen(open), "Issue")
    //   → g.V().hasLabel("Sprint").has("name","S1").in("sprint_link").has("status","open").hasLabel("Issue")
    //
    // O19 / double-label guard: when `other` is Labeled(Labeled(Condition, T1), T2),
    // extractCondition returns HasLabel(T1).  Appending it to the FollowLink traversal would
    // produce two consecutive hasLabel steps (T1 then T2).  TinkerPop's InlineFilterStrategy
    // merges consecutive HasStep objects; YTDBHasLabelStep evaluates multiple predicates with
    // anyMatch (OR), so sibling types under T2 incorrectly pass.  We detect this by checking for
    // a non-null extraLabel and fall through to Aggregate instead.
    if (condCombiner is ConditionCombiner.Intersect || condCombiner is ConditionCombiner.Difference) {
        if (this is FollowLink || (this is Labeled && this.inner is FollowLink)) {
            val condBlock = extractCondition(other)
            // Skip when condBlock is All: appending All is a no-op that silently drops
            // the label from Labeled(Where(All), T), losing the hasLabel filter.
            if (condBlock != null && condBlock !is GremlinBlock.All) {
                val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                val base = if (this is Labeled) Labeled(this.inner.then(appended), this.label)
                           else (this as FollowLink).then(appended)
                // O19 extraLabel: when `other` is Labeled(Labeled(Condition, T1), T2), extractCondition
                // returns HasLabel(T1) and the outer T2 label is propagated here.  This produces a
                // Labeled(Labeled(FollowLink, T1), T2) query whose traversal contains two consecutive
                // hasLabel steps. TinkerPop's InlineFilterStrategy merges consecutive HasStep objects
                // into a single step; YTDBHasLabelStep then evaluates all predicates with anyMatch
                // (OR semantics), which allows sibling types under T2 (e.g. JPUsernamePasswordDetails
                // when T1=OpenIDUserDetails, T2=JPBaseUserDetails) to pass incorrectly.
                // Fall through to Aggregate whenever the double-label would be produced.
                val extraLabel = if (other is Labeled && other.inner is Labeled) extractLabel(other) else null
                if (extraLabel != null) return null
                return base
            }
        }
        if (condCombiner is ConditionCombiner.Intersect &&
            (other is FollowLink || (other is Labeled && other.inner is FollowLink))) {
            val condBlock = extractCondition(this)
            // Same All guard as above (symmetric case).
            if (condBlock != null && condBlock !is GremlinBlock.All) {
                val base = if (other is Labeled) Labeled(other.inner.then(condBlock), other.label)
                           else (other as FollowLink).then(condBlock)
                // Same double-label guard for the symmetric case (see comment above).
                val extraLabel = if (this is Labeled && this.inner is Labeled) extractLabel(this) else null
                if (extraLabel != null) return null
                return base
            }
        }
    }

    // O16: Chained O7 fusion — extends an existing AndThen(FollowLink, ...) chain with a new condition.
    //
    // When `this` is the result of a prior O7 fusion — a Labeled(AndThen(FollowLink, cond1), T)
    // or a bare AndThen(FollowLink, ...) after O17 delegation — appending another condition
    // would otherwise fall to Aggregate because `this` is no longer a plain FollowLink.
    // O16 recognises this shape and extends the existing chain instead.
    //
    // Example (two successive intersects against the same FollowLink result):
    //   Step 1 (O7):
    //     Labeled(FollowLink(sprints(), OUT, "project"), "Project") ∩ projects(archived=false)
    //     → Labeled(AndThen(FollowLink(sprints(), OUT, "project"), archived=false), "Project")
    //   Step 2 (O16 extends the chain from step 1):
    //     above ∩ projects(key="ENG")
    //     → Labeled(AndThen(FollowLink(sprints(), OUT, "project"), archived=false, key="ENG"), "Project")
    //     → g.V().hasLabel("Sprint").out("project_link").has("archived",false).has("key","ENG").hasLabel("Project")
    //
    // Without O16, step 2 would fall to Aggregate(step-1-result, projects(key="ENG")), which
    // requires materialising step-1's results before filtering.
    if (condCombiner is ConditionCombiner.Intersect || condCombiner is ConditionCombiner.Difference) {
        if (this is Labeled) {
            val andThen = this.inner as? AndThen
            if (andThen != null && andThen.inner is FollowLink) {
                val condBlock = extractCondition(other)
                if (condBlock != null) {
                    val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                    return Labeled(this.inner.then(appended), this.label)
                }
            }
        }
        // O16 extension: bare AndThen(FollowLink, ...) reached via O17 delegation
        if (this is AndThen && this.inner is FollowLink) {
            val condBlock = extractCondition(other)
            if (condBlock != null) {
                val appended = if (condCombiner is ConditionCombiner.Difference) GremlinBlock.Not.of(condBlock) else condBlock
                return this.then(appended)
            }
        }
    }

    // O20: condition ∩ UnionAll(q1, q2, ...) — optimise by branch merging or distribution.
    //
    // Safe only when no branch involves paging (Slice/Order/SortBy): pushing a condition
    // into a paged branch would change which elements survive the skip/limit step.
    //
    // O20a — fast path: all branches are FollowLink with the same direction and link name.
    //   Merge their inner sources into one FollowLink; O7 then produces a single traversal.
    //   Dedup is added because multiple source vertices can reach the same target via separate edges.
    //
    //   Example:
    //     ByIds(#10:5, #10:6) ∩ UnionAll(FollowLink(ByIds(#50:1), OUT, "project"),
    //                                      FollowLink(ByIds(#50:2), OUT, "project"))
    //     → ByIds(#10:5,#10:6) ∩ FollowLink(ByIds(#50:1,#50:2), OUT, "project")   [merge]
    //     → AndThen(FollowLink(ByIds(#50:1,#50:2), OUT, "project"), IdWithin(#10:5,#10:6)).dedup()  [O7]
    //     → g.V().hasId(#50:1,#50:2).out("project_link").hasId(#10:5,#10:6).dedup()
    //
    // O20b — fallback: heterogeneous branches — distribute the condition into each branch.
    //   A ∩ (B ∪ C) = Dedup((A ∩ B) ∪ (A ∩ C))
    //
    //   Example:
    //     issues(open) ∩ UnionAll(issuesInProject(ENG), issuesAssignedTo(Alice))
    //     → Dedup( issuesInProject(ENG).andThen(open)
    //            ∪ issuesAssignedTo(Alice).andThen(open) )   [each branch hits O7]
    //     → g.union( g.V().hasLabel("Project").has("key","ENG").in("project_link").has("status","open"),
    //                 g.V().hasLabel("User").has("name","Alice").in("assignee_link").has("status","open")
    //               ).dedup()
    if (condCombiner is ConditionCombiner.Intersect) {
        fun hasPaging(q: GremlinQuery): Boolean = q is Slice || q is SortBy || q is Order || q is ReversedOrder
        // Merge all FollowLink branches with the same direction+link into one FollowLink.
        fun mergeHomogeneousFL(union: UnionAll): FollowLink? {
            val first = union.subqueries.firstOrNull() as? FollowLink ?: return null
            if (union.subqueries.any { it !is FollowLink ||
                    it.direction != first.direction ||
                    it.linkName != first.linkName }) return null
            val mergedInner = union.subqueries.drop(1).fold(first.inner) { acc, q ->
                acc.union((q as FollowLink).inner)
            }
            return FollowLink(mergedInner, first.direction, first.linkName)
        }
        if (other is UnionAll && other.subqueries.none { hasPaging(it) }) {
            val merged = mergeHomogeneousFL(other)
            if (merged != null) {
                val result = combineEfficient(merged, condCombiner)
                if (result != null) return result.then(GremlinBlock.Dedup)
            }
            val condBlock = extractCondition(this)
            if (condBlock != null) {
                // O20b-fix: re-apply the label from `this` (if present) after distributing
                // the condition into branches. extractCondition strips the Labeled wrapper,
                // losing the hasLabel filter. Wrapping the result re-applies hasLabel after
                // the union — at a different traversal level, so TinkerPop's
                // InlineFilterStrategy won't merge it with branch-level hasLabel steps.
                // When a label is present, omit Dedup here — O17 (the caller that stripped
                // Order(Dedup) before recursing) will re-wrap the result with Dedup.
                val label = extractLabel(this)
                val branches = other.subqueries.map { it.then(condBlock) }
                return if (label != null) Labeled.of(UnionAll(branches), label)
                       else UnionAll(branches).then(GremlinBlock.Dedup)
            }
        }
        if (this is UnionAll && this.subqueries.none { hasPaging(it) }) {
            val merged = mergeHomogeneousFL(this)
            if (merged != null) {
                val result = (merged as GremlinQuery).combineEfficient(other, condCombiner)
                if (result != null) return result.then(GremlinBlock.Dedup)
            }
            val condBlock = extractCondition(other)
            if (condBlock != null) {
                // O20b-fix: same label preservation as the symmetric path above.
                val label = extractLabel(other)
                val branches = this.subqueries.map { it.then(condBlock) }
                return if (label != null) Labeled.of(UnionAll(branches), label)
                       else UnionAll(branches).then(GremlinBlock.Dedup)
            }
        }
    }

    // O11: condition OP Labeled(FollowLink(src, dir, link), T) — inverse-link predicate rewrite.
    //
    // When the right operand is a result-labeled FollowLink and the left operand has an
    // extractable condition, translate membership in the FollowLink set into an inline predicate
    // on each vertex, avoiding materialising the right-hand side into an Aggregate.
    //
    // Inverse direction:
    //   v ∈ FollowLink(src, IN,  link) iff v.out("link_link") reaches src  → step = OutLink(link)
    //   v ∈ FollowLink(src, OUT, link) iff v.in("link_link")  reaches src  → step = InLink(link)
    //
    // HasLink shortcut: when src=All and direction=IN, the predicate simplifies to a bare
    // edge-existence check (HasLink) rather than a traversal. No equivalent exists for OUT.
    //
    // Works for both Difference (produces Not) and Union (produces Or).
    //
    // Example (difference, OUT direction):
    //   projects(All) \ Labeled(FollowLink(sprints(All), OUT, "project"), "Project")
    //   inverse step: InLink("project")  (direction OUT → InLink)
    //   chain: in("project_link").hasLabel("Sprint")
    //   → projects().not(where(in("project_link").hasLabel("Sprint")))
    //   → g.V().not(__.where(__.in("project_link").hasLabel("Sprint"))).hasLabel("Project")
    //
    // Example (difference, IN direction + All src → HasLink shortcut):
    //   issues(open) \ Labeled(FollowLink(sprints(All), IN, "sprint"), "Issue")
    //   inverse step: OutLink("sprint")  (direction IN → OutLink); src=All → HasLink shortcut
    //   → issues(open).not(hasLink("sprint"))
    //   → g.V().has("status","open").not(__.out("sprint_link")).hasLabel("Issue")
    //
    // O11b: two-hop variant — src is itself Labeled(FollowLink(innerSrc, innerDir, innerLink), T).
    //
    //   Example:
    //     projects(All) \ Labeled(FollowLink(Labeled(FollowLink(issues(), OUT, "sprint"), "Sprint"),
    //                                         OUT, "project"), "Project")
    //     outer inverse: InLink("project")  (OUT → InLink)
    //     inner inverse: InLink("sprint")   (OUT → InLink)
    //     → projects().not(where(in("project_link").where(in("sprint_link").hasLabel("Issue")).hasLabel("Sprint")))
    //     → g.V().not(__.where(__.in("project_link")
    //                            .where(__.in("sprint_link").hasLabel("Issue"))
    //                            .hasLabel("Sprint"))).hasLabel("Project")
    if (condCombiner is ConditionCombiner.Difference || condCombiner is ConditionCombiner.Union) {
        val flQuery = other as? Labeled
        val flInner = flQuery?.inner as? FollowLink
        if (flInner != null) {
            val srcCondBlock = extractCondition(flInner.inner)
            if (srcCondBlock != null) {
                val srcLabel = extractLabel(flInner.inner)
                val linkStep = if (flInner.direction == LinkDirection.IN)
                    GremlinBlock.OutLink(flInner.linkName)
                else
                    GremlinBlock.InLink(flInner.linkName)
                val inversePredicate: GremlinBlock = when {
                    srcCondBlock is GremlinBlock.All && flInner.direction == LinkDirection.IN ->
                        GremlinBlock.HasLink(flInner.linkName)
                    else -> {
                        val chain = linkStep
                            .andThen(srcCondBlock)
                            .let { if (srcLabel != null) it.andThen(GremlinBlock.HasLabel(srcLabel)) else it }
                        GremlinBlock.Where(chain)
                    }
                }
                val thisCondBlock = extractCondition(this)
                if (thisCondBlock != null) {
                    val label = thisLabel ?: otherLabel
                    val combined = condCombiner.combineBlocks(thisCondBlock, inversePredicate)
                    val result = Where.of(combined)
                    return if (label != null) Labeled.of(result, label) else result
                }
            }

            // O11b: src is Labeled(FollowLink(innerSrc, innerDir, innerLink), srcLabel) —
            // a two-hop pattern. Build a nested where predicate:
            //   where(outerInvLink.where(innerInvLink.innerSrcCond.hasLabel(innerSrcLabel)).hasLabel(srcLabel))
            //
            // Inverse direction rule (same as O11):
            //   FollowLink direction IN  → inverse step = OutLink (forward was in(), reverse is out())
            //   FollowLink direction OUT → inverse step = InLink  (forward was out(), reverse is in())
            val innerFL = (flInner.inner as? Labeled)?.inner as? FollowLink
            if (innerFL != null) {
                val innerSrcCondBlock = extractCondition(innerFL.inner)
                if (innerSrcCondBlock != null) {
                    val srcLabel = flInner.inner.label
                    val innerSrcLabel = extractLabel(innerFL.inner)
                    val outerLinkStep = if (flInner.direction == LinkDirection.IN)
                        GremlinBlock.OutLink(flInner.linkName)
                    else
                        GremlinBlock.InLink(flInner.linkName)
                    val innerLinkStep = if (innerFL.direction == LinkDirection.IN)
                        GremlinBlock.OutLink(innerFL.linkName)
                    else
                        GremlinBlock.InLink(innerFL.linkName)
                    val innerChain = innerLinkStep
                        .andThen(innerSrcCondBlock)
                        .let { if (innerSrcLabel != null) it.andThen(GremlinBlock.HasLabel(innerSrcLabel)) else it }
                    val innerPredicate = GremlinBlock.Where(innerChain)
                    val outerChain = outerLinkStep
                        .andThen(innerPredicate)
                        .andThen(GremlinBlock.HasLabel(srcLabel))
                    val inversePredicate = GremlinBlock.Where(outerChain)
                    val thisCondBlock = extractCondition(this)
                    if (thisCondBlock != null) {
                        val label = thisLabel ?: otherLabel
                        val combined = condCombiner.combineBlocks(thisCondBlock, inversePredicate)
                        val result = Where.of(combined)
                        return if (label != null) Labeled.of(result, label) else result
                    }
                }
            }
        }

        // O11c/O11d: condition OP FollowLink(Labeled(src, T), dir, link) — source-labeled variant.
        //
        // Some query DSL patterns emit FollowLink(Labeled(src, T), dir, link) — the label is
        // on the source argument rather than the result. After O17 strips Dedup, `other` is a
        // bare FollowLink with no outer Labeled wrapper, so O11's `other as? Labeled` returns null.
        //
        // O11c handles the one-hop case; the inverse-link predicate logic is identical to O11.
        //
        // Example (O11c, OUT direction):
        //   projects(All) \ FollowLink(sprints(All), OUT, "project")
        //   (sprints() = Labeled(Where(All), "Sprint") is the source label)
        //   inverse step: InLink("project")  (OUT → InLink); src=All + OUT → else branch
        //   chain: in("project_link").hasLabel("Sprint")
        //   → g.V().not(__.where(__.in("project_link").hasLabel("Sprint"))).hasLabel("Project")
        //
        // Example (O11c, IN direction + All src → HasLink shortcut):
        //   issues(All) \ FollowLink(sprints(All), IN, "sprint")
        //   inverse step: OutLink("sprint"); src=All + IN → HasLink shortcut
        //   → g.V().not(__.out("sprint_link")).hasLabel("Issue")
        //
        // O11d: two-hop source-labeled variant — src is Labeled(FollowLink(innerSrc), T).
        //
        // Example (O11d):
        //   projects(All) \ FollowLink(Labeled(FollowLink(issues(), OUT, "sprint"), "Sprint"), OUT, "project")
        //   outer inverse: InLink("project"); inner inverse: InLink("sprint")
        //   → g.V().not(__.where(__.in("project_link")
        //                          .where(__.in("sprint_link").hasLabel("Issue"))
        //                          .hasLabel("Sprint"))).hasLabel("Project")
        val fl = other as? FollowLink
        if (fl != null) {
            val srcCondBlock = extractCondition(fl.inner)
            if (srcCondBlock != null) {
                val srcLabel = extractLabel(fl.inner)
                val linkStep = if (fl.direction == LinkDirection.IN)
                    GremlinBlock.OutLink(fl.linkName)
                else
                    GremlinBlock.InLink(fl.linkName)
                val inversePredicate: GremlinBlock = when {
                    srcCondBlock is GremlinBlock.All && fl.direction == LinkDirection.IN ->
                        GremlinBlock.HasLink(fl.linkName)
                    else -> {
                        val chain = linkStep
                            .andThen(srcCondBlock)
                            .let { if (srcLabel != null) it.andThen(GremlinBlock.HasLabel(srcLabel)) else it }
                        GremlinBlock.Where(chain)
                    }
                }
                val thisCondBlock = extractCondition(this)
                if (thisCondBlock != null) {
                    val label = thisLabel ?: otherLabel
                    val combined = condCombiner.combineBlocks(thisCondBlock, inversePredicate)
                    val result = Where.of(combined)
                    return if (label != null) Labeled.of(result, label) else result
                }
            }

            // O11d: two-hop bare FollowLink — src is Labeled(FollowLink(innerSrc, innerDir, innerLink), srcLabel).
            // Mirrors O11b for the source-labeled (no outer Labeled wrapper) case.
            val srcLabeled = fl.inner as? Labeled
            val innerFL = srcLabeled?.inner as? FollowLink
            if (srcLabeled != null && innerFL != null) {
                val innerSrcCondBlock = extractCondition(innerFL.inner)
                if (innerSrcCondBlock != null) {
                    val srcLabel = srcLabeled.label
                    val innerSrcLabel = extractLabel(innerFL.inner)
                    val outerLinkStep = if (fl.direction == LinkDirection.IN)
                        GremlinBlock.OutLink(fl.linkName)
                    else
                        GremlinBlock.InLink(fl.linkName)
                    val innerLinkStep = if (innerFL.direction == LinkDirection.IN)
                        GremlinBlock.OutLink(innerFL.linkName)
                    else
                        GremlinBlock.InLink(innerFL.linkName)
                    val innerChain = innerLinkStep
                        .andThen(innerSrcCondBlock)
                        .let { if (innerSrcLabel != null) it.andThen(GremlinBlock.HasLabel(innerSrcLabel)) else it }
                    val innerPredicate = GremlinBlock.Where(innerChain)
                    val outerChain = outerLinkStep
                        .andThen(innerPredicate)
                        .andThen(GremlinBlock.HasLabel(srcLabel))
                    val inversePredicate = GremlinBlock.Where(outerChain)
                    val thisCondBlock = extractCondition(this)
                    if (thisCondBlock != null) {
                        val label = thisLabel ?: otherLabel
                        val combined = condCombiner.combineBlocks(thisCondBlock, inversePredicate)
                        val result = Where.of(combined)
                        return if (label != null) Labeled.of(result, label) else result
                    }
                }
            }
        }
    }

    // O22: ByIds ∩ <condition> — keep the ids on the GraphStep (a direct `g.V(ids)` positional load)
    // and apply the other operand's condition as a residual filter, instead of letting the generic
    // combiner below fold the ids into the condition block as an IdWithin conjunct (e.g.
    // And(prop, IdWithin)). That folded form compiles to `SELECT FROM T WHERE prop AND @rid IN` — a
    // FETCH FROM CLASS scan of the whole extent + post-filter. Rooting on ByIds yields
    // `g.V(ids).<prop>.hasLabel(T)`: an O(1) positional load per id plus an inline filter.
    //
    // Intersect only — a union/difference of a by-ids set with a condition is not a single by-ids
    // load. The condition must be a real (non-All) filter: `ByIds ∩ allOf(T)` is left to the generic
    // path (rerooting it could place the residual hasLabel adjacent to a ByIds entityType hasLabel,
    // hitting the InlineFilterStrategy anyMatch(OR) merge — see O19). The double-label shape is
    // likewise skipped, for the same reason.
    if (condCombiner is ConditionCombiner.Intersect) {
        val byIds = (this as? ByIds) ?: (other as? ByIds)
        val conditionQuery = if (this is ByIds) other else this
        if (byIds != null && conditionQuery !is ByIds) {
            val condition = extractCondition(conditionQuery)
            val extraLabel = if (conditionQuery is Labeled && conditionQuery.inner is Labeled)
                extractLabel(conditionQuery) else null
            if (condition != null && condition !is GremlinBlock.All && extraLabel == null) {
                val label = extractLabel(conditionQuery)
                val rerooted = byIds.then(condition)
                return if (label != null) rerooted.then(GremlinBlock.HasLabel(label)) else rerooted
            }
        }
    }

    val thisCondition = extractCondition(this)
    val otherCondition = extractCondition(other)

    if (thisCondition == null || otherCondition == null) {
        return null
    }

    val label = thisLabel ?: otherLabel
    val combinedCondition = Where.of(condCombiner.combineBlocks(thisCondition, otherCondition))

    return if (label != null) Labeled.of(combinedCondition, label) else combinedCondition
}
