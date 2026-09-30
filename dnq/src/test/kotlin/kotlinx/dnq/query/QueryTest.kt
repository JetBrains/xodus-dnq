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
package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import com.jetbrains.teamsys.dnq.database.TransientEntityIterable
import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterator
import kotlinx.dnq.DBTest
import org.junit.Test
import kotlin.test.assertFailsWith

class QueryTest : DBTest() {

    @Test
    fun `new singleton contains its unflushed member but not null or another entity`() {
        transactional {
            val fresh = User.new {
                login = "fresh"
                skill = 3
            }
            val other = User.new {
                login = "other"
                skill = 3
            }
            assertThat((fresh.entity as TransientEntity).isNew).isTrue()
            val query = User.singleton(fresh)

            assertThat(query.contains(fresh)).isTrue()
            assertThat(query.contains(fresh.entity)).isTrue()
            assertThat(query.contains(null as User?)).isFalse()
            assertThat(query.contains(null as Entity?)).isFalse()
            assertThat(query.contains(other)).isFalse()
        }
    }

    @Test
    fun `saved singleton contains the same id across entity wrappers`() {
        val (saved, other) = transactional {
            User.new {
                login = "saved"
                skill = 3
            } to User.new {
                login = "other"
                skill = 3
            }
        }
        transactional {
            val transient = saved.entity as TransientEntity
            assertThat(transient.isNew).isFalse()
            val query = User.singleton(saved)

            assertThat(query.contains(saved)).isTrue()
            assertThat(query.contains(transient.entity)).isTrue()
            assertThat(query.contains(other)).isFalse()
            assertThat(query.contains(null as User?)).isFalse()
            assertThat(query.contains(null as Entity?)).isFalse()
        }
    }

    @Test
    fun `empty queries contain neither entities nor null`() {
        transactional {
            val fresh = User.new {
                login = "fresh"
                skill = 3
            }
            val queries = listOf(
                User.emptyQuery(),
                User.singleton(null),
                emptyList<Entity>().asQuery(User),
                emptySequence<Entity>().asIterable().asQuery(User),
                TransientEntityIterable(emptySet()).asQuery(User)
            )
            queries.forEach { query ->
                assertThat(query.contains(fresh)).isFalse()
                assertThat(query.contains(null as User?)).isFalse()
                assertThat(query.contains(null as Entity?)).isFalse()
            }
        }
    }

    @Test
    fun `ordinary backings compare ids in both wrapper directions`() {
        val (saved, other) = transactional {
            User.new {
                login = "saved"
                skill = 3
            } to User.new {
                login = "other"
                skill = 3
            }
        }
        transactional {
            val transient = saved.entity as TransientEntity
            val persistent = transient.entity
            assertThat(transient.id).isEqualTo(persistent.id)
            assertThat(transient == persistent).isFalse()
            assertThat(persistent == transient).isFalse()

            listOf(transient, persistent).forEach { member ->
                val queries = listOf(
                    listOf(member).asQuery(User),
                    hashSetOf(member).asQuery(User),
                    sequenceOf(member).asIterable().asQuery(User)
                )
                queries.forEach { query ->
                    assertThat(query.contains(transient)).isTrue()
                    assertThat(query.contains(persistent)).isTrue()
                    assertThat(query.contains(other)).isFalse()
                    assertThat(query.contains(null as Entity?)).isFalse()
                }
            }

            val transientQuery = TransientEntityIterable(setOf(transient)).asQuery(User)
            assertThat(transientQuery.contains(persistent)).isTrue()
            assertThat(transientQuery.contains(other)).isFalse()
            assertThat(transientQuery.contains(null as Entity?)).isFalse()
        }
    }

    @Test
    fun `ordinary backing closes its database iterator on match and exhaustion`() {
        val (first, other) = transactional {
            User.new {
                login = "first"
                skill = 3
            } to User.new {
                login = "other"
                skill = 3
            }
        }
        transactional {
            val source = User.singleton(first).entityIterable
            lateinit var iterator: EntityIterator
            val query = Iterable<Entity> {
                (source.iterator() as EntityIterator).also {
                    iterator = it
                    assertThat(it.shouldBeDisposed()).isTrue()
                }
            }.asQuery(User)

            assertThat(query.contains(first)).isTrue()
            assertThat(iterator.shouldBeDisposed()).isFalse()
            assertThat(query.contains(other)).isFalse()
            assertThat(iterator.shouldBeDisposed()).isFalse()
        }
    }

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
            val loginsReversed = User.all()
                .sortedBy(User::login).reversed()
                .toList().map { it.login }
            assertThat(loginsReversed).containsExactly("user2", "user1").inOrder()
        }
    }
}
