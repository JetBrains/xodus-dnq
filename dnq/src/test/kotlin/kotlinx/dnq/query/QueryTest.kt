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
import jetbrains.exodus.database.TransientEntity
import kotlinx.dnq.DBTest
import org.junit.Test
import kotlin.test.assertFailsWith

class QueryTest : DBTest() {

    @Test
    fun `firstOrNull should return null if nothing found`() {
        store.transactional {
            assertThat(User.all().firstOrNull()).isNull()
        }
    }

    @Test
    fun `firstOrNull should return entity if something is there`() {
        store.transactional {
            User.new {
                login = "test"
                skill = 1
            }
            assertThat(User.all().firstOrNull()).isNotNull()
        }
    }

    @Test
    fun `first should throw if nothing found`() {
        store.transactional {
            assertFailsWith<NoSuchElementException> {
                User.all().first()
            }
        }
    }

    @Test
    fun `first should return entity if something is there`() {
        store.transactional {
            User.new {
                login = "test"
                skill = 1
            }
            assertThat(User.all().firstOrNull()).isNotNull()
        }
    }

    @Test
    fun `query should obey custom db names of link properties`() {
        store.transactional {
            User.new {
                login = "user1"
                skill = 5
                supervisor = User.new {
                    login = "boss"
                    skill = 555
                }
            }
        }

        store.transactional {
            assertThat(User.query(User::supervisor ne null).size()).isEqualTo(1)
        }
    }

    @Test
    fun `take & drop should return query of TransientEntities`() {
        store.transactional {
            (1..2).forEach {
                User.new {
                    login = "user$it"
                    skill = 5
                }
            }
        }

        store.transactional {
            assertThat(User.all().drop(1).entityIterable.iterator().next()).isInstanceOf(TransientEntity::class.java)
            assertThat(User.all().take(1).entityIterable.iterator().next()).isInstanceOf(TransientEntity::class.java)
        }
    }

    @Test
    fun `reverse should return reversed query of TransientEntities`() {
        store.transactional {
            (1..2).forEach {
                User.new {
                    login = "user$it"
                    skill = 5
                }
            }
        }

        store.transactional {
            assertThat(User.all().reversed().toList()).isInStrictOrder(object: Comparator<User>{
                override fun compare(o1: User, o2: User): Int {
                    return (o2.entity.id.localId - o1.entity.id.localId).toInt()
                }

            })
        }
    }
}
