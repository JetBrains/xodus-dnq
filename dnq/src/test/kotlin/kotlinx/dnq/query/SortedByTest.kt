/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
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

import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.*
import org.junit.Before
import org.junit.Test

class SortedByTest : DBTest() {

    class User(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<User>()

        var login by xdStringProp()
        var badge by xdLink0_1(Badge)
    }

    class Badge(entity: Entity) : XdEntity(entity) {
        companion object : XdNaturalEntityType<Badge>()

        var name by xdStringProp()
    }

    val users by lazy {
        transactional {
            listOf(
                    User.new { login = "2"; badge = Badge.new { name = "c" } },
                    User.new { login = "3"; badge = Badge.new { name = "b" } },
                    User.new { login = "1"; badge = Badge.new { name = "a" } }
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
    fun `sort by int property ascending`() {
        transactional {
            assertQuery(User.all().sortedBy(User::login, asc = true))
                    .containsExactlyElementsIn(users.sortedBy { it.login })
                    .inOrder()
        }
    }

    @Test
    fun `sort by int property descending`() {
        transactional {
            assertQuery(User.all().sortedBy(User::login, asc = false))
                    .containsExactlyElementsIn(users.sortedByDescending { it.login })
                    .inOrder()
        }
    }

    @Test
    fun `sort by property of a link ascending`() {
        transactional {
            assertQuery(User.all().sortedBy(User::badge, Badge::name, asc = true))
                    .containsExactlyElementsIn(users.sortedBy { it.badge?.name })
                    .inOrder()
        }
    }

    @Test
    fun `sort by property of a link descending`() {
        transactional {
            assertQuery(User.all().sortedBy(User::badge, Badge::name, asc = false))
                    .containsExactlyElementsIn(users.sortedByDescending { it.badge?.name })
                    .inOrder()
        }
    }
}
