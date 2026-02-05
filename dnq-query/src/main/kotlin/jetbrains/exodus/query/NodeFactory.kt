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
package jetbrains.exodus.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.YTDBEntityId
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinBlock.*
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQuery
import java.util.stream.Collectors
import java.util.stream.StreamSupport


object NodeFactory {

    @JvmStatic
    fun all(): LeafNode = LeafNode(All)

    @JvmStatic
    fun none(): LeafNode = LeafNode(None)

    @JvmStatic
    fun propEqual(property: String, value: Comparable<*>?): LeafNode =
        LeafNode(
            if (value == null) PropNull((property))
            else PropEqual(property, value)
        )

    @JvmStatic
    fun propNull(property: String): LeafNode =
        LeafNode(PropNull(property))

    @JvmStatic
    fun propNotNull(property: String): LeafNode =
        LeafNode(PropNotNull(property))

    @JvmStatic
    fun hasSubstring(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode = LeafNode(
        MatchStringProp(
            property,
            StringCompare.Substring,
            value,
            isCollection = false,
            caseSensitive = !ignoreCase
        )
    )

    @JvmStatic
    fun hasElementWithSubstring(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode = LeafNode(
        MatchStringProp(
            property,
            StringCompare.Substring,
            value,
            isCollection = true,
            caseSensitive = !ignoreCase
        )
    )

    @JvmStatic
    fun hasPrefix(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode = LeafNode(
        MatchStringProp(
            property,
            StringCompare.Prefix,
            value,
            isCollection = false,
            caseSensitive = !ignoreCase
        )
    )

    @JvmStatic
    fun hasElementWithPrefix(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode =
        LeafNode(
            MatchStringProp(
                property,
                StringCompare.Prefix,
                value,
                isCollection = true,
                caseSensitive = !ignoreCase
            )
        )

    @JvmStatic
    fun stringPropEqual(property: String, value: String?, ignoreCase: Boolean = true): LeafNode =
        if (value == null) LeafNode(PropNull(property))
        else LeafNode(
            MatchStringProp(
                property,
                StringCompare.Equal,
                value,
                isCollection = false,
                caseSensitive = !ignoreCase
            )
        )

    @JvmStatic
    fun hasStringElement(property: String, value: String?, ignoreCase: Boolean = true): LeafNode =
        LeafNode(
            MatchStringProp(
                property,
                StringCompare.Equal,
                value,
                isCollection = true,
                caseSensitive = !ignoreCase
            )
        )

    @JvmStatic
    fun hasElement(property: String, value: Any): LeafNode =
        LeafNode(HasElement(property, value))

    @JvmStatic
    fun hasLinkTo(linkName: String, entity: Entity?) =
        if (entity == null) hasNoLink(linkName)
        else LeafNode(HasLinkTo(linkName, (entity.id as YTDBEntityId).asOId()))

    @JvmStatic
    fun hasNoLink(linkName: String) = LeafNode(HasNoLink(linkName))

    @JvmStatic
    fun hasLink(linkName: String) = LeafNode(HasLink(linkName))

    @JvmStatic
    fun inRange(property: String, min: Comparable<*>, max: Comparable<*>) =
        LeafNode(PropInRange(property, min, max))

    @JvmStatic
    fun or(left: NodeBase, right: NodeBase): BinaryNode =
        BinaryNode(
            left, right, true, "or",
            // todo: is it valid to use "union", which returns Set here?
            ::Or, Iterable<Entity>::union
        )

    @JvmStatic
    fun and(left: NodeBase, right: NodeBase): BinaryNode =
        BinaryNode(
            left, right, true, "and",
            ::And, ::intersectTwoIts
        )

    @JvmStatic
    fun combine(first: NodeBase, second: NodeBase): BinaryNode =
        BinaryNode(
            first, second, commutative = false, "andThen",
            ::AndThen
        )

    @JvmStatic
    fun not(nodeBase: NodeBase): UnaryNode =
        UnaryNode(nodeBase, "not", ::Not)

    /** Create a nested version of a node base representing a condition. If the query inside the node base
     * is not a [GremlinQuery.Condition], then an IllegalArgumentException is thrown */
    @JvmStatic
    fun nested(linkName: String, nodeBase: NodeBase): LeafNode =
        (nodeBase.query as? GremlinQuery.Condition)?.let { condition ->
            val newNesting = listOf(linkName)
            when (condition) {
                is GremlinQuery.NestedCondition -> LeafNode(
                    GremlinQuery.NestedCondition(
                        newNesting + condition.structure,
                        condition.condition
                    )
                )

                else -> LeafNode(GremlinQuery.NestedCondition(newNesting, condition))
            }
        } ?: throw IllegalArgumentException("Only Condition instances can be used in the chain")

    @JvmStatic
    fun sortBy(propName: String, direction: SortDirection) =
        LeafNode(Sort(Sort.ByProp(propName), direction))

    @JvmStatic
    fun sortByLinked(linkName: String, propName: String, direction: SortDirection) =
        LeafNode(Sort(Sort.ByLinked(linkName, propName), direction))

    private fun intersectTwoIts(it1: Iterable<Entity>, it2: Iterable<Entity>): Iterable<Entity> {
        val s1 = StreamSupport.stream(it1.spliterator(), false)
        val s2 = StreamSupport.stream(it2.spliterator(), false).collect(Collectors.toSet())
        return s1.filter(s2::contains).collect(Collectors.toList())
    }
}
