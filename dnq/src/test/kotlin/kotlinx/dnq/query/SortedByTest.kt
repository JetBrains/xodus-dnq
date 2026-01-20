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
import kotlinx.dnq.*
import org.junit.Before
import org.junit.Test

class SortedByTest : DBTest() {

    class User(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User>()

        var login by xdStringProp()
        var badge by xdLink0_1(Badge)
        override fun toString(): String {
            return "User(login=$login, badge=$badge)"
        }
    }

    class Badge(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Badge>()

        var name by xdStringProp()
        override fun toString(): String {
            return "Badge(name=$name)"
        }
    }

    val users by lazy {
        transactional {
            listOf(
                User.new { login = "2"; badge = Badge.new { name = "c" } },
                User.new { login = "6"; badge = Badge.new { name = null } },
                User.new { login = "3"; badge = Badge.new { name = "b" } },
                User.new { login = "1"; badge = Badge.new { name = "a" } },
                User.new { login = "4"; },
                User.new {},
                User.new { login = null },
                User.new { login = "5"; badge = Badge.new {} }

            )
        }
    }

    override fun registerEntityTypes() {
        XdModel.registerNodes(User, Badge)
    }

    @Before
    fun touchUsers() {
        users
    }

    @Test
    fun `sort by string property ascending`() {
        checkOrder(
            "asc",
            { sortedBy(User::login, asc = true) },
            compareBy(nullsLast()) { it.login }
        )
    }

    @Test
    fun `sort by string property descending`() {
        checkOrder(
            "desc",
            { sortedBy(User::login, asc = false) },
            compareBy(nullsLast(reverseOrder())) { it.login }
        )
    }

    @Test
    fun `sort by property of a link ascending`() {
        checkOrder(
            "linked asc",
            { sortedBy(User::badge, Badge::name, asc = true) },
            compareBy(nullsLast()) { it.badge?.name }
        )
    }

    @Test
    fun `sort by property of a link descending`() {
        checkOrder(
            "linked desc",
            { sortedBy(User::badge, Badge::name, asc = false) },
            compareBy(nullsLast(reverseOrder())) { it.badge?.name }
        )
    }

    private fun checkOrder(
        name: String,
        queryOrder: XdQuery<User>.() -> XdQuery<User>,
        expectedOrder: Comparator<User>
    ) {
        transactional {
            val result = queryOrder(User.all()).toList()
            println("$name: $result")
            assertThat(result).containsExactlyElementsIn(users)
            assertThat(result).isInOrder(expectedOrder)
        }
    }
}
