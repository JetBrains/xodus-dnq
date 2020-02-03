/**
 * Copyright 2006 - 2020 JetBrains s.r.o.
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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.query.first
import kotlinx.dnq.util.getSafe
import org.junit.Test

class GetSafeTest : DBTest() {

    @Test
    fun `getSafe should return null for undefined properties`() {
        store.transactional {
            val user = User.new()
            assertThat(user.getSafe(User::login)).isNull()
            assertThat(user.getSafe(User::skill)).isNull()
            user.delete()
        }
    }

    @Test
    fun `getSafe should return value for defined properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertThat(user.getSafe(User::login)).isEqualTo("zeckson")
            assertThat(user.getSafe(User::skill)).isEqualTo(1)
        }
    }


    @Test
    fun `getSafe should return null for undefined link`() {
        store.transactional {
            val contact = Contact.new()
            assertThat(contact.getSafe(Contact::user)).isNull()
            contact.delete()
        }
    }

    @Test
    fun `getSafe should return value for defined link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                contacts.add(Contact.new { email = "zeckson@spb.com" })
            }
            assertThat(user.contacts.first().getSafe(Contact::user)?.login).isEqualTo("zeckson")
        }
    }


}