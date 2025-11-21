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
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.entitystore.youtrackdb.iterate.YTDBEntityIterable
import kotlinx.dnq.DBTest
import kotlinx.dnq.SimpleModelPlugin
import kotlinx.dnq.XdModel
import kotlinx.dnq.xdLink0_1
import org.junit.Test


private var DBTest.User.fellow by xdLink0_1(DBTest.User)

class FilterQueryLinksTest : DBTest() {

    override fun registerEntityTypes() {
        super.registerEntityTypes()
        XdModel.withPlugins(
            SimpleModelPlugin(listOf(DBTest.User::fellow))
        )
    }

    @Test
    fun `search by undirected association should work`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            val contact1 = Contact.new {
                user = user1
                email = "123@test.com"
            }
            val contact2 = Contact.new {
                user = user1
                email = "123@test.com"
            }
            val contacts = listOf(contact1, contact2)


            assertThat(Contact.filter { it.user eq user1 }.toList()).containsExactlyElementsIn(contacts)

            assertThat(User.filter { it.contacts.isNotEmpty() }.toList()).containsExactly(user1)
            assertThat(User.filter { it.contacts.isEmpty() }.toList()).containsExactly(user2)
        }
    }

    @Test
    fun `simple search by directed association`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
                supervisor = user1
            }

            var result = User.all().filter { it.supervisor eq user1 }
            assertThat(result.toList()).containsExactly(user2)

            result = User.filter { it.supervisor eq user1 }
            assertThat(result.toList()).containsExactly(user2)
        }
    }

    @Test
    fun `simple search by extension link`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
                fellow = user1
            }

            var result = User.all().filter  { it.fellow eq user1 }
            assertThat(result.toList()).containsExactly(user2)

            result = User.filter { it.fellow eq user1 }
            assertThat(result.toList()).containsExactly(user2)
        }
    }

    @Test
    fun `simple search by contains`() {
        store.transactional {

            val user1 = User.new {
                login = "user 1"
                skill = 1
            }
            val user2 = User.new {
                login = "user 2"
                skill = 2
            }

            val contact1 = Contact.new {
                email = "xxx@123.com"
                user = user1
            }
            val contact2 = Contact.new {
                email = "123@123.com"
                user = user1
            }
            val contact3 = Contact.new {
                email = "bbb@123.com"
                user = user2
            }


            var result = User.filter { it.contacts contains contact1 }

            assertThat(result.toList()).containsExactly(user1)

            result = User.filter { it.contacts contains contact3 }
            assertThat(result.toList()).containsExactly(user2)

            result = User.filter { it.contacts containsIn listOf(contact1, contact3) }
            assertThat(result.toList()).containsExactly(user1, user2)

            result = User.filter { it.contacts containsIn listOf(contact1, contact2) }
            assertThat(result.toList()).containsExactly(user1)
        }
    }

    @Test
    fun `search, then union and intersect`() {
        val (user0, user2) = store.transactional {
            val user0 = User.new {
                login = "user 0"
                skill = 1
            }
            val user1 = User.new {
                login = "user 1"
                skill = 1
                supervisor = user0
            }
            val user2 = User.new {
                login = "user 2"
                skill = 1
                supervisor = user1
            }
            val user3 = User.new {
                login = "user 3"
                skill = 1
                supervisor = user1
            }

            Pair(user0, user2)
        }

        store.transactional { tx ->
            val user2and3 = User.filter { it.supervisor?.supervisor eq user0 }
            val user2and3It = user2and3.entityIterable as YTDBEntityIterable

            val user1ByLink = (User.queryOf(user2).entityIterable as YTDBEntityIterable).selectMany("boss") as YTDBEntityIterable
            val user2ById = User.queryOf(user2).entityIterable as YTDBEntityIterable

            val user1and2It = user1ByLink.union(user2ById) as YTDBEntityIterable

            assertThat(user1and2It.toList().map { it.getProperty("login") }).containsExactly("user 1", "user 2")
            assertThat(user2and3It.toList().map { it.getProperty("login") }).containsExactly("user 2", "user 3")

            val user2Only = user2and3It.intersect(user1and2It) as YTDBEntityIterable

            assertThat(user2Only.toList().map { it.getProperty("login") }).containsExactly("user 2")
        }
    }
}
