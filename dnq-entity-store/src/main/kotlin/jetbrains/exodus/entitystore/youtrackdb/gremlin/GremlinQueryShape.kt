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

/**
 * Produces a normalized shape string for a [GremlinQuery] tree.
 *
 * The shape mirrors Kotlin constructor syntax with concrete data values replaced by `?`:
 * - Names (property names, link names, entity type labels, enum values) are kept verbatim.
 * - Actual data values (property values, RIDs, numeric constants) are replaced with `?`.
 * - Child queries and blocks are rendered recursively in argument position.
 *
 * Two queries that have the same structure but differ only in concrete values produce the
 * same shape, making the shape suitable as a grouping key.
 *
 * Examples:
 * ```
 * Labeled(Where(PropEqual("status", ?)), "Issue")
 * Labeled(AndThen(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), PropEqual("status", ?)), "Issue")
 * Aggregate(Labeled(FollowLink(Labeled(Where(PropEqual("key", ?)), "Project"), IN, "project"), "Issue"), Where(PropEqual("priority", ?)))
 * ```
 */
object GremlinQueryShape {

    fun of(query: GremlinQuery): String = buildString { appendQuery(query) }

    private fun StringBuilder.appendQuery(query: GremlinQuery) {
        when (query) {
            is GremlinQuery.Where -> {
                append("Where("); appendBlock(query.block); append(")")
            }
            is GremlinQuery.ByIds -> append("ByIds(?)")
            is GremlinQuery.NestedCondition -> {
                append("NestedCondition(")
                query.structure.forEachIndexed { i, s -> if (i > 0) append(", "); append("\"$s\"") }
                append(", "); appendQuery(query.condition); append(")")
            }
            is GremlinQuery.Labeled -> {
                append("Labeled("); appendQuery(query.inner); append(", \"${query.label}\")")
            }
            is GremlinQuery.AndThen -> {
                append("AndThen("); appendQuery(query.inner); append(", "); appendBlock(query.block); append(")")
            }
            is GremlinQuery.FollowLink -> {
                append("FollowLink("); appendQuery(query.inner)
                append(", ${query.direction}, \"${query.linkName}\")")
            }
            is GremlinQuery.SortBy -> {
                append("SortBy("); appendQuery(query.inner); append(", ?)")
            }
            is GremlinQuery.Order -> {
                append("Order("); appendQuery(query.inner); append(", "); appendBlock(query.orderBlock); append(")")
            }
            is GremlinQuery.ReversedOrder -> {
                append("ReversedOrder("); appendQuery(query.inner); append(")")
            }
            is GremlinQuery.Slice -> {
                append("Slice("); appendQuery(query.inner); append(", ?)")
            }
            is GremlinQuery.UnionAll -> {
                append("UnionAll(")
                query.subqueries.forEachIndexed { i, sq -> if (i > 0) append(", "); appendQuery(sq) }
                append(")")
            }
            is GremlinQuery.Aggregate -> {
                append("Aggregate("); appendQuery(query.left); append(", "); appendQuery(query.right); append(")")
            }
            is GremlinQuery.AggregateNoOrder -> {
                append("AggregateNoOrder("); appendQuery(query.query1); append(", "); appendQuery(query.query2); append(")")
            }
        }
    }

    private fun StringBuilder.appendBlock(block: GremlinBlock) {
        when (block) {
            is GremlinBlock.AndThen -> {
                append("AndThen("); appendBlock(block.left); append(", "); appendBlock(block.right); append(")")
            }
            is GremlinBlock.Or -> {
                append("Or(")
                block.operands.forEachIndexed { i, op -> if (i > 0) append(", "); appendBlock(op) }
                append(")")
            }
            is GremlinBlock.Where -> {
                append("Where("); appendBlock(block.inner); append(")")
            }
            is GremlinBlock.And -> {
                append("And(")
                block.operands.forEachIndexed { i, op -> if (i > 0) append(", "); appendBlock(op) }
                append(")")
            }
            is GremlinBlock.Not -> {
                append("Not("); appendBlock(block.query); append(")")
            }
            GremlinBlock.All    -> append("All")
            GremlinBlock.None   -> append("None")
            GremlinBlock.Dedup  -> append("Dedup")
            GremlinBlock.Reverse -> append("Reverse")
            is GremlinBlock.HasLabel      -> append("HasLabel(\"${block.entityType}\")")
            is GremlinBlock.PropEqual     -> append("PropEqual(\"${block.property}\", ?)")
            is GremlinBlock.PropNull      -> append("PropNull(\"${block.property}\")")
            is GremlinBlock.PropNotNull   -> append("PropNotNull(\"${block.property}\")")
            is GremlinBlock.PropWithin    -> append("PropWithin(\"${block.propName}\", ?)")
            is GremlinBlock.PropInRange   -> append("PropInRange(\"${block.propName}\", ?, ?)")
            is GremlinBlock.HasLink       -> append("HasLink(\"${block.linkName}\")")
            is GremlinBlock.HasNoLink     -> append("HasNoLink(\"${block.linkName}\")")
            is GremlinBlock.HasLinkTo     -> append("HasLinkTo(\"${block.linkName}\", ?)")
            is GremlinBlock.HasElement    -> append("HasElement(\"${block.property}\", ?)")
            is GremlinBlock.MatchStringProp -> append("MatchStringProp(\"${block.property}\", ${block.op}, ?, ?, ?)")
            is GremlinBlock.OutLink       -> append("OutLink(\"${block.linkName}\")")
            is GremlinBlock.InLink        -> append("InLink(\"${block.linkName}\")")
            is GremlinBlock.IdEqual       -> append("IdEqual(?)")
            is GremlinBlock.IdWithin      -> append("IdWithin(?)")
            is GremlinBlock.Limit         -> append("Limit(?)")
            is GremlinBlock.Skip          -> append("Skip(?)")
            is GremlinBlock.Tail          -> append("Tail(?)")
            is GremlinBlock.Sort -> {
                append("Sort(")
                when (val by = block.by) {
                    is GremlinBlock.Sort.ByProp   -> append("ByProp(\"${by.propName}\")")
                    is GremlinBlock.Sort.ByLinked -> append("ByLinked(\"${by.linkName}\", \"${by.propName}\")")
                }
                append(", ${block.direction})")
            }
        }
    }
}
