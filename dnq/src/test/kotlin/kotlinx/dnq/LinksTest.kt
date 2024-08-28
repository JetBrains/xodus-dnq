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
import kotlinx.dnq.query.*
import kotlinx.dnq.util.getAddedLinks
import kotlinx.dnq.util.getOldValue
import kotlinx.dnq.util.getRemovedLinks
import org.junit.Ignore
import org.junit.Test


class LinksTest : DBTest() {

    @Test
    fun `bidirectional many to many`() {
        store.transactional {
            val admin = User.new { login = "anakin"; skill = 1 }
            RootGroup.new {
                name = "Root"
                nestedGroups.add(NestedGroup.new { name = "A"; owner = admin })
                nestedGroups.add(NestedGroup.new { name = "B"; owner = admin })
                nestedGroups.add(NestedGroup.new { name = "C"; owner = admin })
            }
        }

        store.transactional {
            val root = RootGroup.query(RootGroup::name eq "Root").first()

            assertThat(root.entity.getLinks("nested"))
                    .isNotEmpty()
            assertThat(root.nestedGroups.toList().map { it.name })
                    .containsExactly("A", "B", "C")
            root.nestedGroups.asSequence().forEach {
                assertThat(it.parentGroup).isEqualTo(root)
                assertThat(it.entity.getLink("parent")).isEqualTo(root.entity)
            }
        }
    }

    @Test
    fun `bidirectional many to one`() {
        val contact = store.transactional {
            Contact.new {
                this.email = "1@1.com"
                this.user = User.new {
                    login = "1"
                    skill = 1
                }
            }
        }

        store.transactional {
            assertThat(contact.user.login).isEqualTo("1")
        }
    }

    @Test
    fun `bidirectional one to many`() {
        val user = store.transactional {
            User.new {
                login = "1"
                skill = 1
                contacts.add(Contact.new {
                    this.email = "1@1.com"
                })
            }
        }

        store.transactional {
            assertThat(user.contacts.first().email).isEqualTo("1@1.com")
        }
    }

    @Test
    fun `getAddedLinks should return added links`() {
        val user = store.transactional {
            User.new {
                login = "zeckson"
                skill = 1
            }
        }
        store.transactional {
            user.contacts.add(Contact.new { email = "zeckson@spb.ru" })
            assertThat(user.getAddedLinks(User::contacts).toList().map { it.email }).containsExactly("zeckson@spb.ru")
            assertThat(user.getRemovedLinks(User::contacts).toList()).isEmpty()
        }
    }

    @Test
    
    fun `getRemovedLinks should return removed links`() {
        val user = store.transactional {
            User.new {
                login = "zeckson"
                skill = 1
                contacts.add(Contact.new { email = "zeckson@spb.ru" })
            }
        }
        store.transactional {
            val contact = user.contacts.first()
            user.contacts.remove(contact)
            contact.delete()

            assertThat(user.getAddedLinks(User::contacts).toList()).isEmpty()
            assertThat(user.getRemovedLinks(User::contacts).toList().map { it.getOldValue(Contact::email) })
                    .containsExactly("zeckson@spb.ru")
        }
    }
}
