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
import jetbrains.exodus.query.NodeBase
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdLink0_1
import kotlinx.dnq.xdStringProp
import org.junit.Test

class PredicateUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<PredicateUser>()

    var name by xdStringProp()
}

class PredicateHolder(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<PredicateHolder>()

    var tag by xdStringProp()
    var target by xdLink0_1(PredicateUser)
}

/**
 * DNQ string properties are case-insensitive: every string predicate (`eq`, `ne`, `isIn`,
 * `startsWith`, `contains`) must ignore case, whatever the shape of the query it runs in.
 *
 * Each case is verified, for committed and uncommitted data, against these query shapes:
 * - `all().filter { }` on a database-backed query;
 * - the same filter on an in-memory (`asQuery`) source;
 * - the filter applied after a link traversal (`mapDistinct(link).filter { }`);
 * - the filter applied to a linked entity's property (`it.link?.prop ...`).
 */
class StringPredicateCollationTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(PredicateUser, PredicateHolder)
    }

    private class Case(
        val label: String,
        val expected: List<String?>,
        val direct: FilteringContext.(PredicateUser) -> XdSearchingNode,
        val viaLink: FilteringContext.(PredicateHolder) -> XdSearchingNode,
    )

    private fun eq(value: String, vararg expected: String?) = Case(
        "eq \"$value\"", expected.toList(),
        { it.name eq value },
        { it.target?.name eq value },
    )

    private fun ne(value: String, vararg expected: String?) = Case(
        "ne \"$value\"", expected.toList(),
        { it.name ne value },
        { it.target?.name ne value },
    )

    private fun eqNull(vararg expected: String?) = Case(
        "eq null", expected.toList(),
        { it.name eq null },
        { it.target?.name eq null },
    )

    private fun neNull(vararg expected: String?) = Case(
        "ne null", expected.toList(),
        { it.name ne null },
        { it.target?.name ne null },
    )

    private fun isIn(values: List<String?>, vararg expected: String?) = Case(
        "isIn $values", expected.toList(),
        { it.name isIn values },
        { it.target?.name isIn values },
    )

    private fun startsWith(prefix: String, vararg expected: String?) = Case(
        "startsWith \"$prefix\"", expected.toList(),
        { it.name startsWith prefix },
        { it.target?.name startsWith prefix },
    )

    private fun contains(part: String, vararg expected: String?) = Case(
        "contains \"$part\"", expected.toList(),
        { it.name contains part },
        { it.target?.name contains part },
    )

    private fun verify(names: List<String?>, vararg cases: Case) {
        verifyUncommittedAndCommitted(
            create = {
                names.forEach { name ->
                    val user = PredicateUser.new { this.name = name }
                    PredicateHolder.new { target = user }
                }
            },
            verify = { phase ->
                cases.forEach { case ->
                    val sources = linkedMapOf<String, () -> List<String?>>(
                        "database" to {
                            PredicateUser.all().filter(case.direct).toList().map { it.name }
                        },
                        "in-memory" to {
                            PredicateUser.all().toList().map { it.entity }.asQuery(PredicateUser)
                                .filter(case.direct).toList().map { it.name }
                        },
                        "after link traversal" to {
                            PredicateHolder.all().mapDistinct(PredicateHolder::target)
                                .filter(case.direct).toList().map { it.name }
                        },
                        "linked property" to {
                            PredicateHolder.all().filter(case.viaLink).toList().map { it.target?.name }
                        },
                    )
                    sources.forEach { (source, run) ->
                        assertWithMessage("$phase / $source / ${case.label}")
                            .that(run())
                            .containsExactlyElementsIn(case.expected)
                    }
                }
            }
        )
    }

    @Test
    fun `eq ignores case`() = verify(
        MIXED_CASE_NAMES,
        eq("alice", "Alice", "alice", "ALICE"),
        eq("ALICE", "Alice", "alice", "ALICE"),
        eq("aLiCe", "Alice", "alice", "ALICE"),
        eq("bob", "Bob"),
        eq("nobody"),
    )

    @Test
    fun `eq ignores case of non-ASCII letters`() = verify(
        MIXED_CASE_NAMES,
        eq("ÜNAL", "Ünal", "ünal"),
        eq("ünal", "Ünal", "ünal"),
    )

    @Test
    fun `eq does not ignore surrounding whitespace`() = verify(
        MIXED_CASE_NAMES,
        eq("alice ", "Alice "),
        eq("ALICE  "),
    )

    @Test
    fun `eq null matches only absent values`() = verify(
        MIXED_CASE_NAMES,
        eqNull(null),
    )

    @Test
    fun `ne null matches every present value`() = verify(
        MIXED_CASE_NAMES,
        neNull("Alice", "alice", "ALICE", "Alice ", "Alicia", "Bob", "bobby", "Ünal", "ünal"),
    )

    @Test
    fun `ne ignores case and keeps absent values`() = verify(
        MIXED_CASE_NAMES,
        ne("alice", "Alice ", "Alicia", "Bob", "bobby", "Ünal", "ünal", null),
        ne("ALICE", "Alice ", "Alicia", "Bob", "bobby", "Ünal", "ünal", null),
        ne("nobody", "Alice", "alice", "ALICE", "Alice ", "Alicia", "Bob", "bobby", "Ünal", "ünal", null),
    )

    @Test
    fun `isIn ignores case of every listed value`() = verify(
        MIXED_CASE_NAMES,
        isIn(listOf("ALICE", "BOB"), "Alice", "alice", "ALICE", "Bob"),
        isIn(listOf("aLiCiA"), "Alicia"),
        isIn(listOf("bob", null), "Bob", null),
        isIn(listOf("nobody")),
    )

    @Test
    fun `startsWith ignores case`() = verify(
        MIXED_CASE_NAMES,
        startsWith("ALI", "Alice", "alice", "ALICE", "Alice ", "Alicia"),
        startsWith("ali", "Alice", "alice", "ALICE", "Alice ", "Alicia"),
        startsWith("aLiC", "Alice", "alice", "ALICE", "Alice ", "Alicia"),
        startsWith("ALICE", "Alice", "alice", "ALICE", "Alice "),
        startsWith("BOB", "Bob", "bobby"),
        startsWith("nobody"),
    )

    @Test
    fun `startsWith ignores case of non-ASCII letters`() = verify(
        MIXED_CASE_NAMES,
        startsWith("ÜN", "Ünal", "ünal"),
        startsWith("ün", "Ünal", "ünal"),
    )

    @Test
    fun `startsWith matches only at the beginning`() = verify(
        MIXED_CASE_NAMES,
        startsWith("LICE"),
        startsWith("obby"),
    )

    @Test
    fun `contains ignores case`() = verify(
        MIXED_CASE_NAMES,
        contains("LIC", "Alice", "alice", "ALICE", "Alice ", "Alicia"),
        contains("lic", "Alice", "alice", "ALICE", "Alice ", "Alicia"),
        contains("ICE", "Alice", "alice", "ALICE", "Alice "),
        contains("OB", "Bob", "bobby"),
        contains("BY", "bobby"),
        contains("nobody"),
    )

    @Test
    fun `contains ignores case of non-ASCII letters`() = verify(
        MIXED_CASE_NAMES,
        contains("ÜN", "Ünal", "ünal"),
        contains("NAL", "Ünal", "ünal"),
    )

    @Test
    fun `contains keeps whitespace significant`() = verify(
        MIXED_CASE_NAMES,
        contains("ce ", "Alice "),
        contains(" ", "Alice "),
    )

    @Test
    fun `predicates combine with and and or`() = verify(
        MIXED_CASE_NAMES,
        Case(
            "(eq ALICE) or (startsWith BO)",
            listOf("Alice", "alice", "ALICE", "Bob", "bobby"),
            { (it.name eq "ALICE") or (it.name startsWith "BO") },
            { (it.target?.name eq "ALICE") or (it.target?.name startsWith "BO") },
        ),
        Case(
            "(startsWith ALI) and (contains CE)",
            listOf("Alice", "alice", "ALICE", "Alice "),
            { (it.name startsWith "ALI") and (it.name contains "CE") },
            { (it.target?.name startsWith "ALI") and (it.target?.name contains "CE") },
        ),
        Case(
            "(startsWith ALI) and (ne alice)",
            listOf("Alice ", "Alicia"),
            { (it.name startsWith "ALI") and (it.name ne "alice") },
            { (it.target?.name startsWith "ALI") and (it.target?.name ne "alice") },
        ),
    )

    @Test
    fun `empty string is a value distinct from null`() = verify(
        listOf("", "a", null),
        eq("", ""),
        ne("", "a", null),
        eqNull(null),
        neNull("", "a"),
        startsWith("", "", "a"),
        contains("", "", "a"),
    )

    /** Pattern characters of LIKE, glob and regex dialects must be matched literally. */
    @Test
    fun `special characters are matched literally`() = verify(
        SPECIAL_NAMES,
        contains("_", "a_c"),
        contains("%", "a%c"),
        contains(".", "a.c"),
        contains("*", "a*c"),
        contains("?", "a?c"),
        contains("+", "a+c"),
        contains("|", "a|c"),
        contains("$", "a\$c"),
        contains("^", "a^c"),
        contains("\\", "a\\c"),
        contains("'", "a'c"),
        contains("\"", "a\"c"),
        contains("[A]", "[a]"),
        contains("(A)", "(a)"),
        startsWith("A_", "a_c"),
        startsWith("A%", "a%c"),
        startsWith("A.", "a.c"),
        startsWith("A*", "a*c"),
        startsWith("A\\", "a\\c"),
        startsWith("[A", "[a]"),
        startsWith("(A", "(a)"),
        eq("A_C", "a_c"),
        eq("A%C", "a%c"),
        eq("A.C", "a.c"),
        eq("A*C", "a*c"),
        eq("A\\C", "a\\c"),
        eq("[A]", "[a]"),
        isIn(listOf("A_C", "A.C"), "a_c", "a.c"),
    )

    /**
     * A holder without a link has no linked entity to match, so positive predicates on the linked
     * property never match it, while their negations do. A linked entity without a name is a
     * different case: only `eq null` matches it.
     */
    @Test
    fun `linked property predicates treat an absent link as no match and its negation as a match`() {
        verifyUncommittedAndCommitted(
            create = {
                PredicateHolder.new { tag = "alice"; target = PredicateUser.new { name = "alice" } }
                PredicateHolder.new { tag = "no name"; target = PredicateUser.new { } }
                PredicateHolder.new { tag = "no link" }
            },
            verify = { phase ->
                fun tags(clause: FilteringContext.(PredicateHolder) -> XdSearchingNode) =
                    PredicateHolder.all().filter(clause).toList().map { it.tag }

                assertWithMessage("$phase / eq").that(tags { it.target?.name eq "ALICE" }).containsExactly("alice")
                assertWithMessage("$phase / eq null").that(tags { it.target?.name eq null }).containsExactly("no name")
                assertWithMessage("$phase / ne")
                    .that(tags { it.target?.name ne "ALICE" }).containsExactly("no name", "no link")
                assertWithMessage("$phase / startsWith")
                    .that(tags { it.target?.name startsWith "AL" }).containsExactly("alice")
                assertWithMessage("$phase / startsWith empty")
                    .that(tags { it.target?.name startsWith "" }).containsExactly("alice")
                assertWithMessage("$phase / contains")
                    .that(tags { it.target?.name contains "LIC" }).containsExactly("alice")
                assertWithMessage("$phase / isIn")
                    .that(tags { it.target?.name isIn listOf("ALICE") }).containsExactly("alice")
            }
        )
    }

    @Test
    fun `legacy property operators ignore case`() {
        verifyUncommittedAndCommitted(
            create = {
                MIXED_CASE_NAMES.forEach { name ->
                    val user = PredicateUser.new { this.name = name }
                    PredicateHolder.new { target = user }
                }
            },
            verify = { phase ->
                val sources = linkedMapOf<String, () -> XdQuery<PredicateUser>>(
                    "database" to { PredicateUser.all() },
                    "in-memory" to { PredicateUser.all().toList().map { it.entity }.asQuery(PredicateUser) },
                )
                sources.forEach { (source, all) ->
                    fun names(node: NodeBase) = all().query(node).toList().map { it.name }

                    assertWithMessage("$phase / $source / eq")
                        .that(names(PredicateUser::name eq "ALICE"))
                        .containsExactly("Alice", "alice", "ALICE")
                    assertWithMessage("$phase / $source / ne")
                        .that(names(PredicateUser::name ne "ALICE"))
                        .containsExactly("Alice ", "Alicia", "Bob", "bobby", "Ünal", "ünal", null)
                    assertWithMessage("$phase / $source / startsWith")
                        .that(names(PredicateUser::name startsWith "BO"))
                        .containsExactly("Bob", "bobby")
                    assertWithMessage("$phase / $source / contains")
                        .that(names(PredicateUser::name.contains("LIC")))
                        .containsExactly("Alice", "alice", "ALICE", "Alice ", "Alicia")
                    assertWithMessage("$phase / $source / inValues")
                        .that(names(PredicateUser::name inValues listOf("ALICE", "BOB")))
                        .containsExactly("Alice", "alice", "ALICE", "Bob")
                    assertWithMessage("$phase / $source / not startsWith")
                        .that(names(not(PredicateUser::name startsWith "ALI")))
                        .containsExactly("Bob", "bobby", "Ünal", "ünal", null)
                    assertWithMessage("$phase / $source / not contains")
                        .that(names(not(PredicateUser::name.contains("LIC"))))
                        .containsExactly("Bob", "bobby", "Ünal", "ünal", null)
                    assertWithMessage("$phase / $source / not inValues")
                        .that(names(not(PredicateUser::name inValues listOf("ALICE", "BOB"))))
                        .containsExactly("Alice ", "Alicia", "bobby", "Ünal", "ünal", null)
                }
                assertWithMessage("$phase / matches on a link")
                    .that(
                        PredicateHolder.query(PredicateHolder::target.matches(PredicateUser::name eq "ALICE"))
                            .toList().map { it.target?.name }
                    )
                    .containsExactly("Alice", "alice", "ALICE")
            }
        )
    }

    companion object {
        private val MIXED_CASE_NAMES: List<String?> = listOf(
            "Alice", "alice", "ALICE", "Alice ", "Alicia", "Bob", "bobby", "Ünal", "ünal", null
        )

        private val SPECIAL_NAMES: List<String?> = listOf(
            "abc", "a_c", "a%c", "a.c", "a*c", "a?c", "a+c", "a|c", "a\$c", "a^c",
            "a\\c", "a'c", "a\"c", "[a]", "(a)"
        )
    }
}
