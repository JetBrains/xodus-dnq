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

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.youtrackdb.gremlin.GremlinQueryShape
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdModel
import kotlinx.dnq.XdNaturalEntityType
import kotlinx.dnq.xdLink0_1
import kotlinx.dnq.xdRequiredStringProp
import org.junit.Test

class StringCollationUser(entity: Entity) : XdEntity(entity) {
    companion object : XdNaturalEntityType<StringCollationUser>("User")

    var name by xdRequiredStringProp()
    var superviser by xdLink0_1(StringCollationUser)
}

class StringCollationQueryTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(StringCollationUser)
    }

    @Test
    fun `link traversal respects string property collation`() {
        store.transactional {
            val targetNames = listOf("Lev", "lev", "leV")
            targetNames.forEach { targetName ->
                val target = StringCollationUser.new { name = targetName }
                StringCollationUser.new {
                    name = "source-$targetName"
                    superviser = target
                }
            }

            val query = StringCollationUser.all()
                .mapDistinct(StringCollationUser::superviser)
                .filter { it.name eq "Lev" }

            val shape = GremlinQueryShape.of((query.entityIterable as YTDBEntityIterable).query)
            assertThat(shape).isEqualTo(
                """Dedup(Labeled(AndThen(FollowLink(Labeled(Where(All), "User"), OUT, "superviser"), PropEqual("name", ?)), "User"))"""
            )

            // DNQ configures string properties with case-insensitive collation. The equivalent
            // native traversal uses has("name", "Lev"), which currently returns only "Lev"
            // instead of honoring that schema collation for the linked vertices.
            assertThat(query.toList().map { it.name })
                .containsExactly("Lev", "lev", "leV")
        }
    }

    @Test
    fun `sorting string property respects case insensitive collation`() {
        store.transactional {
            listOf("a", "B").forEach { name ->
                StringCollationUser.new { this.name = name }
            }

            val result = StringCollationUser.all()
                .sortedBy(StringCollationUser::name)
                .toList()

            // Case-insensitive collation orders "a" before "B". Ordinary lexical comparison
            // would produce "B", "a", making this falsifiable against native Gremlin order.
            assertThat(result.map { it.name })
                .containsExactly("a", "B")
                .inOrder()
        }
    }

    @Test
    fun `sorting string property respects case insensitive collation with uppercase first value`() {
        store.transactional {
            listOf("A", "b").forEach { name ->
                StringCollationUser.new { this.name = name }
            }

            val result = StringCollationUser.all()
                .sortedBy(StringCollationUser::name)
                .toList()

            assertThat(result.map { it.name })
                .containsExactly("A", "b")
                .inOrder()
        }
    }

    @Test
    fun `link traversal respects string property collation for startsWith`() {
        store.transactional {
            val targetNames = listOf("Lev", "lev", "leV", "Levit", "Alex")
            targetNames.forEach { targetName ->
                val target = StringCollationUser.new { name = targetName }
                StringCollationUser.new {
                    name = "source-$targetName"
                    superviser = target
                }
            }

            val query = StringCollationUser.all()
                .mapDistinct(StringCollationUser::superviser)
                .filter { it.name startsWith "Lev" }

            val shape = GremlinQueryShape.of((query.entityIterable as YTDBEntityIterable).query)
            assertThat(shape).isEqualTo(
                """Dedup(Labeled(AndThen(FollowLink(Labeled(Where(All), "User"), OUT, "superviser"), MatchStringProp("name", Prefix, ?, ?, ?)), "User"))"""
            )

            // `startsWith` is case-insensitive by DNQ query semantics. The prefix query must
            // therefore include the three case variants and the longer matching value, but not
            // the unrelated target.
            assertThat(query.toList().map { it.name })
                .containsExactly("Lev", "lev", "leV", "Levit")
        }
    }
}
