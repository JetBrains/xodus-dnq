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

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.*
import org.joda.time.DateTime
import org.junit.Test

private var DBTest.User.inn by xdStringProp<DBTest.User>(dbName = "_inn_")

class FilterQueryPropertiesTest : DBTest() {

    @Test
    fun `firstOrNull should return null if nothing found`() {
        store.transactional {
            assertThat(User.filter {}.firstOrNull()).isNull()
        }
    }

    @Test
    fun `simple search should work`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            User.assertThatFilterResult { it.login = "test" }.containsExactly(user1)
            User.assertThatFilterResult { it.login = "test1" }.containsExactly(user2)

            User.assertThatFilterResult { it.skill = 0 }.isEmpty()
            User.assertThatFilterResult { it.skill = 1 }.containsExactly(user1)
            User.assertThatFilterResult { it.skill = 2 }.containsExactly(user2)
        }
    }

    @Test
    fun `should filter by property`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            User.assertThatFilterResult { it.login = "test" }.containsExactly(user1)
            User.assertThatFilterResult { it.login = "test1" }.containsExactly(user2)
        }
    }

    @Test
    fun `should found by null property`() {
        store.transactional {
            User.new {
                login = "test"
                name = "some"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            User.assertThatFilterResult { it.name = null }.containsExactly(user2)
        }
    }

    @Test
    fun `should found by less and greater property`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            val user2 = User.new {
                login = "test1"
                skill = 2
            }

            User.assertThatFilterResult { it.skill lt 2 }.containsExactly(user1)
            User.assertThatFilterResult { it.skill gt 1 }.containsExactly(user2)
        }
    }

    @Test
    fun `should found by not value property`() {
        store.transactional {
            val user1 = User.new {
                login = "test"
                skill = 1
            }
            User.new {
                login = "test1"
                skill = 2
            }
            val user3 = User.new {
                login = "test2"
                skill = 3
            }

            User.assertThatFilterResult { it.skill ne 2 }.containsExactly(user1, user3)
        }
    }

    @Test
    fun `should apply multiple causes`() {
        store.transactional {
            val user1 = User.new {
                login = "test1"
                name = "test"
                skill = 1
            }
            User.new {
                login = "test2"
                name = "test"
                skill = 2
            }
            User.new {
                login = "test3"
                name = "test"
                skill = 2
            }

            User.assertThatFilterResult {
                it.name = "test"
                it.skill ne 2
            }.containsExactly(user1)
        }
    }

    @Test
    fun `should search by between`() {
        store.transactional {
            val user1 = User.new {
                login = "test1"
                skill = 1
            }
            val user2 = User.new {
                login = "test2"
                skill = 2
            }
            val user3 = User.new {
                login = "test3"
                skill = 7
            }

            User.assertThatFilterResult { it.skill between (1 to 3) }
                    .containsExactly(user1, user2)
            User.assertThatFilterResult { it.login startsWith "test" }
                    .containsExactly(user1, user2, user3)
        }
    }

    @Test
    fun `should search by required fields`() {
        store.transactional {
            val user1 = User.new {
                login = "test1"
                skill = 1
            }
            User.assertThatFilterResult { it.skill ne 0 }.containsExactly(user1)
            User.assertThatFilterResult { it.login ne "" }.containsExactly(user1)
        }
    }

    @Test
    fun `should search by hierarchy properties`() {
        store.transactional {
            RootGroup.new {
                name = "root-group"
            }
        }
        store.transactional {
            assertThat(RootGroup.filter { it.name ne null }.toList().map { it.name })
                    .containsExactly("root-group")
        }
    }

    @Test
    fun `should search by Boolean property`() {
        store.transactional {
            User.new {
                login = "login"
                isMale = true
                isGuest = true
                skill = 1
            }
        }
        store.transactional {
            User.assertThatFilterResult { (it.isMale eq true) or (it.isGuest eq true) }
                    .hasSize(1)
        }
    }

    @Test
    fun `should search by Long property`() {
        store.transactional {
            User.new {
                login = "login"
                salary = 1
                skill = 1
            }
        }
        store.transactional {
            User.assertThatFilterResult { it.salary eq 1L }
                    .hasSize(1)
        }
    }

    @Test
    fun `should search by DateTime property`() {
        val date = DateTime()
        store.transactional {
            User.new {
                login = "login1"
                skill = 1
                registered = date
            }
            User.new {
                login = "login2"
                skill = 1
            }
        }
        store.transactional {
            User.assertThatFilterResult { it.registered eq date }.hasSize(1)
            User.assertThatFilterResult { it.registered eq null }.hasSize(1)
        }
    }

    @Test
    fun `should search by extension property`() {
        val date = DateTime()
        store.transactional {
            User.new {
                login = "login1"
                skill = 1
                registered = date
                inn = "123"
            }
            User.new {
                login = "login2"
                skill = 1
            }
        }
        store.transactional {
            User.assertThatFilterResult { it.inn eq "123" }.hasSize(1)
            User.assertThatFilterResult { it.inn eq null }.hasSize(1)
        }
    }


    private fun <T : XdEntity> XdEntityType<T>.assertThatFilterResult(clause: FilteringContext.(T) -> Unit): IterableSubject {
        return assertThat(this.filter(clause).toList())
    }
}

