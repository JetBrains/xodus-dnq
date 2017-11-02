/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
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

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.transactional
import org.junit.Before
import org.junit.Test

class FilterQueryDecoratedTest : DBTest() {

    @Before
    fun fillDB() {
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
            val user3 = User.new {
                login = "test3"
                skill = 3
                supervisor = user2
            }

            Contact.new {
                user = user1
                email = "1@123.com"
            }
            Contact.new {
                user = user2
                email = "2@123.com"
            }

            Contact.new {
                user = user3
                email = "3@123.com"
            }
        }
    }

    @Test
    fun `searching by link property on same types`() {
        store.transactional {
            User.assertThatFilterResult { it.supervisor?.login eq "test" }
                    .containsUsers("test1")

            User.assertThatFilterResult { it.supervisor?.login = "test" }
                    .containsUsers("test1")
        }
    }

    @Test
    fun `searching by link property on different types`() {
        store.transactional {
            Contact.assertThatFilterResult { it.user.login eq "test3" }
                    .containsContacts("3@123.com")


            Contact.assertThatFilterResult { it.user.login = "test3" }
                    .containsContacts("3@123.com")
        }
    }

    @Test
    fun `searching by link property should works with AND`() {
        store.transactional {
            Contact.assertThatFilterResult { (it.user.login eq "test3") and (it.email eq "1@123.com") }
                    .isEmpty()

            Contact.assertThatFilterResult { (it.user.login eq "test3") and (it.email eq "3@123.com") }
                    .containsContacts("3@123.com")
        }
    }

    @Test
    fun `searching by link property should works with OR`() {
        store.transactional {
            Contact.assertThatFilterResult { (it.user.login eq "test3") or (it.email eq "1@123.com") }
                    .containsContacts("3@123.com", "1@123.com")

            Contact.assertThatFilterResult { (it.user.login eq "test3") or (it.user.login eq "test") }
                    .containsContacts("3@123.com", "1@123.com")
        }
    }

    @Test
    fun `searching by link property should works with isIn`() {
        store.transactional {
            Contact.assertThatFilterResult { it.user.login isIn listOf("test3", "test") }
                    .containsContacts("3@123.com", "1@123.com")
        }
    }

    @Test
    fun `searching by link property should works with second level`() {
        store.transactional {
            Contact.assertThatFilterResult { it.user.supervisor?.login eq "test" }
                    .containsContacts("2@123.com")
        }
    }

    @Test
    fun `searching by link property should works with link equation`() {
        store.transactional {
            val user = User.filter { it.login eq "test" }.first()
            Contact.assertThatFilterResult { it.user.supervisor ne user }
                    .containsContacts("1@123.com", "3@123.com")
        }
    }

    fun IterableSubject.containsContacts(vararg contacts: String) {
        containsExactlyElementsIn(contacts.map { contact -> Contact.filter { it.email eq contact }.first() })
    }

    fun IterableSubject.containsUsers(vararg logins: String) {
        containsExactlyElementsIn(logins.map { login -> User.filter { it.login eq login }.first() })
    }

    fun <T : XdEntity> XdEntityType<T>.assertThatFilterResult(clause: FilteringContext.(T) -> Unit): IterableSubject {
        return assertThat(this.filter(clause).toList())
    }
}