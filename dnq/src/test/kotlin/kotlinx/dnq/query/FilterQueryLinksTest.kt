/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
                user = user2
                email = "123@test.com"
            }

            assertThat(Contact.filter { it.user = user1 }.first()).isEqualTo(contact1)
            assertThat(Contact.filter { it.user eq user1 }.first()).isEqualTo(contact1)
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

            var result = User.filter { it.supervisor = user1 }
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

            var result = User.filter { it.fellow = user1 }
            assertThat(result.toList()).containsExactly(user2)

            result = User.filter { it.fellow eq user1 }
            assertThat(result.toList()).containsExactly(user2)
        }
    }
}