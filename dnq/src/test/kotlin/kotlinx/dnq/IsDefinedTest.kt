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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.query.first
import kotlinx.dnq.util.isDefined
import org.joda.time.DateTime
import org.junit.Test

class IsDefinedTest : DBTest() {
    @Test
    fun `isDefined should return false for undefined optional properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertThat(user.isDefined(User::name)).isFalse()
            assertThat(user.isDefined(User::isGuest)).isTrue()
            assertThat(user.isDefined(User::isMale)).isTrue()
            assertThat(user.isDefined(User::registered)).isFalse()
        }
    }

    @Test
    fun `isDefined should return true for defined optional properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                name = "Schepotev"
                registered = DateTime.now()
                isGuest = false
                isMale = true
            }
            assertThat(user.isDefined(User::name)).isTrue()
            assertThat(user.isDefined(User::isGuest)).isTrue()
            assertThat(user.isDefined(User::isMale)).isTrue()
            assertThat(user.isDefined(User::registered)).isTrue()
        }
    }

    @Test
    fun `isDefined should return false for undefined required properties`() {
        store.transactional {
            val user = User.new()
            assertThat(user.isDefined(User::login)).isFalse()
            assertThat(user.isDefined(User::skill)).isFalse()
            user.delete()
        }
    }

    @Test
    fun `isDefined should return true for defined required properties`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertThat(user.isDefined(User::login)).isTrue()
            assertThat(user.isDefined(User::skill)).isTrue()
        }
    }

    @Test
    fun `isDefined should return false for undefined optional link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
            }
            assertThat(user.isDefined(User::supervisor)).isFalse()
        }
    }

    @Test
    fun `isDefined should return true for defined optional link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                supervisor = User.new {
                    login = "pegov"
                    skill = 42
                }
            }
            assertThat(user.isDefined(User::supervisor)).isTrue()
        }
    }


    @Test
    fun `isDefined should return false for undefined required link`() {
        store.transactional {
            val contact = Contact.new()
            assertThat(contact.isDefined(Contact::user)).isFalse()
            contact.delete()
        }
    }

    @Test
    fun `isDefined should return true for defined required link`() {
        store.transactional {
            val user = User.new {
                login = "zeckson"
                skill = 1
                contacts.add(Contact.new { email = "zeckson@spb.com" })
            }
            assertThat(user.contacts.first().isDefined(Contact::user)).isTrue()
        }
    }


}