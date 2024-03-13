/**
 * Copyright 2006 - 2024 JetBrains s.r.o.
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
import org.junit.Test

/**
 * @author Maxim.Mazin at date: 11.01.2007 time: 11:29:28
 */
class SearchByPropertyTest : DBTest() {

    @Test
    fun testSearchByProperty() {
        transactional {
            User.new { login = "guest"; skill = 0 }
        }

        transactional { txn ->
            assertThat(txn.getAll(User.entityType)).hasSize(1)
        }

        transactional { txn ->
            assertThat(txn.find("User", "login", "guest")).isNotEmpty()
        }
    }

}
