package kotlinx.dnq.query

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth.assertThat
import kotlinx.dnq.DBTest
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.transactional
import org.joda.time.DateTime
import org.junit.Test

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
            User.new {
                login = "test3"
                skill = 7
            }

            User.assertThatFilterResult { it.skill between (1 to 3) }
                    .containsExactly(user1, user2)
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

    private fun <T : XdEntity> XdEntityType<T>.assertThatFilterResult(clause: (T) -> Unit): IterableSubject {
        return assertThat(this.filter(clause).toList())
    }
}
