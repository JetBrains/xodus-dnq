package kotlinx.dnq.query

import com.google.common.truth.Truth.assertThat
import jetbrains.exodus.database.TransientEntity
import kotlinx.dnq.DBTest
import kotlinx.dnq.transactional
import org.junit.Test
import java.util.*
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
}