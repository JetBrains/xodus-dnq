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
import kotlinx.dnq.query.toList
import org.junit.Test

class XdEntityTypePolymorphicTest : DBTest() {

    @Test
    fun `non-polymorphic all on leaf type returns only exact instances`() {
        transactional {
            User.new { login = "user1"; skill = 1 }
            User.new { login = "user2"; skill = 2 }
        }

        transactional(readonly = true) {
            val result = User.all(polymorphic = false).toList()
            assertThat(result).hasSize(2)
            assertThat(result.map { it.login }).containsExactly("user1", "user2")
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
}
