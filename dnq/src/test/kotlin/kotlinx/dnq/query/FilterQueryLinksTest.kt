/**
 * Copyright 2006 - 2023 JetBrains s.r.o.
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
import kotlinx.dnq.DBTest
import kotlinx.dnq.xdLink0_1
import org.junit.Test


private var DBTest.User.fellow by xdLink0_1(DBTest.User)

class FilterQueryLinksTest : DBTest() {

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
            Contact.new {
                user = user1
                email = "123@test.com"
            }

            assertThat(Contact.all().filterUnsafe { it.user = user1 }.first()).isEqualTo(contact1)
            assertThat(Contact.filter { it.user eq user1 }.first()).isEqualTo(contact1)

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

            var result = User.all().filterUnsafe { it.supervisor = user1 }
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

            var result = User.all().filterUnsafe  { it.fellow = user1 }
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
}
