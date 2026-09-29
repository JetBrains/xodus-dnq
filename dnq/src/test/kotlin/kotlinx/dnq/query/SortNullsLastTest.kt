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
import kotlinx.dnq.xdDateTimeProp
import kotlinx.dnq.xdIntProp
import kotlinx.dnq.xdLink0_1
import kotlinx.dnq.xdNullableDoubleProp
import kotlinx.dnq.xdNullableIntProp
import kotlinx.dnq.xdStringProp
import org.joda.time.DateTime
import org.junit.Test
import kotlin.reflect.KMutableProperty1

class NullsOwner(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<NullsOwner>()

    var text by xdStringProp()
    var number by xdNullableIntProp()
    var decimal by xdNullableDoubleProp()
    var date by xdDateTimeProp()
}

class NullsItem(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<NullsItem>()

    var text by xdStringProp()
    var number by xdNullableIntProp()
    var decimal by xdNullableDoubleProp()
    var date by xdDateTimeProp()
    var owner by xdLink0_1(NullsOwner)
    var group by xdIntProp()
}

/**
 * `sortedBy` always places null (absent) values after every non-null value, for both `asc = true`
 * and `asc = false`, for direct properties and properties of a linked entity (an absent link
 * counts as null), for every property type, whatever produced the query being sorted.
 *
 * Every case runs for committed and uncommitted data against these sources:
 * a plain database query, an in-memory (`asQuery`) source, a `union` of two queries and a
 * query that was already sorted and sliced (`take`), which cannot be translated to one MATCH.
 */
class SortNullsLastTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(NullsOwner, NullsItem)
    }

    private fun sources(): Map<String, XdQuery<NullsItem>> = linkedMapOf(
        "database" to NullsItem.all(),
        "in-memory" to NullsItem.all().toList().map { it.entity }.asQuery(NullsItem),
        "union" to (NullsItem.filter { it.group eq 0 } union NullsItem.filter { it.group eq 1 }),
        "after take" to NullsItem.all().sortedBy(NullsItem::group).take(1000),
    )

    /** Four distinct non-null values in [ascending] order, one property type on both entity types. */
    private class Case<V : Comparable<*>>(
        val label: String,
        val ascending: List<V>,
        val item: KMutableProperty1<NullsItem, V?>,
        val owner: KMutableProperty1<NullsOwner, V?>,
    ) {
        init {
            require(ascending.size == 4)
        }

        /** Stored values in an order unrelated to the sort order, with nulls in between. */
        val stored: List<V?> = listOf(ascending[2], null, ascending[0], null, ascending[3], ascending[1], null)

        fun expected(asc: Boolean, extraNulls: Int = 0): List<V?> =
            (if (asc) ascending else ascending.reversed()) + List(stored.count { it == null } + extraNulls) { null }
    }

    private fun <V : Comparable<*>> verifyDirect(case: Case<V>) {
        verifyUncommittedAndCommitted(
            create = {
                case.stored.forEachIndexed { index, value ->
                    NullsItem.new { case.item.set(this, value); group = index % 2 }
                }
            },
            verify = { phase ->
                sources().forEach { (source, query) ->
                    listOf(true, false).forEach { asc ->
                        assertWithMessage("$phase / $source / ${case.label} / asc=$asc")
                            .that(query.sortedBy(case.item, asc).toList().map { case.item.get(it) })
                            .containsExactlyElementsIn(case.expected(asc))
                            .inOrder()
                    }
                }
            }
        )
    }

    /** Items without an owner and items whose owner has no value both sort as null. */
    private fun <V : Comparable<*>> verifyLinked(case: Case<V>) {
        val itemsWithoutOwner = 2
        verifyUncommittedAndCommitted(
            create = {
                case.stored.forEachIndexed { index, value ->
                    val owner = NullsOwner.new { case.owner.set(this, value) }
                    NullsItem.new { this.owner = owner; group = index % 2 }
                }
                repeat(itemsWithoutOwner) { NullsItem.new { group = it % 2 } }
            },
            verify = { phase ->
                sources().forEach { (source, query) ->
                    listOf(true, false).forEach { asc ->
                        assertWithMessage("$phase / $source / ${case.label} / asc=$asc")
                            .that(
                                query.sortedBy(NullsItem::owner, case.owner, asc).toList()
                                    .map { item -> item.owner?.let { case.owner.get(it) } }
                            )
                            .containsExactlyElementsIn(case.expected(asc, extraNulls = itemsWithoutOwner))
                            .inOrder()
                    }
                }
            }
        )
    }

    private val strings = Case("string", listOf("apple", "Banana", "cherry", "Date"), NullsItem::text, NullsOwner::text)
    private val ints = Case("int", listOf(-5, 0, 7, 100), NullsItem::number, NullsOwner::number)
    private val doubles = Case("double", listOf(-1.5, 0.25, 3.0, 1000.0), NullsItem::decimal, NullsOwner::decimal)
    private val dates = Case(
        "date",
        listOf(DateTime(1_000_000L), DateTime(2_000_000L), DateTime(3_000_000L), DateTime(4_000_000L)),
        NullsItem::date,
        NullsOwner::date,
    )

    @Test
    fun `string property sorts nulls last`() = verifyDirect(strings)

    @Test
    fun `int property sorts nulls last`() = verifyDirect(ints)

    @Test
    fun `double property sorts nulls last`() = verifyDirect(doubles)

    @Test
    fun `date property sorts nulls last`() = verifyDirect(dates)

    @Test
    fun `linked string property sorts nulls last`() = verifyLinked(strings)

    @Test
    fun `linked int property sorts nulls last`() = verifyLinked(ints)

    @Test
    fun `linked double property sorts nulls last`() = verifyLinked(doubles)

    @Test
    fun `linked date property sorts nulls last`() = verifyLinked(dates)

    @Test
    fun `empty string is a value and sorts before other strings, not with nulls`() {
        verifyUncommittedAndCommitted(
            create = {
                listOf("b", "", null, "a").forEachIndexed { index, value ->
                    NullsItem.new { text = value; group = index % 2 }
                }
            },
            verify = { phase ->
                sources().forEach { (source, query) ->
                    assertWithMessage("$phase / $source / asc")
                        .that(query.sortedBy(NullsItem::text).toList().map { it.text })
                        .containsExactly("", "a", "b", null)
                        .inOrder()
                    assertWithMessage("$phase / $source / desc")
                        .that(query.sortedBy(NullsItem::text, asc = false).toList().map { it.text })
                        .containsExactly("b", "a", "", null)
                        .inOrder()
                }
            }
        )
    }

    @Test
    fun `paging across the boundary between values and nulls keeps nulls last`() {
        verifyUncommittedAndCommitted(
            create = {
                listOf(2, null, 1, null).forEach { value -> NullsItem.new { number = value } }
            },
            verify = { phase ->
                val ascending = NullsItem.all().sortedBy(NullsItem::number)
                val descending = NullsItem.all().sortedBy(NullsItem::number, asc = false)
                fun XdQuery<NullsItem>.numbers() = toList().map { it.number }

                assertWithMessage("$phase / asc first page")
                    .that(ascending.take(3).numbers()).containsExactly(1, 2, null).inOrder()
                assertWithMessage("$phase / asc middle page")
                    .that(ascending.drop(1).take(2).numbers()).containsExactly(2, null).inOrder()
                assertWithMessage("$phase / asc last page")
                    .that(ascending.drop(2).numbers()).containsExactly(null, null).inOrder()
                assertWithMessage("$phase / desc first page")
                    .that(descending.take(3).numbers()).containsExactly(2, 1, null).inOrder()
                assertWithMessage("$phase / desc middle page")
                    .that(descending.drop(1).take(2).numbers()).containsExactly(1, null).inOrder()
                assertWithMessage("$phase / desc last page")
                    .that(descending.drop(2).numbers()).containsExactly(null, null).inOrder()
            }
        )
    }

    @Test
    fun `sorting a filtered query keeps nulls last`() {
        verifyUncommittedAndCommitted(
            create = {
                listOf(3, null, 1, 2, null).forEachIndexed { index, value ->
                    NullsItem.new { number = value; text = if (index == 4) "other" else "keep" }
                }
            },
            verify = { phase ->
                val kept = NullsItem.filter { it.text eq "keep" }
                assertWithMessage("$phase / asc")
                    .that(kept.sortedBy(NullsItem::number).toList().map { it.number })
                    .containsExactly(1, 2, 3, null).inOrder()
                assertWithMessage("$phase / desc")
                    .that(kept.sortedBy(NullsItem::number, asc = false).toList().map { it.number })
                    .containsExactly(3, 2, 1, null).inOrder()
            }
        )
    }

    private class ChainedOrder(val textAsc: Boolean, val numberAsc: Boolean, val expected: List<String>)

    /**
     * Sorts with `sortedBy(number).sortedBy(text)`: `text` is the primary key and `number` breaks its
     * ties. Nulls of each key are last in that key's own direction: rows with a null `text` come after
     * all others, and within one `text` a null `number` comes after all numbers.
     */
    @Test
    fun `chained sorts keep nulls last for every key`() {
        val rows = listOf<Pair<String?, Int?>>(
            "b" to 2, "b" to null, "b" to 1, "a" to null, null to 5, null to null, null to 3, "a" to 4
        )
        val orders = listOf(
            ChainedOrder(
                true, true,
                listOf("a/4", "a/null", "b/1", "b/2", "b/null", "null/3", "null/5", "null/null")
            ),
            ChainedOrder(
                false, true,
                listOf("b/1", "b/2", "b/null", "a/4", "a/null", "null/3", "null/5", "null/null")
            ),
            ChainedOrder(
                true, false,
                listOf("a/4", "a/null", "b/2", "b/1", "b/null", "null/5", "null/3", "null/null")
            ),
            ChainedOrder(
                false, false,
                listOf("b/2", "b/1", "b/null", "a/4", "a/null", "null/5", "null/3", "null/null")
            ),
        )
        verifyUncommittedAndCommitted(
            create = {
                rows.forEachIndexed { index, (text, number) ->
                    NullsItem.new { this.text = text; this.number = number; group = index % 2 }
                }
            },
            verify = { phase ->
                sources().forEach { (source, query) ->
                    orders.forEach { order ->
                        val sorted = query
                            .sortedBy(NullsItem::number, order.numberAsc)
                            .sortedBy(NullsItem::text, order.textAsc)
                        assertWithMessage("$phase / $source / text asc=${order.textAsc}, number asc=${order.numberAsc}")
                            .that(sorted.toList().map { "${it.text}/${it.number}" })
                            .containsExactlyElementsIn(order.expected)
                            .inOrder()
                    }
                }
            }
        )
    }

    @Test
    fun `chained sorts keep nulls last when the primary key is a linked property`() {
        // (owner text, number); the owner is absent for "none" and has no text for "blank".
        val rows = listOf("y" to 7, "x" to 2, "x" to null, "none" to 1, "blank" to 3, "none" to null)
        verifyUncommittedAndCommitted(
            create = {
                rows.forEachIndexed { index, (ownerText, number) ->
                    val owner = when (ownerText) {
                        "none" -> null
                        "blank" -> NullsOwner.new { }
                        else -> NullsOwner.new { text = ownerText }
                    }
                    NullsItem.new { this.owner = owner; this.number = number; group = index % 2 }
                }
            },
            verify = { phase ->
                sources().forEach { (source, query) ->
                    fun XdQuery<NullsItem>.labels() = toList().map { "${it.owner?.text}/${it.number}" }

                    assertWithMessage("$phase / $source / owner asc, number asc")
                        .that(
                            query.sortedBy(NullsItem::number)
                                .sortedBy(NullsItem::owner, NullsOwner::text).labels()
                        )
                        .containsExactly("x/2", "x/null", "y/7", "null/1", "null/3", "null/null")
                        .inOrder()
                    assertWithMessage("$phase / $source / owner desc, number asc")
                        .that(
                            query.sortedBy(NullsItem::number)
                                .sortedBy(NullsItem::owner, NullsOwner::text, asc = false).labels()
                        )
                        .containsExactly("y/7", "x/2", "x/null", "null/1", "null/3", "null/null")
                        .inOrder()
                }
            }
        )
    }
}
