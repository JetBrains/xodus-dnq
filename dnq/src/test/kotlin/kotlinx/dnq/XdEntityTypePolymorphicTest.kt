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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.PersistentEntityIterableWrapper
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.sortedBy
import kotlinx.dnq.query.toList
import org.junit.Test
import kotlin.test.assertFailsWith

class XdEntityTypePolymorphicTest : DBTest() {

    @Test
    fun `non-polymorphic all on leaf type with no subtypes behaves like polymorphic`() {
        transactional {
            User.new { login = "user1"; skill = 1 }
            User.new { login = "user2"; skill = 2 }
        }

        transactional(readonly = true) {
            val nonPoly = User.all(polymorphic = false).toList()
            val poly = User.all(polymorphic = true).toList()
            assertThat(nonPoly.map { it.login }).containsExactly("user1", "user2")
            assertThat(nonPoly).containsExactlyElementsIn(poly)
        }
    }

    @Test
    fun `non-polymorphic all on abstract base type returns empty`() {
        transactional {
            User.new { login = "user1"; skill = 1 }
            User.new { login = "user2"; skill = 2 }
        }

        transactional(readonly = true) {
            val result = BaseUser.all(polymorphic = false).toList()
            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `default polymorphic all on base type returns subtypes`() {
        transactional {
            User.new { login = "user1"; skill = 1 }
            User.new { login = "user2"; skill = 2 }
        }

        transactional(readonly = true) {
            val result = BaseUser.all().toList()
            assertThat(result).hasSize(2)
            assertThat(result.map { it.login }).containsExactly("user1", "user2")
        }
    }

    @Test
    fun `non-polymorphic all on concrete sibling returns only its instances`() {
        transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "rootGroup" }
            NestedGroup.new { name = "nestedGroup"; parentGroup = root; owner = user }
        }

        transactional(readonly = true) {
            val nestedOnly = NestedGroup.all(polymorphic = false).toList()
            assertThat(nestedOnly).hasSize(1)
            assertThat(nestedOnly.map { it.name }).containsExactly("nestedGroup")

            val rootOnly = RootGroup.all(polymorphic = false).toList()
            assertThat(rootOnly).hasSize(1)
            assertThat(rootOnly.map { it.name }).containsExactly("rootGroup")

            val allGroups = Group.all().toList()
            assertThat(allGroups).hasSize(2)
            assertThat(allGroups.map { it.name }).containsExactly("rootGroup", "nestedGroup")

            val nonPolyBase = Group.all(polymorphic = false).toList()
            assertThat(nonPolyBase).isEmpty()
        }
    }

    @Test
    fun `non-polymorphic all wraps result in PersistentEntityIterableWrapper with correct flag`() {
        transactional {
            User.new { login = "user1"; skill = 1 }
        }

        transactional(readonly = true) {
            val nonPolyQuery = User.all(polymorphic = false)
            val nonPolyIterable = nonPolyQuery.entityIterable
            assertThat(nonPolyIterable).isInstanceOf(PersistentEntityIterableWrapper::class.java)
            assertThat((nonPolyIterable as YTDBEntityIterable).polymorphic).isFalse()

            val polyQuery = User.all()
            val polyIterable = polyQuery.entityIterable
            assertThat(polyIterable).isInstanceOf(PersistentEntityIterableWrapper::class.java)
            assertThat((polyIterable as YTDBEntityIterable).polymorphic).isTrue()
        }
    }

    @Test
    fun `non-polymorphic all chained with filter throws due to polymorphic mismatch`() {
        // filter() internally intersects the non-polymorphic instance with a
        // default-polymorphic tree result from QueryEngine.query(), triggering
        // Track 2's combination validation. This is a known limitation:
        // filtering non-polymorphic queries requires lower-level APIs
        // (YTDBStoreTransaction.find* with polymorphic=false).
        transactional {
            User.new { login = "user1"; skill = 5 }
        }

        transactional(readonly = true) {
            val exception = assertFailsWith<IllegalArgumentException> {
                User.all(polymorphic = false).filter {
                    it.skill gt 3
                }.toList()
            }
            assertThat(exception.message).contains("non-polymorphic")
        }
    }

    @Test
    fun `non-polymorphic all chained with sortedBy returns only exact type`() {
        transactional {
            User.new { login = "b_user"; skill = 2 }
            User.new { login = "a_user"; skill = 1 }
        }

        transactional(readonly = true) {
            val sortedQuery = User.all(polymorphic = false).sortedBy(User::login)
            val sorted = sortedQuery.toList()
            assertThat(sorted.map { it.login }).containsExactly("a_user", "b_user").inOrder()

            // Known limitation: SortEngine.sort() creates a new iterable via
            // txn.sort() which defaults to polymorphic=true. The non-polymorphic
            // flag from the source is not propagated through the sort path.
            val sortedIterable = sortedQuery.entityIterable
            assertThat((sortedIterable as YTDBEntityIterable).polymorphic).isTrue()

            val baseSorted = BaseUser.all(polymorphic = false).sortedBy(BaseUser::login).toList()
            assertThat(baseSorted).isEmpty()
        }
    }
}
