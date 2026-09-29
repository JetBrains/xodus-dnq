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
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertWithMessage
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdIntProp
import kotlinx.dnq.xdLink0_1
import kotlinx.dnq.xdStringProp
import org.junit.Ignore
import org.junit.Test

class OrderPerson(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<OrderPerson>()

    var name by xdStringProp()
    var nickname by xdStringProp()
    var group by xdIntProp()
}

class OrderItem(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<OrderItem>()

    var person by xdLink0_1(OrderPerson)
    var group by xdIntProp()
}

/**
 * `sortedBy` on a string property always orders case-insensitively, in both directions, for
 * direct and linked properties, whatever produced the query being sorted.
 *
 * Every case runs for committed and uncommitted data against these sources:
 * a plain database query, an in-memory (`asQuery`) source, a `union` of two queries and a
 * query that was already sorted and sliced (`take`), which cannot be translated to one MATCH.
 */
class StringSortCollationTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(OrderPerson, OrderItem)
    }

    private fun people(): Map<String, XdQuery<OrderPerson>> = linkedMapOf(
        "database" to OrderPerson.all(),
        "in-memory" to OrderPerson.all().toList().map { it.entity }.asQuery(OrderPerson),
        "union" to (OrderPerson.filter { it.group eq 0 } union OrderPerson.filter { it.group eq 1 }),
        "after take" to OrderPerson.all().sortedBy(OrderPerson::group).take(1000),
    )

    private fun items(): Map<String, XdQuery<OrderItem>> = linkedMapOf(
        "database" to OrderItem.all(),
        "in-memory" to OrderItem.all().toList().map { it.entity }.asQuery(OrderItem),
        "union" to (OrderItem.filter { it.group eq 0 } union OrderItem.filter { it.group eq 1 }),
        "after take" to OrderItem.all().sortedBy(OrderItem::group).take(1000),
    )

    /**
     * Sorts people by `name` in both directions. [expectedAscending] is the case-insensitive
     * ascending order; descending is its reverse. [normalize] makes values that are equal
     * ignoring case interchangeable, since their relative order is unspecified.
     */
    private fun verifyDirect(
        names: List<String>,
        expectedAscending: List<String>,
        normalize: (String) -> String = { it },
    ) {
        verifyUncommittedAndCommitted(
            create = {
                names.forEachIndexed { index, name -> OrderPerson.new { this.name = name; group = index % 2 } }
            },
            verify = { phase ->
                people().forEach { (source, query) ->
                    listOf(true, false).forEach { asc ->
                        val expected = if (asc) expectedAscending else expectedAscending.reversed()
                        assertWithMessage("$phase / $source / asc=$asc")
                            .that(query.sortedBy(OrderPerson::name, asc).toList().map { normalize(it.name!!) })
                            .containsExactlyElementsIn(expected.map(normalize))
                            .inOrder()
                    }
                }
            }
        )
    }

    /** Same as [verifyDirect], for `sortedBy(link, property)`: each item links to a person named so. */
    private fun verifyLinked(
        names: List<String>,
        expectedAscending: List<String>,
        normalize: (String) -> String = { it },
    ) {
        verifyUncommittedAndCommitted(
            create = {
                names.forEachIndexed { index, name ->
                    val person = OrderPerson.new { this.name = name }
                    OrderItem.new { this.person = person; group = index % 2 }
                }
            },
            verify = { phase ->
                items().forEach { (source, query) ->
                    listOf(true, false).forEach { asc ->
                        val expected = if (asc) expectedAscending else expectedAscending.reversed()
                        assertWithMessage("$phase / $source / asc=$asc")
                            .that(
                                query.sortedBy(OrderItem::person, OrderPerson::name, asc).toList()
                                    .map { normalize(it.person!!.name!!) }
                            )
                            .containsExactlyElementsIn(expected.map(normalize))
                            .inOrder()
                    }
                }
            }
        )
    }

    @Test
    fun `direct sort orders mixed case letters ignoring case`() =
        // Case-sensitive order would be A D b c e.
        verifyDirect(listOf("c", "A", "e", "D", "b"), listOf("A", "b", "c", "D", "e"))

    @Test
    fun `linked sort orders mixed case letters ignoring case`() =
        verifyLinked(listOf("c", "A", "e", "D", "b"), listOf("A", "b", "c", "D", "e"))

    @Test
    fun `direct sort compares whole values ignoring case`() =
        // Case-sensitive order would be APPLESAUCE, Apple pie, Banana, apple, apples.
        verifyDirect(
            listOf("Banana", "APPLESAUCE", "apples", "apple", "Apple pie"),
            listOf("apple", "Apple pie", "apples", "APPLESAUCE", "Banana"),
        )

    @Test
    fun `linked sort compares whole values ignoring case`() =
        verifyLinked(
            listOf("Banana", "APPLESAUCE", "apples", "apple", "Apple pie"),
            listOf("apple", "Apple pie", "apples", "APPLESAUCE", "Banana"),
        )

    @Test
    fun `direct sort places digits before letters of either case`() =
        verifyDirect(listOf("b", "10", "A", "9", "C"), listOf("10", "9", "A", "b", "C"))

    @Test
    fun `linked sort places digits before letters of either case`() =
        verifyLinked(listOf("b", "10", "A", "9", "C"), listOf("10", "9", "A", "b", "C"))

    @Test
    fun `direct sort orders accented letters after latin letters ignoring case`() =
        // Case-sensitive order would put "Zebra" before "apple".
        verifyDirect(
            listOf("Ärger", "Zebra", "banana", "apple"),
            listOf("apple", "banana", "Zebra", "Ärger"),
        )

    @Test
    fun `linked sort orders accented letters after latin letters ignoring case`() =
        verifyLinked(
            listOf("Ärger", "Zebra", "banana", "apple"),
            listOf("apple", "banana", "Zebra", "Ärger"),
        )

    @Test
    fun `direct sort treats values differing only in case as equal`() =
        verifyDirect(
            listOf("b", "B", "a", "A", "c"),
            listOf("a", "a", "b", "b", "c"),
            normalize = String::lowercase,
        )

    @Test
    fun `linked sort treats values differing only in case as equal`() =
        verifyLinked(
            listOf("b", "B", "a", "A", "c"),
            listOf("a", "a", "b", "b", "c"),
            normalize = String::lowercase,
        )

    /**
     * `_` sorts between `Z` and `a` by code point. Database ordering lowercases the values, so
     * `_` precedes every letter; the in-memory ordering must agree with the database one.
     */
    @Test
    fun `in-memory and database orders agree on punctuation between upper and lower case letters`() =
        verifyDirect(listOf("Zed", "alpha", "_x", "Beta"), listOf("_x", "alpha", "Beta", "Zed"))

    @Test
    fun `sorting a filtered query orders ignoring case`() {
        verifyUncommittedAndCommitted(
            create = {
                listOf("b", "Ad", "AB", "ac", "c").forEach { name -> OrderPerson.new { this.name = name } }
            },
            verify = { phase ->
                val filtered = OrderPerson.filter { it.name startsWith "a" }
                assertWithMessage("$phase / asc")
                    .that(filtered.sortedBy(OrderPerson::name).toList().map { it.name })
                    .containsExactly("AB", "ac", "Ad")
                    .inOrder()
                assertWithMessage("$phase / desc")
                    .that(filtered.sortedBy(OrderPerson::name, asc = false).toList().map { it.name })
                    .containsExactly("Ad", "ac", "AB")
                    .inOrder()
            }
        )
    }

    @Test
    fun `paging a sorted query slices the case-insensitive order`() {
        verifyUncommittedAndCommitted(
            create = {
                // Case-sensitive order would be A D b c e, so every page below differs from it.
                listOf("e", "b", "D", "A", "c").forEach { name -> OrderPerson.new { this.name = name } }
            },
            verify = { phase ->
                val ascending = OrderPerson.all().sortedBy(OrderPerson::name)
                val descending = OrderPerson.all().sortedBy(OrderPerson::name, asc = false)
                assertWithMessage("$phase / asc page")
                    .that(ascending.drop(1).take(3).toList().map { it.name })
                    .containsExactly("b", "c", "D")
                    .inOrder()
                assertWithMessage("$phase / desc page")
                    .that(descending.drop(1).take(3).toList().map { it.name })
                    .containsExactly("D", "c", "b")
                    .inOrder()
                assertWithMessage("$phase / asc first")
                    .that(ascending.take(2).toList().map { it.name })
                    .containsExactly("A", "b")
                    .inOrder()
            }
        )
    }

    private class ChainedOrder(val nameAsc: Boolean, val nicknameAsc: Boolean, val expected: List<String>)

    /**
     * Sorts people with `sortedBy(nickname).sortedBy(name)`: the later `sortedBy` is the primary key
     * and the earlier one breaks its ties. Runs against the sources named in [sourceNames].
     */
    private fun verifyChained(
        rows: List<Pair<String, String>>,
        sourceNames: Set<String>,
        vararg orders: ChainedOrder,
    ) {
        verifyUncommittedAndCommitted(
            create = {
                rows.forEachIndexed { index, (name, nickname) ->
                    OrderPerson.new { this.name = name; this.nickname = nickname; group = index % 2 }
                }
            },
            verify = { phase ->
                people().filterKeys { it in sourceNames }.forEach { (source, query) ->
                    orders.forEach { order ->
                        val sorted = query
                            .sortedBy(OrderPerson::nickname, order.nicknameAsc)
                            .sortedBy(OrderPerson::name, order.nameAsc)
                        assertWithMessage(
                            "$phase / $source / name asc=${order.nameAsc}, nickname asc=${order.nicknameAsc}"
                        )
                            .that(sorted.toList().map { "${it.name}/${it.nickname}" })
                            .containsExactlyElementsIn(order.expected)
                            .inOrder()
                    }
                }
            }
        )
    }

    /** Primary values are identical or differ by more than case; the secondary key is compared ignoring case. */
    @Test
    fun `chained sorts compare every key ignoring case`() = verifyChained(
        // Case-sensitive order would put "Bob" before "alice" and "Delta" before "alpha".
        rows = listOf("Bob" to "Delta", "Bob" to "alpha", "alice" to "Gamma", "alice" to "beta"),
        sourceNames = setOf("database", "in-memory", "union", "after take"),
        ChainedOrder(true, true, listOf("alice/beta", "alice/Gamma", "Bob/alpha", "Bob/Delta")),
        ChainedOrder(false, true, listOf("Bob/alpha", "Bob/Delta", "alice/beta", "alice/Gamma")),
        ChainedOrder(true, false, listOf("alice/Gamma", "alice/beta", "Bob/Delta", "Bob/alpha")),
        ChainedOrder(false, false, listOf("Bob/Delta", "Bob/alpha", "alice/Gamma", "alice/beta")),
    )

    /** Primary values that differ only in case are equal, so the secondary key decides their order. */
    @Test
    fun `chained in-memory sorts fall through to the next key when primary values differ only in case`() =
        verifyChained(
            rows = listOf("Bob" to "zed", "bob" to "Alpha", "ALICE" to "beta", "alice" to "Gamma"),
            sourceNames = setOf("in-memory"),
            ChainedOrder(true, true, listOf("ALICE/beta", "alice/Gamma", "bob/Alpha", "Bob/zed")),
            ChainedOrder(false, true, listOf("bob/Alpha", "Bob/zed", "ALICE/beta", "alice/Gamma")),
            ChainedOrder(true, false, listOf("alice/Gamma", "ALICE/beta", "Bob/zed", "bob/Alpha")),
            ChainedOrder(false, false, listOf("Bob/zed", "bob/Alpha", "alice/Gamma", "ALICE/beta")),
        )

    /**
     * Fails today: the database ordering splits primary values that differ only in case ("Bob" before
     * "bob" ascending, the reverse descending) before it consults the next key, so the secondary
     * key and its direction have no effect on those rows. The Gremlin traversal is a plain
     * `order().by("name").by("nickname")`, so the case-sensitive tie-break is inferred to come from
     * YouTrackDB's native collation-aware ordering.
     */
    @Ignore("database sort ignores the next key when primary values differ only in case")
    @Test
    fun `chained database sorts fall through to the next key when primary values differ only in case`() =
        verifyChained(
            rows = listOf("Bob" to "zed", "bob" to "Alpha", "ALICE" to "beta", "alice" to "Gamma"),
            sourceNames = setOf("database", "union", "after take"),
            ChainedOrder(true, true, listOf("ALICE/beta", "alice/Gamma", "bob/Alpha", "Bob/zed")),
            ChainedOrder(false, true, listOf("bob/Alpha", "Bob/zed", "ALICE/beta", "alice/Gamma")),
            ChainedOrder(true, false, listOf("alice/Gamma", "ALICE/beta", "Bob/zed", "bob/Alpha")),
            ChainedOrder(false, false, listOf("Bob/zed", "bob/Alpha", "alice/Gamma", "ALICE/beta")),
        )

    /**
     * Linked keys as primary key: persons that differ only in case are equal, so the next key
     * (`group`) decides their order in both directions.
     */
    @Test
    fun `chained sorts with a linked primary key compare it ignoring case`() {
        // (person name, group); "Bob"/"bob" and "ALICE"/"alice" tie on the linked key.
        val rows = listOf("Bob" to 1, "bob" to 0, "ALICE" to 1, "alice" to 0)
        val orders = listOf(
            Triple(true, true, listOf("alice/0", "ALICE/1", "bob/0", "Bob/1")),
            Triple(false, true, listOf("bob/0", "Bob/1", "alice/0", "ALICE/1")),
            Triple(true, false, listOf("ALICE/1", "alice/0", "Bob/1", "bob/0")),
            Triple(false, false, listOf("Bob/1", "bob/0", "ALICE/1", "alice/0")),
        )
        verifyUncommittedAndCommitted(
            create = {
                rows.forEach { (name, group) ->
                    val person = OrderPerson.new { this.name = name }
                    OrderItem.new { this.person = person; this.group = group }
                }
            },
            verify = { phase ->
                items().forEach { (source, query) ->
                    orders.forEach { (personAsc, groupAsc, expected) ->
                        val sorted = query
                            .sortedBy(OrderItem::group, groupAsc)
                            .sortedBy(OrderItem::person, OrderPerson::name, personAsc)
                        assertWithMessage("$phase / $source / person asc=$personAsc, group asc=$groupAsc")
                            .that(sorted.toList().map { "${it.person!!.name}/${it.group}" })
                            .containsExactlyElementsIn(expected)
                            .inOrder()
                    }
                }
            }
        )
    }
}
