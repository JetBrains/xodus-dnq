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

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID
import jetbrains.exodus.entitystore.youtrackdb.YTDBVertexEntity
import org.apache.tinkerpop.gremlin.process.traversal.Order
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.TextP
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.values

typealias YT = GraphTraversal<*, YTDBVertex>

enum class BlockType {
    SLICE,
    ORDER,
    LINK,
    CONDITION,
    COMBINE,
    COMPOSE
}

sealed class GremlinBlock(val shortName: String, val type: BlockType) {

    abstract fun traverse(g: YT): YT

    abstract fun describe(s: StringBuilder): StringBuilder

    /**
     * Recursively simplifies this block by propagating [All] and [None] identities through
     * compound blocks ([And], [Or], [Not], [AndThen], [Where]).
     *
     * Returns the simplified block if any simplification was possible, or `null` if the block
     * is already in its simplest form. Returning `null` (rather than `this`) is deliberate:
     * it lets callers use `block.simplify() ?: block` to avoid allocating a new object when
     * nothing changed, giving structural sharing for free.
     *
     * Implementations are recursive: each compound block first simplifies its children, then
     * applies its own identity rules (e.g. `And(All, x) → x`, `Or(None, x) → x`,
     * `Not(Not(x)) → x`). A new parent node is only allocated when a child actually changed,
     * so trees that need no simplification incur zero allocations beyond O(n) method calls.
     *
     * Leaf blocks (conditions, link steps, slice/order steps) always return `null`.
     */
    open fun simplify(): GremlinBlock? = null

    // TODO: not stack safe potentially?
    fun andThen(query: GremlinBlock) =
        if (this is All) query
        else if (query is All) this
        else AndThen(this, query)

    // todo: make this private, don't expose it to the outside. query parts should be composed using GremlinQuery.
    data class AndThen(val left: GremlinBlock, val right: GremlinBlock) : GremlinBlock("andThen", BlockType.COMPOSE) {
        override fun traverse(g: YT): YT {
            val h = left.traverse(g)
            val i = right.traverse(h)
            return i
        }

        override fun describe(s: StringBuilder) = right.describe(left.describe(s).append(", THEN "))
        override fun simplify(): GremlinBlock? {
            val sl = left.simplify()
            val sr = right.simplify()
            val l = sl ?: left
            val r = sr ?: right
            return when {
                l is All -> r
                r is All -> l
                l is None -> None
                r is None -> None
                sl != null || sr != null -> AndThen(l, r)
                else -> null
            }
        }
    }

    data class Or(val operands: List<GremlinBlock>) : GremlinBlock("or", BlockType.COMBINE) {
        constructor(left: GremlinBlock, right: GremlinBlock) : this(listOf(left, right))

        override fun traverse(g: YT): YT =
            g.or(*operands.map { it.traverse(`__`.start<Any>().asYT()) }.toTypedArray())

        override fun describe(s: StringBuilder): StringBuilder {
            operands.forEachIndexed { i, op -> if (i > 0) s.append(" OR "); op.describe(s) }
            return s
        }

        override fun simplify(): GremlinBlock? {
            var changed = false
            val result = mutableListOf<GremlinBlock>()
            for (op in operands) {
                val s = op.simplify()
                val o = s ?: op
                if (s != null) changed = true
                when {
                    o is All -> return All
                    o is None -> changed = true       // drop — None is identity for Or
                    o is Or -> { result.addAll(o.operands); changed = true }  // flatten
                    else -> result.add(o)
                }
            }
            // O9: coalesce same-property PropEqual / PropWithin operands into a single PropWithin
            if (result.size >= 2) {
                val propName = when (val first = result[0]) {
                    is PropEqual -> first.property
                    is PropWithin -> first.propName
                    else -> null
                }
                if (propName != null && result.all {
                    (it is PropEqual && it.property == propName) ||
                    (it is PropWithin && it.propName == propName)
                }) {
                    return PropWithin(propName, result.flatMap {
                        when (it) {
                            is PropEqual -> listOf(it.value)
                            is PropWithin -> it.within.toList()
                            else -> emptyList()
                        }
                    })
                }
            }
            return when {
                result.isEmpty() -> None
                result.size == 1 -> result[0]
                changed -> Or(result)
                else -> null
            }
        }
    }

    data class Where(val inner: GremlinBlock) : GremlinBlock("where", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.where(inner.traverse(`__`.start<Any>().asYT()))
        override fun describe(s: StringBuilder): StringBuilder = inner.describe(s.append("WHERE "))
        override fun simplify(): GremlinBlock? {
            val si = inner.simplify()
            val i = si ?: inner
            return when (i) {
                is All -> All
                is None -> None
                else -> if (si != null) Where(i) else null
            }
        }
    }

    data class And(val operands: List<GremlinBlock>) : GremlinBlock("and", BlockType.COMBINE) {
        constructor(left: GremlinBlock, right: GremlinBlock) : this(listOf(left, right))

        override fun traverse(g: YT): YT =
            g.and(*operands.map { it.traverse(`__`.start<Any>().asYT()) }.toTypedArray())

        override fun describe(s: StringBuilder): StringBuilder {
            operands.forEachIndexed { i, op -> if (i > 0) s.append(" AND "); op.describe(s) }
            return s
        }

        override fun simplify(): GremlinBlock? {
            var changed = false
            val result = mutableListOf<GremlinBlock>()
            for (op in operands) {
                val s = op.simplify()
                val o = s ?: op
                if (s != null) changed = true
                when {
                    o is None -> return None
                    o is All -> changed = true        // drop — All is identity for And
                    o is And -> { result.addAll(o.operands); changed = true }  // flatten
                    else -> result.add(o)
                }
            }
            return when {
                result.isEmpty() -> All
                result.size == 1 -> result[0]
                changed -> And(result)
                else -> null
            }
        }
    }

    data class Not(val query: GremlinBlock) : GremlinBlock("not", BlockType.COMBINE) {
        override fun traverse(g: YT): YT = g.not(query.traverse(`__`.start<Any>().asYT()))

        override fun describe(s: StringBuilder): StringBuilder = query.describe(s.append("NOT "))
        override fun simplify(): GremlinBlock? {
            val sq = query.simplify()
            val q = sq ?: query
            return when (q) {
                is Not -> q.query
                is All -> None
                is None -> All
                else -> if (sq != null) Not(q) else null
            }
        }
    }

    data object All : GremlinBlock("all", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g
        override fun describe(s: StringBuilder): java.lang.StringBuilder = s.append("*")
    }

    data object None : GremlinBlock("none", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.discard()

        override fun describe(s: StringBuilder): StringBuilder = s.append("none")

    }

    data object Dedup : GremlinBlock("dedup", BlockType.ORDER) {
        override fun traverse(g: YT): YT = g.dedup()
        override fun describe(s: StringBuilder): StringBuilder = s.append(".dedup()")
    }

    data class HasLabel(val entityType: String) : GremlinBlock("hl", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.hasLabel(entityType).asYT()
        override fun describe(s: StringBuilder): StringBuilder = s.append(".hasLabel(").append(entityType).append(")")
    }

    data class Limit(val limit: Long) : GremlinBlock("lim", BlockType.SLICE) {
        init {
            require(limit >= 0) { "Limit must be non-negative" }
        }

        override fun traverse(g: YT): YT = g.limit(limit)
        override fun describe(s: StringBuilder): StringBuilder = s.append(".limit(").append(limit).append(")")
    }

    data class Skip(val skip: Long) : GremlinBlock("skp", BlockType.SLICE) {
        init {
            require(skip >= 0) { "Skip must be non-negative" }
        }

        override fun traverse(g: YT): YT = g.skip(skip)
        override fun describe(s: StringBuilder): StringBuilder = s.append(".skip(").append(skip).append(")")
    }

    data class Tail(val tail: Long) : GremlinBlock("tail", BlockType.SLICE) {
        init {
            require(tail >= 0) { "Skip must be non-negative" }
        }

        override fun traverse(g: YT): YT = g.tail(tail)
        override fun describe(s: StringBuilder): StringBuilder = s.append(".tail(").append(tail).append(")")
    }

    data class PropEqual(val property: String, val value: Any?) : GremlinBlock("eq", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.has(property, value)
        override fun describe(s: StringBuilder): StringBuilder = s.append(property).append("=").append(value)
    }

    data class PropNull(val property: String) : GremlinBlock("nul", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.hasNot(property)
        override fun describe(s: StringBuilder): StringBuilder = s.append(property).append(" IS NULL")
    }

    data class PropNotNull(val property: String) : GremlinBlock("nn", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.has(property)
        override fun describe(s: StringBuilder): StringBuilder = s.append(property).append(" IS NOT NULL")
    }

    data class OutLink(val linkName: String) : GremlinBlock("olnk", BlockType.LINK) {
        override fun traverse(g: YT): YT = g.out(YTDBVertexEntity.edgeClassName(linkName)).asYT()
        override fun describe(s: StringBuilder): StringBuilder = s.append(".out(").append(linkName).append(")")
    }

    data class InLink(val linkName: String) : GremlinBlock("ilnk", BlockType.LINK) {
        override fun traverse(g: YT): YT = g.`in`(YTDBVertexEntity.edgeClassName(linkName)).asYT()
        override fun describe(s: StringBuilder): StringBuilder = s.append(".in(").append(linkName).append(")")
    }

    enum class StringCompare(
        val shortName: String,
        val predicate: (String?) -> P<String>
    ) {
        Equal("eq", { P.eq(it) }),
        Prefix("prefix", { TextP.startingWith(it) }),
        Suffix("suffix", { TextP.endingWith(it) }),
        Substring("substr", { TextP.containing(it) }),
    }

    data class MatchStringProp(
        val property: String,
        val op: StringCompare,
        val matchValue: String?,
        val isCollection: Boolean,
        val caseSensitive: Boolean,
    ) : GremlinBlock("str$op", BlockType.CONDITION) {
        override fun traverse(g: YT): YT {
            val predicate = op.predicate(if (caseSensitive) matchValue else matchValue?.lowercase())

            return if (isCollection)
                g.where(
                    values<YTDBVertex, String>(property)
                        .unfold<String>()
                        .let { if (caseSensitive) it else it.toLower() }
                        .`is`(predicate)
                )
            else if (caseSensitive)
                g.has(property, predicate)
            else
                g.where(
                    values<YTDBVertex, String>(property)
                        .toLower()
                        .`is`(predicate)
                )
        }

        override fun describe(s: StringBuilder): StringBuilder =
            s.append(property).append(" ").append(op.shortName).append(" ").append(matchValue)
    }

    data class HasElement(val property: String, val value: Any) : GremlinBlock("he", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.where(
                values<YTDBVertex, Any>(property)
                    .unfold<Any>()
                    .`is`(value)
            )

        override fun describe(s: StringBuilder): StringBuilder = s.append(property).append(" hasElement ").append(value)
    }

    data class HasLinkTo(val linkName: String, val rid: RID) : GremlinBlock("hlt", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.where(
                `__`
                    .out(YTDBVertexEntity.edgeClassName(linkName))
                    .hasId(rid)
            )
                .asYT()

        override fun describe(s: StringBuilder): StringBuilder =
            s.append("hasLinkTo(").append(linkName).append(", ").append(rid).append(")")
    }

    data class HasLink(val linkName: String) : GremlinBlock("hl", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.where(`__`.out(YTDBVertexEntity.edgeClassName(linkName)))

        override fun describe(s: StringBuilder): StringBuilder =
            s.append("hasLink(").append(linkName).append(")")
    }

    data class HasNoLink(val linkName: String) : GremlinBlock("hnl", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.not(`__`.out(YTDBVertexEntity.edgeClassName(linkName)))

        override fun describe(s: StringBuilder): StringBuilder =
            s.append("hasNoLink(").append(linkName).append(")")
    }

    data class PropInRange(val propName: String, val min: Comparable<*>, val max: Comparable<*>) :
        GremlinBlock("pb", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.has(propName, P.gte(min).and(P.lte(max)))

        override fun describe(s: StringBuilder): StringBuilder =
            s.append(min).append(" <= ").append(propName).append(" <= ").append(max)

    }

    data class PropWithin(val propName: String, val within: Collection<*>) : GremlinBlock("pw", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.has(propName, P.within<Any>(within))

        override fun describe(s: StringBuilder): StringBuilder =
            s.append(propName).append(" within ").append(within)

    }

    data class IdEqual(val rid: RID) : GremlinBlock("ide", BlockType.CONDITION) {
        override fun traverse(g: YT): YT =
            g.hasId(rid)

        override fun describe(s: StringBuilder): StringBuilder =
            s.append("id=").append(rid)

    }

    data class IdWithin(val within: Collection<RID>) : GremlinBlock("idw", BlockType.CONDITION) {
        override fun traverse(g: YT): YT = g.hasId(P.within(within))

        override fun describe(s: StringBuilder): StringBuilder = s.append("id within ").append(within)

    }

    enum class SortDirection {
        ASC, DESC
    }

    // Always "nulls last", as the old xodus DNQ implementation worked like this.
    data class Sort(val by: By, val direction: SortDirection) : GremlinBlock("sb", BlockType.ORDER) {
        sealed interface By
        class ByProp(val propName: String) : By
        class ByLinked(val linkName: String, val propName: String) : By

        override fun traverse(g: YT): YT {
            val order = when (direction) {
                SortDirection.ASC -> Order.asc
                SortDirection.DESC -> Order.desc
            }

            return when (by) {
                is ByProp -> g.order()
                    .by(values<YTDBVertex, Any>(by.propName).count(), Order.desc)
                    .by(
                        `__`.values<YTDBVertex, Any>(by.propName).fold(),
                        order
                    )

                is ByLinked -> {
                    val edgeLabel = YTDBVertexEntity.edgeClassName(by.linkName)
                    g.order()
                        .by(`__`.out(edgeLabel).values<Any>(by.propName).count(), Order.desc)
                        .by(
                            `__`.out(edgeLabel)
                                .values<Any>(by.propName)
                                .fold(),
                            order
                        )
                }
            }
        }

        override fun describe(s: StringBuilder): StringBuilder =
            s.append(".sortBy(").append(by).append(", ").append(direction).append(")")

    }

    data object Reverse : GremlinBlock("rev", BlockType.ORDER) {
        override fun traverse(g: YT): YT =
            g.fold().reverse<Any>().unfold<Any>().asYT()

        override fun describe(s: StringBuilder): StringBuilder = s.append(".reverse()")
    }
}

@Suppress("UNCHECKED_CAST")
fun GraphTraversal<*, *>.asYT(): YT = this as YT