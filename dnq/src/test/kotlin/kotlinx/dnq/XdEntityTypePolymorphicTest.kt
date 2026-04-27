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
import kotlinx.dnq.query.exclude
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.filterIsInstance
import kotlinx.dnq.query.filterIsNotInstance
import kotlinx.dnq.query.plus
import kotlinx.dnq.query.sortedBy
import kotlinx.dnq.query.toList
import kotlinx.dnq.query.union
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
    fun `non-polymorphic all chained with filter returns only exact type instances`() {
        transactional {
            User.new { login = "user1"; skill = 5 }
            User.new { login = "user2"; skill = 1 }
            User.new { login = "user3"; skill = 10 }
        }

        transactional(readonly = true) {
            // QueryEngine.query() must match the tree result's polymorphic flag
            // to the instance's flag before intersecting, otherwise
            // requirePolymorphicMatch rejects the combination.
            val result = User.all(polymorphic = false).filter {
                it.skill gt 3
            }.toList()
            assertThat(result.map { it.login }).containsExactly("user1", "user3")
        }
    }

    @Test
    fun `non-polymorphic all with filter on hierarchy base type returns empty`() {
        transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "rootGroup" }
            NestedGroup.new { name = "nestedGroup"; parentGroup = root; owner = user }
        }

        transactional(readonly = true) {
            // Filtering non-polymorphic all() on an abstract base should still
            // return empty — no concrete instances of Group exist.
            val result = Group.all(polymorphic = false).filter {
                it.name eq "rootGroup"
            }.toList()
            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `non-polymorphic all with filterIsInstance returns only matching subtype`() {
        transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "rootGroup" }
            NestedGroup.new { name = "nestedGroup"; parentGroup = root; owner = user }
        }

        transactional(readonly = true) {
            // Non-polymorphic all on Group (abstract) returns empty,
            // so filterIsInstance on it should also return empty
            val result = Group.all(polymorphic = false).filterIsInstance(RootGroup).toList()
            assertThat(result).isEmpty()

            // Polymorphic all on Group returns all subtypes,
            // filterIsInstance narrows to RootGroup only
            val polyResult = Group.all().filterIsInstance(RootGroup).toList()
            assertThat(polyResult).hasSize(1)
            assertThat(polyResult.map { it.name }).containsExactly("rootGroup")
        }
    }

    @Test
    fun `non-polymorphic all with filterIsNotInstance excludes matching subtype`() {
        transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "rootGroup" }
            NestedGroup.new { name = "nestedGroup"; parentGroup = root; owner = user }
        }

        transactional(readonly = true) {
            // Non-polymorphic all on Group (abstract) returns empty,
            // so filterIsNotInstance should also return empty
            val result = Group.all(polymorphic = false).filterIsNotInstance(RootGroup).toList()
            assertThat(result).isEmpty()

            // Polymorphic all on Group returns all subtypes,
            // filterIsNotInstance excludes RootGroup, leaving only NestedGroup
            val polyResult = Group.all().filterIsNotInstance(RootGroup).toList()
            assertThat(polyResult).hasSize(1)
            assertThat(polyResult.map { it.name }).containsExactly("nestedGroup")
        }
    }

    @Test
    fun `non-polymorphic all on concrete type with filterIsInstance exercises real intersect`() {
        transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "rootGroup" }
            NestedGroup.new { name = "nestedGroup"; parentGroup = root; owner = user }
        }

        transactional(readonly = true) {
            // Non-polymorphic all on RootGroup (concrete) returns real instances,
            // so filterIsInstance actually exercises the intersect path
            val result = RootGroup.all(polymorphic = false).filterIsInstance(RootGroup).toList()
            assertThat(result.map { it.name }).containsExactly("rootGroup")

            // filterIsNotInstance(NestedGroup) on non-polymorphic RootGroup keeps all RootGroups
            val notNestedResult = RootGroup.all(polymorphic = false).filterIsNotInstance(NestedGroup).toList()
            assertThat(notNestedResult.map { it.name }).containsExactly("rootGroup")
        }
    }

    @Test
    fun `non-polymorphic all with chained filters preserves polymorphic flag`() {
        transactional {
            User.new { login = "user1"; skill = 5 }
            User.new { login = "user2"; skill = 1 }
            User.new { login = "user3"; skill = 10 }
        }

        transactional(readonly = true) {
            val result = User.all(polymorphic = false)
                .filter { it.skill gt 0 }
                .filter { it.skill gt 3 }
                .toList()
            assertThat(result.map { it.login }).containsExactly("user1", "user3")
        }
    }

    @Test
    fun `non-polymorphic all exclude single entity throws`() {
        val user1 = transactional {
            User.new { login = "user1"; skill = 5 }
        }
        transactional {
            User.new { login = "user2"; skill = 1 }
        }

        transactional(readonly = true) {
            // exclude(entity) wraps the entity in queryOf() which creates a ByIds
            // iterable (default polymorphic=true). Mixing polymorphic flags is rejected.
            assertFailsWith<IllegalArgumentException> {
                User.all(polymorphic = false).exclude(user1).toList()
            }
        }
    }

    @Test
    fun `non-polymorphic all union single entity throws`() {
        val user1 = transactional {
            User.new { login = "user1"; skill = 5 }
        }

        transactional(readonly = true) {
            // union(entity) wraps in queryOf() → ByIds (polymorphic=true).
            // Mixing polymorphic flags is rejected.
            assertFailsWith<IllegalArgumentException> {
                (User.all(polymorphic = false) union user1).toList()
            }
        }
    }

    @Test
    fun `non-polymorphic all plus single entity throws`() {
        val user1 = transactional {
            User.new { login = "user1"; skill = 5 }
        }
        transactional {
            User.new { login = "user2"; skill = 1 }
        }

        transactional(readonly = true) {
            // plus(entity) wraps in queryOf() → ByIds (polymorphic=true).
            // Mixing polymorphic flags is rejected.
            assertFailsWith<IllegalArgumentException> {
                (User.all(polymorphic = false) + user1).toList()
            }
        }
    }

    @Test
    fun `non-polymorphic all chained with sortedBy returns only exact type`() {
        transactional {
            val user = User.new { login = "owner"; skill = 1 }
            val root = RootGroup.new { name = "b_root" }
            RootGroup.new { name = "a_root" }
            NestedGroup.new { name = "c_nested"; parentGroup = root; owner = user }
        }

        transactional(readonly = true) {
            // Non-polymorphic sortedBy on abstract base returns empty —
            // would return 3 groups sorted if the flag were ignored
            val nonPolySorted = Group.all(polymorphic = false).sortedBy(Group::name).toList()
            assertThat(nonPolySorted).isEmpty()

            // Contrast: polymorphic sortedBy returns all subtypes in order
            val allGroupsSorted = Group.all().sortedBy(Group::name).toList()
            assertThat(allGroupsSorted.map { it.name })
                .containsExactly("a_root", "b_root", "c_nested").inOrder()

            // Known limitation: SortEngine.sort() creates a new iterable via
            // txn.sort() which defaults to polymorphic=true. The non-polymorphic
            // flag from the source is not propagated through the sort path.
            val sortedIterable = Group.all(polymorphic = false).sortedBy(Group::name).entityIterable
            assertThat((sortedIterable as YTDBEntityIterable).polymorphic).isTrue()
        }
    }
}
