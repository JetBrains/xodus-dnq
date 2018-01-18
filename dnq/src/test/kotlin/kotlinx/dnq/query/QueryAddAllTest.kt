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
import org.junit.Test

class QueryAddAllTest : DBTest() {

    @Test
    fun `addAll(Sequence) should add all elements`() {
        assertAddAll { users ->
            this.users.addAll(users)
        }
    }

    @Test
    fun `addAll(Iterable) should add all elements`() {
        assertAddAll { users ->
            this.users.addAll(users.asIterable())
        }
    }

    @Test
    fun `addAll(XdQuery) should add all elements`() {
        assertAddAll { users ->
            this.users.addAll(users.asIterable().map { it.entity }.asQuery(User))
        }
    }

    fun assertAddAll(addAll: Group.(Sequence<User>) -> Unit) {
        val users = store.transactional {
            sequenceOf(
                    User.new { login = "1"; skill = 1 },
                    User.new { login = "2"; skill = 3 }
            )
        }

        val group = store.transactional { RootGroup.new { name = "group" } }

        store.transactional {
            group.addAll(users)
        }

        store.transactional {
            assertThat(group.users.toList().map { it.login }).containsExactly("1", "2")
        }
    }
}