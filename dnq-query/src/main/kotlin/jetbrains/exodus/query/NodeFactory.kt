/**
 * Copyright 2006 - 2025 JetBrains s.r.o.
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
import java.util.stream.Collectors
import java.util.stream.StreamSupport


object NodeFactory {

    @JvmStatic
    fun all(): LeafNode = LeafNode(All)

    fun propEqual(property: String, value: Comparable<*>?): LeafNode =
        LeafNode(
            if (value == null) PropNull((property))
            else PropEqual(property, value)
        )

    fun propNull(property: String): LeafNode =
        LeafNode(PropNull(property))

    fun propNotNull(property: String): LeafNode =
        LeafNode(PropNotNull(property))

    fun hasSubstring(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode =
        LeafNode(HasSubstring(property, value, isCollection = false, caseSensitive = !ignoreCase))

    fun hasElementWithSubstring(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode =
        LeafNode(HasSubstring(property, value, isCollection = true, caseSensitive = !ignoreCase))

    fun hasPrefix(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode =
        LeafNode(HasPrefix(property, value, isCollection = false, caseSensitive = !ignoreCase))

    fun hasElementWithPrefix(
        property: String,
        value: String?,
        ignoreCase: Boolean = true
    ): LeafNode =
        LeafNode(HasPrefix(property, value, isCollection = true, caseSensitive = !ignoreCase))

    fun hasElement(property: String, value: Any): LeafNode =
        LeafNode(HasElement(property, value))

    fun hasLinkTo(linkName: String, entity: Entity?) =
        if (entity == null) hasNoLink(linkName)
        else LeafNode(HasLinkTo(linkName, (entity.id as YTDBEntityId).asOId()))

    fun hasNoLink(linkName: String) = LeafNode(HasNoLink(linkName))
    fun hasLink(linkName: String) = LeafNode(HasLink(linkName))

    fun inRange(property: String, min: Comparable<*>, max: Comparable<*>) =
        LeafNode(PropInRange(property, min, max))

    fun or(left: NodeBase, right: NodeBase): BinaryNode =
        BinaryNode(
            left, right, true, "or",
            // todo: is it valid to use "union", which returns Set here?
            ::Or, Iterable<Entity>::union
        )

    fun and(left: NodeBase, right: NodeBase): BinaryNode =
        BinaryNode(
            left, right, true, "and",
            ::And, ::intersectTwoIts
        )

    fun combine(first: NodeBase, second: NodeBase): BinaryNode =
        BinaryNode(
            first, second, commutative = false, "andThen",
            ::AndThen
        )

    fun not(nodeBase: NodeBase): UnaryNode =
        UnaryNode(nodeBase, "not", ::Not)

    fun sortBy(propName: String, direction: SortDirection) =
        LeafNode(Sort(Sort.ByProp(propName), direction))

    fun sortByLinked(linkName: String, propName: String, direction: SortDirection) =
        LeafNode(Sort(Sort.ByLinked(linkName, propName), direction))

    private fun intersectTwoIts(it1: Iterable<Entity>, it2: Iterable<Entity>): Iterable<Entity> {
        val s1 = StreamSupport.stream(it1.spliterator(), false)
        val s2 = StreamSupport.stream(it2.spliterator(), false).collect(Collectors.toSet())
        return s1.filter(s2::contains).collect(Collectors.toList())
    }
}
